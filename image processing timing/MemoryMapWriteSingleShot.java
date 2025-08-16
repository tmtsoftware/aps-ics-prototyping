import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MemoryMapWriteSingleShot
 *
 * Single-shot timing probe for the HCD copy path.
 * Copies ONE frame into a memory-mapped file using one bulk put(...),
 * and reports per-trial copy and end-to-end timings plus summary stats.
 *
 * This version adds a PRE-FAULT phase designed to remove cold-run penalties:
 *   1) map.load() to hint the OS to page in the mapping
 *   2) read-only page touch across the mapped region (no dirty pages)
 *   3) short CPU warm-up to stabilize clocks/JIT
 *   4) one timed burn-in copy (printed but not included in stats)
 *
 * Usage:
 *   java MemoryMapWriteSingleShot [width height [outDir] [bytesPerPixel] [pattern] [trials]]
 *     width/height   ROI in pixels (default 1020×1020)
 *     outDir         output directory (default $TMPDIR/aps or /tmp/aps)
 *     bytesPerPixel  1=8-bit, 2=16-bit, 4=32-bit (default 2)
 *     pattern        { zero | random | ramp | alt } (default random)
 *     trials         number of timed trials (default 1)
 *
 * Output:
 *   - per-trial totals: copy ms, end-to-end ms
 *   - summary stats across trials (min/max/mean/median/stddev + IQR outliers)
 *
 * Notes:
 *   - We map once and reuse the mapping across trials.
 *   - We do NOT force() between trials; this keeps the page cache hot and
 *     avoids writeback contention in the memory-bandwidth regime.
 *   - For a "disk-honest" regime, you could enable force() and sleeps
 *     between trials (see constants below), but that's not typical for HCD.
 */
public class MemoryMapWriteSingleShot {
  // -------- Defaults --------
  private static final int DEFAULT_WIDTH  = 1020;
  private static final int DEFAULT_HEIGHT = 1020;
  private static final int DEFAULT_BPP    = 2;   // bytes per pixel

  // -------- Pre-fault controls (no CLI) --------
  private static final boolean ENABLE_PREFAULT            = true;  // run pre-fault phase before timed trials
  private static final boolean PREFETCH_TOUCH_PAGES       = true;  // read-only page-touch (no dirty)
  private static final int     WARMUP_WRITES              = 0;     // full untimed writes (keep 0 for single-shot)
  private static final boolean FORCE_FLUSH_AFTER_PREFAULT = false; // don't kick APFS writeback before trials
  private static final int     SLEEP_AFTER_PREFAULT_MS    = 500;   // modest breather

  // -------- Per-trial behavior (kept minimal) --------
  private static final boolean FORCE_FLUSH_EACH_TRIAL     = false; // usually false for memory-bandwidth view
  private static final int     SLEEP_BETWEEN_TRIALS_MS    = 0;     // no sleep by default
  private static final int     BURN_IN_TRIALS             = 1;     // one timed burn-in (printed, not summarized)

