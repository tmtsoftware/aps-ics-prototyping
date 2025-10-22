import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AssemblyReadSingleShot
 *
 * Single-shot READ timing probe for the Assembly side.
 * Maps the file containing one frame and measures per-trial read time in:
 *   - copy mode:  copies the entire frame into a byte[] (worst case, realistic if Assembly copies)
 *   - touch mode: touches one byte per 4 KiB page (best case, metadata/verification without full copy)
 *
 * Stabilization:
 *   - Prefault:   map.load() + linear read-only touches to bring pages resident (no dirty IO)
 *   - CPU warmup: short spin to stabilize clocks/JIT
 *   - Burn-in:    none performed as it hides the cost of disk access
 *
 * Usage:
 *   java AssemblyReadSingleShot <path> <width> <height> [bytesPerPixel] [mode] [trials]
 *     path           Absolute path to the single-shot container
 *     width/height   ROI in pixels
 *     bytesPerPixel  1, 2, or 4 (default 2)
 *     mode           copy | touch (default copy)
 *     trials         number of measured trials (default 1)
 *
 * Output:
 *   - per-trial: copy ms and end-to-end ms
 *   - summary:   min/max/mean/median/stddev and IQR outliers (by original trial index)
 */
public class AssemblyReadSingleShot {

  // Defaults
  private static final int    DEFAULT_BPP    = 2;
  private static final String DEFAULT_MODE   = "copy";
  private static final int    DEFAULT_TRIALS = 1;

  // Stabilization knobs (no CLI)
  private static final boolean ENABLE_PREFAULT     = false;   // map.load + page touch
  private static final int     CPU_WARMUP_MS       = 100;    // short spin
  private static final int     BURN_IN_TRIALS      = 1;      // print but don’t summarize
  private static final int     PAGE_SIZE           = 4096;

  public static void main(String[] args) throws IOException {
    if (args.length < 3 || args.length > 6) {
      System.err.println("Usage: java AssemblyReadSingleShot <path> <width> <height> [bytesPerPixel] [mode] [trials]");
      System.exit(2);
    }

    Path path   = Paths.get(args[0]);
    int  width  = parsePos(args[1], "width");
    int  height = parsePos(args[2], "height");
    int  bpp    = (args.length >= 4) ? parsePos(args[3], "bytesPerPixel") : DEFAULT_BPP;
    String mode = (args.length >= 5) ? args[4].toLowerCase() : DEFAULT_MODE;
    int  trials = (args.length == 6)  ? parsePos(args[5], "trials")       : DEFAULT_TRIALS;

    if (!mode.equals("copy") && !mode.equals("touch"))
      throw new IllegalArgumentException("mode must be 'copy' or 'touch'");

    final long frameBytes = Math.multiplyExact((long)width * (long)height, bpp);
    final long fileBytes  = Files.size(path);
    if (fileBytes < frameBytes) {
      throw new IllegalArgumentException("File smaller than expected frame size: file=" + fileBytes + " < frame=" + frameBytes);
    }

    System.out.printf("Assembly single-shot read -> %s (%,d bytes)%n", path.toAbsolutePath(), fileBytes);
    System.out.printf("Config: %dx%d @ %d B/px, mode=%s, trials=%d%n", width, height, bpp, mode, trials);

    List<Double> copyMs    = new ArrayList<>(trials);
    List<Double> end2endMs = new ArrayList<>(trials);

    try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
      MappedByteBuffer map = ch.map(FileChannel.MapMode.READ_ONLY, 0, frameBytes);

      // Prefault: hint + linear page touches (read-only)
      if (ENABLE_PREFAULT) {
        try { map.load(); } catch (Throwable ignore) {}
        for (long p = 0; p < frameBytes; p += PAGE_SIZE) {
          byte b = map.get((int)p);
          if ((b & 1) == 2) System.out.print(""); // keep JIT from eliding
        }
        // touch last byte if not exact multiple
        if ((frameBytes % PAGE_SIZE) != 0) {
          byte b = map.get((int)(frameBytes - 1));
          if ((b & 1) == 2) System.out.print("");
        }
      }

      cpuWarmupMillis(CPU_WARMUP_MS);

      // Burn-in (timed but not summarized)
     // runOneRead(map, mode, (int)frameBytes, true, copyMs, end2endMs); // prints line, does not add to lists

      // Measured trials
      for (int t = 1; t <= trials; t++) {
        double[] res = timeOneRead(map, mode, (int)frameBytes);
        System.out.printf("Trial %d/%d: copy=%.3f ms | end-to-end=%.3f ms%n", t, trials, res[0], res[1]);
        copyMs.add(res[0]);
        end2endMs.add(res[1]);
      }
    }

