import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AssemblyReadBurst
 *
 * Burst READ timing probe for the Assembly side.
 * Reads multiple burst container files (one per trial) to measure realistic first-access performance.
 * Each trial reads a different file written by MemoryMapWriteBurst, avoiding OS page cache warming.
 *
 * Maps each container file in ≤2 GiB windows and measures:
 *   - copy mode:  full container read into a reusable chunked byte[] buffer
 *   - touch mode: page-sampling (1 byte per 4 KiB) across the container
 *
 * Stabilization:
 *   - Prefault (optional): read-only page touches per window to warm cache (disabled by default)
 *   - CPU warmup: short spin to stabilize clocks/JIT
 *   - NO BURN-IN: Each trial reads a fresh file to measure realistic first-access cost
 *
 * Usage:
 *   java AssemblyReadBurst <outDir> <width> <height> <frameCount> [bytesPerPixel] [mode] [trials]
 *     outDir         directory containing burst container files
 *     width/height   ROI in pixels
 *     frameCount     number of frames per container
 *     bytesPerPixel  1, 2, or 4 (default 2)
 *     mode           copy | touch (default copy)
 *     trials         number of files to read (default 1)
 *
 * Files are auto-discovered using pattern:
 *   burst_container_{width}x{height}_{bpp}bpp_{frameCount}frames_trial{NN}.bin
 *
 * Output:
 *   - per-trial: copy ms, end-to-end ms, remap ms, remap count
 *   - summary:   min/max/mean/median/std + IQR outliers (original trial indices)
 */
public class AssemblyReadBurst {

  // Defaults & limits
  private static final int    DEFAULT_BPP    = 2;
  private static final String DEFAULT_MODE   = "copy";
  private static final int    DEFAULT_TRIALS = 1;

  private static final long   WINDOW_BYTES   = 2_000_000_000L;     // ≤2 GiB per map window
  private static final int    PAGE_SIZE      = 4096;
  private static final int    COPY_CHUNK     = 16 * 1024 * 1024;   // 16 MiB reusable buffer

  // Stabilization knobs (no CLI)
  private static final boolean ENABLE_PREFAULT = false;  // set true to warm pages before measurement
  private static final int     CPU_WARMUP_MS   = 100;    // short spin to stabilize clocks/JIT

