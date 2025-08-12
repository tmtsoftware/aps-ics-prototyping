/**
 * MemoryMapWriteSingleShot
 *
 * Single-shot timing probe for the Detector HCD copy path.
 * Measures the time to copy ONE image frame into a memory-mapped file
 * using a single bulk ByteBuffer.put(...). Also reports an “end-to-end”
 * timing that includes lightweight positioning overhead.
 *
 * What it measures:
 *   - copy-only:   time around mbb.put(src) (pure bulk copy)
 *   - end-to-end:  position + bulk copy (no mapping or flush)
 *
 * Not measured:
 *   - camera/SDK/µManager delivery latency
 *   - fsync/force() or disk flushes
 *
 * Usage:
 *   java MemoryMapWriteSingleShot [width height [outDir] [bytesPerPixel]]
 *     width/height: ROI in pixels (default 1020x1020)
 *     outDir:       output directory (default $TMPDIR/aps or /tmp/aps)
 *   Assumes 16-bit pixels (2 B/px). Adjust in code if needed.
 *
 * Output:
 *   - path of the mapped file
 *   - frame size in bytes (and mapped length)
 *   - copy-only ms, end-to-end ms
 *   - throughput in GiB/s for both timings
 *
 * Notes:
 *   - The file is created/truncated once and mapped once (size = frame bytes).
 *   - No per-pixel loops; a single bulk copy reflects HCD behaviour.
 */

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

public class MemoryMapWriteSingleShot {
  // Defaults
  static final int DEFAULT_WIDTH  = 1020;
  static final int DEFAULT_HEIGHT = 1020;
  static final int DEFAULT_BPP    = 2; // 16-bit

  static int align4096(int n) { int r = n & 4095; return (r == 0) ? n : (n + (4096 - r)); }

  public static void main(String[] args) throws IOException {
    // Usage: [width height [outDir] [bytesPerPixel]]
    if (args.length == 1 || args.length > 4) {
      System.err.println("Usage: java MemoryMapWriteSingleShot [width height [outDir] [bytesPerPixel]]");
      System.exit(2);
    }

    int width  = DEFAULT_WIDTH;
    int height = DEFAULT_HEIGHT;
    int bpp    = DEFAULT_BPP;

    // Parse width/height if provided
    if (args.length >= 2) {
      try {
        width  = Integer.parseInt(args[0]);
        height = Integer.parseInt(args[1]);
        if (width <= 0 || height <= 0) throw new NumberFormatException("non-positive");
      } catch (NumberFormatException nfe) {
        System.err.println("Width/height must be positive integers. Got: " + String.join(" ", args));
        System.exit(2);
      }
    }

    // Choose output dir: explicit arg -> $TMPDIR/aps -> /tmp/aps
    Path outDir;
    if (args.length >= 3) {
      outDir = Paths.get(args[2]);
    } else {
      String tmpEnv = System.getenv("TMPDIR");
      outDir = (tmpEnv != null && !tmpEnv.isBlank())
          ? Paths.get(tmpEnv).resolve("aps")
          : Paths.get("/tmp").resolve("aps");
    }
    Files.createDirectories(outDir);

    // Optional bytes-per-pixel
    if (args.length == 4) {
      try {
        bpp = Integer.parseInt(args[3]);
        if (bpp <= 0) throw new NumberFormatException("non-positive");
      } catch (NumberFormatException nfe) {
        System.err.println("bytesPerPixel must be a positive integer. Got: " + args[3]);
        System.exit(2);
      }
    }

    final int frameBytes  = Math.multiplyExact(Math.multiplyExact(width, height), bpp);
    final int mappedBytes = align4096(frameBytes);
    final Path path = outDir.resolve(
        String.format("single_shot_%dx%d_%dbpp.bin", width, height, bpp * 8));

    // Simulated camera bytes; contents don’t affect copy timing
    final byte[] src = new byte[frameBytes];

    try (FileChannel ch = FileChannel.open(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING)) {

      ch.truncate(mappedBytes);
      var mbb = ch.map(FileChannel.MapMode.READ_WRITE, 0, mappedBytes);

      // Warm-up (JIT + page touch), not timed
      mbb.position(0);
      mbb.put(src);
      mbb.position(0);

      // Measure end-to-end (position + copy) and copy-only separately
      long endToEndStartNs = System.nanoTime();
      mbb.position(0);                      // included in end-to-end
      long t0 = System.nanoTime();
      mbb.put(src);                         // ONE bulk copy
      long t1 = System.nanoTime();
      long endToEndEndNs = System.nanoTime();

      double copyMs     = (t1 - t0) / 1_000_000.0;
      double endToEndMs = (endToEndEndNs - endToEndStartNs) / 1_000_000.0;

      double giB = 1024.0 * 1024.0 * 1024.0;
      double copyGiBs     = (frameBytes / giB) / ((t1 - t0) / 1e9);
      double endToEndGiBs = (frameBytes / giB) / ((endToEndEndNs - endToEndStartNs) / 1e9);

      System.out.printf(
          "Single-shot bulk copy -> %dx%d @ %d B/px => %,d bytes (mapped %,d) -> %s%n",
          width, height, bpp, frameBytes, mappedBytes, path.toAbsolutePath());
      System.out.printf("Totals: copy=%.3f ms | end-to-end=%.3f ms%n", copyMs, endToEndMs);
      System.out.printf("Throughput: copy-only=%.2f GiB/s | end-to-end=%.2f GiB/s%n",
          copyGiBs, endToEndGiBs);
    }
  }
}