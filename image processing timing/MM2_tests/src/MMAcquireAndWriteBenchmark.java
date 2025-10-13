import mmcorej.CMMCore;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

public class MMAcquireAndWriteBenchmark {

    // Defaults
    private static final int DEFAULT_WIDTH  = 512;
    private static final int DEFAULT_HEIGHT = 512;
    private static final int DEFAULT_TRIALS = 5;

    // Prefault controls (to mimic warm HCD buffer)
    private static final boolean ENABLE_PREFAULT      = true; // pre-warm mapped pages
    private static final boolean PREFETCH_TOUCH_PAGES = true; // walk pages read-only

    public static void main(String[] args) throws Exception {
        if (args.length == 1 || args.length > 5) {
            System.err.println("Usage: java MMAcquireAndWriteBenchmark [width height [trials [outDir]]]");
            System.exit(2);
        }

        // --- Parse CLI ---
        int width  = DEFAULT_WIDTH;
        int height = DEFAULT_HEIGHT;
        int trials = DEFAULT_TRIALS;
        Path outDir = Paths.get(".");

        if (args.length >= 1) width  = parsePos(args[0], "width");
        if (args.length >= 2) height = parsePos(args[1], "height");
        if (args.length >= 3) trials = parsePos(args[2], "trials");
        if (args.length >= 4) outDir = Paths.get(args[3]);
        Files.createDirectories(outDir);

        // --- Initialize MM Core ---
        CMMCore core = new CMMCore();
        core.loadSystemConfiguration("config/MMConfig_Demo.cfg");

        System.out.printf("Benchmark (HCD-style mapping reuse): %dx%d px, %d trials -> %s%n",
                width, height, trials, outDir.toAbsolutePath());

        int bytesPerPixel = (int) core.getBytesPerPixel();  // MM returns long
        int frameBytes    = width * height * bytesPerPixel;
        int mappedBytes   = align4096(frameBytes);

        // --- Create single file + mapping up front ---
        Path outPath = outDir.resolve(String.format("mm_acquire_%dx%d.bin", width, height));
        try (RandomAccessFile raf = new RandomAccessFile(outPath.toFile(), "rw");
             FileChannel ch = raf.getChannel()) {

            raf.setLength(mappedBytes);
            MappedByteBuffer mbb = ch.map(FileChannel.MapMode.READ_WRITE, 0, mappedBytes);

            // Optional: pre-fault to remove cold penalties
            if (ENABLE_PREFAULT) {
                try { mbb.load(); } catch (Throwable ignore) {}
                if (PREFETCH_TOUCH_PAGES) {
                    final int page = 4096;
                    for (int p = 0; p < mappedBytes; p += page) {
                        byte b = mbb.get(p); // read-only touch
                        if ((b & 1) == 2) System.out.print(""); // defeat DCE
                    }
                    mbb.position(0);
                }
            }

            // Timing buckets
            List<Double> snapMs    = new ArrayList<>(trials);
            List<Double> getMs     = new ArrayList<>(trials);
            List<Double> convertMs = new ArrayList<>(trials);
            List<Double> copyMs    = new ArrayList<>(trials);    // <-- ONLY the mbb.put(..) time
            List<Double> end2endMs = new ArrayList<>(trials);

            for (int t = 1; t <= trials; t++) {
                long endStart = System.nanoTime();

                long t0 = System.nanoTime();
                core.snapImage();                 // acquisition
                long t1 = System.nanoTime();

                Object img = core.getImage();     // transfer into JVM
                long t2 = System.nanoTime();

                byte[] data = convertToBytes(img); // convert to byte[]
                long t3 = System.nanoTime();

                // HCD-style write timing: measure ONLY the copy into the pre-allocated mapping
                mbb.position(0);
                long c0 = System.nanoTime();
                mbb.put(data);                    // copy into already-mapped buffer
                long c1 = System.nanoTime();

                long endEnd = System.nanoTime();

                // Record
                snapMs.add((t1 - t0) / 1e6);
                getMs.add((t2 - t1) / 1e6);
                convertMs.add((t3 - t2) / 1e6);
                copyMs.add((c1 - c0) / 1e6);
                end2endMs.add((endEnd - endStart) / 1e6);

                System.out.printf("Trial %d/%d: Snap=%.3f | Get=%.3f | Convert=%.3f | Copy=%.3f | End2End=%.3f -> %s%n",
                        t, trials,
                        (t1 - t0) / 1e6,
                        (t2 - t1) / 1e6,
                        (t3 - t2) / 1e6,
                        (c1 - c0) / 1e6,
                        (endEnd - endStart) / 1e6,
                        outPath.toAbsolutePath());
            }

            // Summaries
            summarize("Snap",    snapMs);
            summarize("Get",     getMs);
            summarize("Convert", convertMs);
            summarize("Copy",    copyMs);    // copy-only metric (what we call "Write" in HCD)
            summarize("End2End", end2endMs);

            System.out.println("Final image available at: " + outPath.toAbsolutePath());
        }
    }

    // ---- Helpers ----------------------------------------------------------------------

    private static byte[] convertToBytes(Object img) {
        if (img instanceof short[]) {
            short[] s = (short[]) img;
            ByteBuffer buf = ByteBuffer.allocate(s.length * 2);
            for (short v : s) buf.putShort(v);
            return buf.array();
        } else if (img instanceof byte[]) {
            return (byte[]) img;
        } else if (img instanceof int[]) {
            int[] i = (int[]) img;
            ByteBuffer buf = ByteBuffer.allocate(i.length * 4);
            for (int v : i) buf.putInt(v);
            return buf.array();
        }
        throw new IllegalArgumentException("Unsupported image type: " + img.getClass());
    }

    private static int align4096(int n) {
        int r = n & 4095;
        return (r == 0) ? n : (n + (4096 - r));
    }

    private static int parsePos(String s, String name) {
        int v = Integer.parseInt(s);
        if (v <= 0) throw new IllegalArgumentException(name + " must be > 0");
        return v;
    }

    private static void summarize(String label, List<Double> vals) {
        if (vals.isEmpty()) {
            System.out.printf("Summary (%s): no data%n", label);
            return;
        }
        double[] sorted = vals.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        double min = sorted[0], max = sorted[sorted.length - 1];
        double mean = Arrays.stream(sorted).average().orElse(Double.NaN);
        double median = percentile(sorted, 50);
        double sd = stddev(sorted, mean);
        System.out.printf("Summary (%s): min=%.3f | max=%.3f | mean=%.3f | median=%.3f | std=%.3f%n",
                label, min, max, mean, median, sd);
    }

    private static double percentile(double[] sorted, double p) {
        double rank = (p / 100.0) * (sorted.length - 1);
        int lo = (int) Math.floor(rank), hi = (int) Math.ceil(rank);
        if (lo == hi) return sorted[lo];
        double w = rank - lo;
        return sorted[lo] * (1 - w) + sorted[hi] * w;
    }

    private static double stddev(double[] a, double mean) {
        double s = 0; for (double v : a) { double d = v - mean; s += d * d; }
        return Math.sqrt(s / a.length);
    }
}