    summarize("copy ms",    copyMs);
    summarize("end-to-end", end2endMs);
  }

  // --- One read (with optional burn-in print) ---
  private static void runOneRead(MappedByteBuffer map, String mode, int frameBytes,
                                 boolean printOnly, List<Double> copyMs, List<Double> end2endMs) {
    double[] res = timeOneRead(map, mode, frameBytes);
    if (printOnly) {
      System.out.printf("Burn-in: copy=%.3f ms | end-to-end=%.3f ms%n", res[0], res[1]);
    } else {
      copyMs.add(res[0]); end2endMs.add(res[1]);
    }
  }

  // --- Time a single read according to mode ---
  private static double[] timeOneRead(MappedByteBuffer map, String mode, int frameBytes) {
    long tStart = System.nanoTime();

    map.position(0);
    long t0 = System.nanoTime();

    if ("copy".equals(mode)) {
      byte[] dst = new byte[frameBytes];
      map.get(dst);
      // prevent dead-code elimination
      if ((dst[0] ^ dst[dst.length - 1]) == 123) System.out.print("");
    } else { // "touch"
      for (int p = 0; p < frameBytes; p += PAGE_SIZE) {
        byte b = map.get(p);
        if ((b & 1) == 2) System.out.print("");
      }
      if ((frameBytes % PAGE_SIZE) != 0) {
        byte b = map.get(frameBytes - 1);
        if ((b & 1) == 2) System.out.print("");
      }
    }

    long t1 = System.nanoTime();
    long tEnd = System.nanoTime();

    double copyMs    = (t1 - t0)    / 1e6;
    double end2endMs = (tEnd - tStart) / 1e6;
    return new double[]{copyMs, end2endMs};
  }

  // --- Helpers ---
  private static int parsePos(String s, String name) {
    int v = Integer.parseInt(s);
    if (v <= 0) throw new IllegalArgumentException(name + " must be > 0");
    return v;
  }

  private static void cpuWarmupMillis(int ms) {
    long end = System.nanoTime() + (long)ms * 1_000_000L;
    long x = 0;
    while (System.nanoTime() < end) { x += 31; x ^= (x << 7); }
    if (x == 42) System.out.print("");
  }

  // --- Stats (correct outlier indexing) ---
  private static void summarize(String label, List<Double> vals) {
    final int n = vals.size();
    if (n == 0) {
      System.out.printf("Summary (%s): no data%n", label);
      return;
    }

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

    List<Integer> outIdx = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      double v = vals.get(i);
      if (v < lo || v > hi) outIdx.add(i + 1);  // original trial indices (1-based)
    }

    System.out.printf(
        "Summary (%s): min=%.3f ms | max=%.3f ms | mean=%.3f ms | median=%.3f ms | std=%.3f ms | IQR=[%.3f, %.3f] -> outlier if < %.3f or > %.3f%n",
        label, min, max, meanSorted, median, sd, q1, q3, lo, hi);

    if (!outIdx.isEmpty()) {
      System.out.printf("  Outliers by IQR at trials %s%n", outIdx);
    }
  }

  private static double mean(double[] a) { double s=0; for(double v:a) s+=v; return s/a.length; }
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