  public static void main(String[] args) throws IOException {
    if (args.length < 4 || args.length > 7) {
      System.err.println("Usage: java AssemblyReadBurst <outDir> <width> <height> <frameCount> [bytesPerPixel] [mode] [trials]");
      System.exit(2);
    }

    Path outDir   = Paths.get(args[0]);
    int  width    = parsePos(args[1], "width");
    int  height   = parsePos(args[2], "height");
    int  frames   = parsePos(args[3], "frameCount");
    int  bpp      = (args.length >= 5) ? parsePos(args[4], "bytesPerPixel") : DEFAULT_BPP;
    String mode   = (args.length >= 6) ? args[5].toLowerCase() : DEFAULT_MODE;
    int  trials   = (args.length == 7)  ? parsePos(args[6], "trials")       : DEFAULT_TRIALS;

    if (!mode.equals("copy") && !mode.equals("touch"))
      throw new IllegalArgumentException("mode must be 'copy' or 'touch'");
    if (!Files.isDirectory(outDir))
      throw new IllegalArgumentException("outDir must be a directory: " + outDir);

    final long frameBytes     = Math.multiplyExact(Math.multiplyExact((long) width, (long) height), (long) bpp);
    final long containerBytes = Math.multiplyExact(frameBytes, (long) frames);

    System.out.printf("Assembly burst read -> %s%n", outDir.toAbsolutePath());
    System.out.printf("Config: %dx%d @ %d B/px, frames=%d, mode=%s, trials=%d%n", width, height, bpp, frames, mode, trials);
    System.out.printf("Expected container size: %,d bytes%n", containerBytes);

    // Build file paths for all trials
    List<Path> filePaths = new ArrayList<>(trials);
    for (int t = 1; t <= trials; t++) {
      String filename = String.format("burst_container_%dx%d_%dbpp_%dframes_trial%02d.bin",
          width, height, bpp * 8, frames, t);
      Path path = outDir.resolve(filename);
      if (!Files.exists(path)) {
        throw new IllegalArgumentException("Required file not found: " + path + 
            "\nGenerate files first using: java MemoryMapWriteBurst " + width + " " + height + " " + 
            frames + " " + outDir + " " + bpp + " random " + trials);
      }
      long fileBytes = Files.size(path);
      if (fileBytes < containerBytes) {
        throw new IllegalArgumentException("File smaller than expected: " + path + 
            " (file=" + fileBytes + " < expected=" + containerBytes + ")");
      }
      filePaths.add(path);
    }

    // Reusable copy buffer (for copy mode)
    final byte[] dstChunk = mode.equals("copy") ? new byte[(int) Math.min(COPY_CHUNK, containerBytes)] : new byte[1];

    List<Double> copyMs    = new ArrayList<>(trials);
    List<Double> end2endMs = new ArrayList<>(trials);
    List<Double> remapMs   = new ArrayList<>(trials);
    List<Integer> remaps   = new ArrayList<>(trials);

    // CPU warmup once at start
    cpuWarmupMillis(CPU_WARMUP_MS);

    // Read each file once (no burn-in per trial)
    for (int t = 0; t < trials; t++) {
      Path path = filePaths.get(t);
      
      try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
        if (ENABLE_PREFAULT) {
          prefault(ch, containerBytes); // warm up page cache (optional)
        }

        Trial r = timeOneRead(ch, containerBytes, mode, dstChunk);
        copyMs.add(r.copyMs);
        end2endMs.add(r.endToEndMs);
        remapMs.add(r.remapMs);
        remaps.add(r.remaps);
        System.out.printf("Trial %d/%d (%s): copy=%.3f ms | end-to-end=%.3f ms | remap=%.3f ms | remaps=%d%n",
            t + 1, trials, path.getFileName(), r.copyMs, r.endToEndMs, r.remapMs, r.remaps);
      }
    }

