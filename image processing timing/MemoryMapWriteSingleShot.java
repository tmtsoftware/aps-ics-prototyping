import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

public class MemoryMapWriteSingleShot {
  // Defaults: 1020x1020 @ 16-bit
  static final int DEFAULT_WIDTH = 1020;
  static final int DEFAULT_HEIGHT = 1020;
  static final int BYTES_PER_PIXEL = 2; // 16-bit pixels

  static int align4096(int n) { int r = n & 4095; return (r == 0) ? n : (n + (4096 - r)); }

  public static void main(String[] args) throws IOException {
    if (args.length == 1 || args.length > 3) {
      System.err.println("Usage: java MemoryMapWriteSingleShot [width height [outDir]]");
      System.exit(2);
    }

    int width = DEFAULT_WIDTH, height = DEFAULT_HEIGHT;
    if (args.length >= 2) {
      try {
        width = Integer.parseInt(args[0]);
        height = Integer.parseInt(args[1]);
        if (width <= 0 || height <= 0) throw new NumberFormatException("non-positive");
      } catch (NumberFormatException nfe) {
        System.err.println("Width/height must be positive integers. Got: " + String.join(" ", args));
        System.exit(2);
      }
    }

    // Pick output dir: $TMPDIR/aps (mac preferred) -> /tmp/aps (fallback) unless provided
    Path outDir;
    if (args.length == 3) {
      outDir = Paths.get(args[2]);
    } else {
      String tmpEnv = System.getenv("TMPDIR");
      outDir = (tmpEnv != null && !tmpEnv.isBlank())
          ? Paths.get(tmpEnv).resolve("aps")
          : Paths.get("/tmp").resolve("aps");
    }
    Files.createDirectories(outDir);

    final int frameBytes  = Math.multiplyExact(Math.multiplyExact(width, height), BYTES_PER_PIXEL);
    final int mappedBytes = align4096(frameBytes);
    final Path path = outDir.resolve(
        String.format("single_shot_%dx%d_%dbpp.bin", width, height, BYTES_PER_PIXEL * 8));

    // Simulated µManager image bytes; contents don’t affect copy timing
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
      mbb.position(0);                      // tiny but included in end-to-end
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
          width, height, BYTES_PER_PIXEL, frameBytes, mappedBytes, path.toAbsolutePath());
      System.out.printf("Totals: copy=%.3f ms | end-to-end=%.3f ms%n", copyMs, endToEndMs);
      System.out.printf("Throughput: copy-only=%.2f GiB/s | end-to-end=%.2f GiB/s%n",
          copyGiBs, endToEndGiBs);
    }
  }
}