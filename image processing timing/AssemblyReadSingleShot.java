/**
 * AssemblyReadSingleShot
 *
 * Single-shot read timing probe for the Assembly.
 * Maps ONE image file (READ_ONLY) and measures:
 *   - copy-only: bulk ByteBuffer.get(dst) of the whole frame
 *   - end-to-end: position + bulk get (no fsync/force), or page-touch loop
 *
 * Usage:
 *   java AssemblyReadSingleShot <path> <width> <height> [bytesPerPixel=2] [mode=copy|touch]
 *     path          : absolute path to the single-shot container file
 *     width/height  : ROI in pixels (e.g., 1020 1020)
 *     bytesPerPixel : 1 for 8-bit, 2 for 16-bit (default 2)
 *     mode          : 'copy' (default) or 'touch'
 *
 * Output:
 *   - file path/size
 *   - copy ms, end-to-end ms
 *   - throughput in GiB/s for copy-only and end-to-end
 */
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class AssemblyReadSingleShot {
  public static void main(String[] args) throws IOException {
    if (args.length < 3 || args.length > 5) {
      System.err.println("Usage: java AssemblyReadSingleShot <path> <width> <height> [bytesPerPixel=2] [mode=copy|touch]");
      System.exit(2);
    }

    Path path   = Paths.get(args[0]);
    int  width  = parsePosInt(args[1], "width");
    int  height = parsePosInt(args[2], "height");
    int  bpp    = (args.length >= 4) ? parsePosInt(args[3], "bytesPerPixel") : 2;
    String mode = (args.length == 5) ? args[4].toLowerCase() : "copy";
    boolean touch = mode.equals("touch");

    final int frameBytes = Math.multiplyExact(Math.multiplyExact(width, height), bpp);
    final byte[] dst = touch ? null : new byte[frameBytes];

    try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
      long fileSize = ch.size();
      if (fileSize < frameBytes) {
        throw new IllegalArgumentException("File size (" + fileSize + ") < frameBytes (" + frameBytes + ")");
      }
      // Map only the frame range from offset 0
      MappedByteBuffer map = ch.map(FileChannel.MapMode.READ_ONLY, 0, frameBytes);

      // Warm-up: fault a little data (not timed)
      map.position(0);
      if (touch) { map.get(); } else { int len = Math.min(frameBytes, 64); byte[] tmp = new byte[len]; map.get(tmp); }
      map.position(0);

      // Measure end-to-end and copy-only
      long endToEndStartNs = System.nanoTime();
      map.position(0); // included in end-to-end

      long t0 = System.nanoTime();
      if (touch) {
        // Touch one byte per OS page to simulate zero-copy access
        final int page = 4096;
        int remaining = frameBytes;
        int pos = 0;
        byte acc = 0;
        while (remaining > 0) {
          acc ^= map.get(pos);
          int step = Math.min(page, remaining);
          pos += step;
          remaining -= step;
        }
        if (acc == 7) System.out.print(""); // prevent dead-code elim
      } else {
        map.get(dst); // ONE bulk read
      }
      long t1 = System.nanoTime();
      long endToEndEndNs = System.nanoTime();

      double copyMs     = (t1 - t0) / 1e6;
      double end2endMs  = (endToEndEndNs - endToEndStartNs) / 1e6;

      double giB = 1024.0 * 1024.0 * 1024.0;
      double bytes = frameBytes;
      double copyGiBs    = (bytes / giB) / ((t1 - t0) / 1e9);
      double end2endGiBs = (bytes / giB) / ((endToEndEndNs - endToEndStartNs) / 1e9);

      System.out.printf("Assembly single-shot read -> %s (%,d bytes)%n", path.toAbsolutePath(), fileSize);
      System.out.printf("Config: %dx%d @ %d B/px, mode=%s%n", width, height, bpp, touch ? "touch" : "copy");
      System.out.printf("Totals: copy=%.3f ms | end-to-end=%.3f ms%n", copyMs, end2endMs);
      System.out.printf("Throughput: copy-only=%.2f GiB/s | end-to-end=%.2f GiB/s%n", copyGiBs, end2endGiBs);
    }
  }

  private static int parsePosInt(String s, String name) {
    int v = Integer.parseInt(s);
    if (v <= 0) throw new IllegalArgumentException(name + " must be > 0");
    return v;
  }
}