    summarize("copy ms",    copyMs);
    summarize("end-to-end", end2endMs);
    summarize("remap ms",   remapMs);
    int[] rArr = remaps.stream().mapToInt(Integer::intValue).toArray();
    System.out.println("Remaps (count): min=" + Arrays.stream(rArr).min().orElse(0) +
        " max=" + Arrays.stream(rArr).max().orElse(0) +
        " mean=" + String.format("%.2f", mean(rArr)));
  }

  // --- Single full-container timed read ---
  private static Trial timeOneRead(FileChannel ch, long containerBytes, String mode, byte[] dstChunk) throws IOException {
    long copyNs = 0L, remapNs = 0L;
    int  remaps = 0;

    long tStart = System.nanoTime();

    for (long start = 0; start < containerBytes; start += WINDOW_BYTES) {
      int len = (int) Math.min(WINDOW_BYTES, containerBytes - start);

      long m0 = System.nanoTime();
      MappedByteBuffer map = ch.map(FileChannel.MapMode.READ_ONLY, start, len);
      long m1 = System.nanoTime();
      remapNs += (m1 - m0);
      remaps++;

      if ("copy".equals(mode)) {
        // Chunked copy across the current window
        int pos = 0;
        while (pos < len) {
          int n = Math.min(dstChunk.length, len - pos);
          map.position(pos);
          long c0 = System.nanoTime();
          map.get(dstChunk, 0, n);
          long c1 = System.nanoTime();
          copyNs += (c1 - c0);
          // Prevent dead-code elimination
          int s = (dstChunk[0] & 0xFF) ^ (dstChunk[n - 1] & 0xFF);
          if (s == 257) System.out.print("");
          pos += n;
        }
      } else {
        // Touch mode: sample a byte per page across the window
        long c0 = System.nanoTime();
        for (int p = 0; p < len; p += PAGE_SIZE) {
          byte b = map.get(p);
          if ((b & 1) == 2) System.out.print("");
        }
        if ((len % PAGE_SIZE) != 0) {
          byte b = map.get(len - 1);
          if ((b & 1) == 2) System.out.print("");
        }
        long c1 = System.nanoTime();
        copyNs += (c1 - c0);
      }
    }

    long tEnd = System.nanoTime();
    return new Trial(
        copyNs / 1e6,
        (tEnd - tStart) / 1e6,
        remapNs / 1e6,
        remaps
    );
  }

  // --- Optional prefault pass (READ_ONLY page touches across the whole container) ---
  private static void prefault(FileChannel ch, long containerBytes) throws IOException {
    for (long start = 0; start < containerBytes; start += WINDOW_BYTES) {
      int len = (int) Math.min(WINDOW_BYTES, containerBytes - start);
      MappedByteBuffer ro = ch.map(FileChannel.MapMode.READ_ONLY, start, len);
      try { ro.load(); } catch (Throwable ignore) {}
      for (int p = 0; p < len; p += PAGE_SIZE) {
        byte b = ro.get(p);
        if ((b & 1) == 2) System.out.print("");
      }
      if ((len % PAGE_SIZE) != 0) {
        byte b = ro.get(len - 1);
        if ((b & 1) == 2) System.out.print("");
      }
    }
  }

  // --- Helpers & stats ---
  private static int parsePos(String s, String name) {
    int v = Integer.parseInt(s);
    if (v <= 0) throw new IllegalArgumentException(name + " must be > 0");
    return v;
  }

  private static void cpuWarmupMillis(int ms) {
    long end = System.nanoTime() + (long) ms * 1_000_000L;
    long x = 0;
    while (System.nanoTime() < end) { x += 31; x ^= (x << 7); }
    if (x == 42) System.out.print("");
  }

  private static class Trial {
    final double copyMs, endToEndMs, remapMs; final int remaps;
    Trial(double c, double e, double r, int m){ copyMs=c; endToEndMs=e; remapMs=r; remaps=m; }
  }

  private static void summarize(String label, List<Double> vals) {
    final int n = vals.size();
    if (n == 0) { System.out.printf("Summary (%s): no data%n", label); return; }
    double[] sorted = vals.stream().mapToDouble(Double::doubleValue).toArray();
    Arrays.sort(sorted);

    double min = sorted[0], max = sorted[n - 1];
    double meanSorted = mean(sorted);
    double median = percentile(sorted, 50);
    double sd = stddev(sorted, meanSorted);
    double q1 = percentile(sorted, 25), q3 = percentile(sorted, 75);
    double iqr = q3 - q1, lo = q1 - 1.5 * iqr, hi = q3 + 1.5 * iqr;

    List<Integer> outIdx = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      double v = vals.get(i);
      if (v < lo || v > hi) outIdx.add(i + 1); // original trial indices (1-based)
    }

    System.out.printf(
        "Summary (%s): min=%.3f ms | max=%.3f ms | mean=%.3f ms | median=%.3f ms | std=%.3f ms | IQR=[%.3f, %.3f] -> outlier if < %.3f or > %.3f%n",
        label, min, max, meanSorted, median, sd, q1, q3, lo, hi);
    if (!outIdx.isEmpty()) System.out.printf("  Outliers by IQR at trials %s%n", outIdx);
  }

  private static double mean(double[] a) { double s=0; for(double v:a) s+=v; return s/a.length; }
  private static double mean(int[] a)    { double s=0; for(int v:a) s+=v; return s/(double)a.length; }
  private static double percentile(double[] sorted, double p) {
    if (sorted.length == 0) return Double.NaN;
    double rank = (p / 100.0) * (sorted.length - 1);
    int lo = (int)Math.floor(rank), hi = (int)Math.ceil(rank);
    if (lo == hi) return sorted[lo];
    double w = rank - lo; return sorted[lo]*(1-w) + sorted[hi]*w;
  }
  private static double stddev(double[] a, double mean) {
    double s=0; for(double v:a){ double d=v-mean; s+=d*d; } return Math.sqrt(s/a.length);
  }
}