  // -------- Main --------
  public static void main(String[] args) throws IOException {
    if (args.length == 1 || args.length > 6) {
      System.err.println("Usage: java MemoryMapWriteSingleShot [width height [outDir] [bytesPerPixel] [pattern] [trials]]");
      System.exit(2);
    }

    int width  = DEFAULT_WIDTH;
    int height = DEFAULT_HEIGHT;
    if (args.length >= 2) {
      width  = parsePos(args[0], "width");
      height = parsePos(args[1], "height");
    }

    Path outDir;
    if (args.length >= 3) outDir = Paths.get(args[2]);
    else {
      String tmp = System.getenv("TMPDIR");
      outDir = (tmp != null && !tmp.isBlank()) ? Paths.get(tmp).resolve("aps") : Paths.get("/tmp", "aps");
    }
    Files.createDirectories(outDir);

    int bpp = (args.length >= 4) ? parsePos(args[3], "bytesPerPixel") : DEFAULT_BPP;
    String pattern = (args.length >= 5) ? args[4].toLowerCase() : "random";
    int trials = (args.length >= 6) ? parsePos(args[5], "trials") : 1;

    final int frameBytes  = Math.multiplyExact(Math.multiplyExact(width, height), bpp);
    final int mappedBytes = align4096(frameBytes);
    final Path path = outDir.resolve(String.format("single_shot_%dx%d_%dbpp.bin", width, height, bpp * 8));

    // Source buffer filled once (outside timing)
    final byte[] src = new byte[frameBytes];
    fill(src, pattern);

    System.out.printf("Single-shot -> %dx%d @ %d B/px (%s) => %,d bytes (mapped %,d) -> %s%n",
        width, height, bpp, pattern, frameBytes, mappedBytes, path.toAbsolutePath());

    final List<Double> copyMsList    = new ArrayList<>(trials);
    final List<Double> end2endMsList = new ArrayList<>(trials);

    try (FileChannel ch = FileChannel.open(
        path,
        StandardOpenOption.CREATE, StandardOpenOption.READ,
        StandardOpenOption.WRITE,  StandardOpenOption.TRUNCATE_EXISTING)) {

      ch.truncate(mappedBytes);
      MappedByteBuffer mbb = ch.map(FileChannel.MapMode.READ_WRITE, 0, mappedBytes);

      // -------- PRE-FAULT: remove cold penalties without dirtying --------
      if (ENABLE_PREFAULT) {
        // Hint OS to page in (may be a no-op on some JDKs/OSes)
        try { mbb.load(); } catch (Throwable ignore) {}

        if (PREFETCH_TOUCH_PAGES) {
          final int page = 4096;
          MappedByteBuffer ro = ch.map(FileChannel.MapMode.READ_ONLY, 0, mappedBytes);
          for (int p = 0; p < mappedBytes; p += page) {
            byte b = ro.get(p);
            if ((b & 1) == 2) System.out.print(""); // keep JIT honest (avoid DCE)
          }
        }

        // Optional untimed warm-up writes (disabled for single-shot)
        for (int i = 0; i < WARMUP_WRITES; i++) {
          mbb.position(0);
          mbb.put(src);
          mbb.position(0);
        }

        if (FORCE_FLUSH_AFTER_PREFAULT) { try { ch.force(true); } catch (Throwable ignore) {} }
        if (SLEEP_AFTER_PREFAULT_MS > 0) {
          try { Thread.sleep(SLEEP_AFTER_PREFAULT_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
      }

      // Short CPU warm-up to stabilize clocks/JIT
      cpuWarmupMillis(100);

      // -------- Timed burn-in (not included in stats) --------
      for (int b = 0; b < BURN_IN_TRIALS; b++) {
        long end2endStart = System.nanoTime();
        mbb.position(0);
        long t0 = System.nanoTime();
        mbb.put(src);
        long t1 = System.nanoTime();
        long end2endEnd = System.nanoTime();
        System.out.printf("Burn-in: copy=%.3f ms | end-to-end=%.3f ms%n",
            (t1 - t0) / 1e6, (end2endEnd - end2endStart) / 1e6);
      }

      // -------- Measured trials --------
      for (int t = 1; t <= trials; t++) {
        long end2endStart = System.nanoTime();
        mbb.position(0);                 // included in end-to-end
        long t0 = System.nanoTime();
        mbb.put(src);                    // bulk copy
        long t1 = System.nanoTime();
        long end2endEnd = System.nanoTime();

        double copyMs    = (t1 - t0) / 1e6;
        double end2endMs = (end2endEnd - end2endStart) / 1e6;

        copyMsList.add(copyMs);
        end2endMsList.add(end2endMs);

        System.out.printf("Trial %d/%d: copy=%.3f ms | end-to-end=%.3f ms%n",
            t, trials, copyMs, end2endMs);

        if (FORCE_FLUSH_EACH_TRIAL) { try { ch.force(true); } catch (Throwable ignore) {} }
        if (SLEEP_BETWEEN_TRIALS_MS > 0) {
          try { Thread.sleep(SLEEP_BETWEEN_TRIALS_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
      }
    }

    // -------- Summaries --------
    summarize("copy ms",    copyMsList);
    summarize("end-to-end", end2endMsList);
  }

  // -------- Helpers --------
  private static int align4096(int n) {
    int r = n & 4095;
    return (r == 0) ? n : (n + (4096 - r));
  }

  private static int parsePos(String s, String name) {
    int v = Integer.parseInt(s);
    if (v <= 0) throw new IllegalArgumentException(name + " must be > 0");
    return v;
  }

  private static void fill(byte[] a, String pattern) {
    switch (pattern) {
      case "zero": /* leave zeros */ break;
      case "alt":  for (int i = 0; i < a.length; i++) a[i] = (byte)((i & 1) == 0 ? 0x55 : 0xAA); break;
      case "ramp": for (int i = 0; i < a.length; i++) a[i] = (byte)i; break;
      case "random":
      default: ThreadLocalRandom.current().nextBytes(a); break;
    }
  }

  private static void cpuWarmupMillis(int ms) {
    long end = System.nanoTime() + (long) ms * 1_000_000L;
    long x = 0;
    while (System.nanoTime() < end) { x += 31; x ^= (x << 7); }
    if (x == 42) System.out.print(""); // prevent dead-code elimination
  }

  // -------- Stats --------
  private static void summarize(String label, List<Double> vals) {
    final int n = vals.size();
    if (n == 0) {
      System.out.printf("Summary (%s): no data%n", label);
      return;
    }

    // Work on a sorted copy to compute quantiles
    double[] sorted = vals.stream().mapToDouble(Double::doubleValue).toArray();
    Arrays.sort(sorted);

    double min = sorted[0];
    double max = sorted[n - 1];
    double meanSorted = mean(sorted);
    double median = percentile(sorted, 50);
    double sd = stddev(sorted, meanSorted);
    double q1 = percentile(sorted, 25);
    double q3 = percentile(sorted, 75);
    double iqr = q3 - q1;
    double lo = q1 - 1.5 * iqr;
    double hi = q3 + 1.5 * iqr;

    // Map outliers back to ORIGINAL trial indices (1-based)
    List<Integer> outIdx = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      double v = vals.get(i);
      if (v < lo || v > hi) outIdx.add(i + 1);
    }

    System.out.printf(
        "Summary (%s): min=%.3f ms | max=%.3f ms | mean=%.3f ms | median=%.3f ms | std=%.3f ms | IQR=[%.3f, %.3f] -> outlier if < %.3f or > %.3f%n",
        label, min, max, meanSorted, median, sd, q1, q3, lo, hi);

    if (!outIdx.isEmpty()) {
      System.out.printf("  Outliers by IQR at trials %s%n", outIdx);
    }
  }

  private static double mean(double[] a) {
    double s = 0; for (double v : a) s += v; return s / a.length;
  }

  private static double percentile(double[] sorted, double p) {
    if (sorted.length == 0) return Double.NaN;
    double rank = (p / 100.0) * (sorted.length - 1);
    int lo = (int) Math.floor(rank), hi = (int) Math.ceil(rank);
    if (lo == hi) return sorted[lo];
    double w = rank - lo; return sorted[lo] * (1 - w) + sorted[hi] * w;
  }

  private static double stddev(double[] a, double mean) {
    double s = 0; for (double v : a) { double d = v - mean; s += d * d; }
    return Math.sqrt(s / a.length);
  }
}