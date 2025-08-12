/**
 * AssemblyReadBurst
 *
 * Burst-mode read timing probe for the Assembly.
 * Maps ONE burst container file (READ_ONLY) in <= 2 GiB windows and, for each
 * frame i, reads at byte offset (i * frameBytes) using a single bulk get(...).
 *
 * Reports:
 *   - total copy-only time (sum of get(...) per frame)
 *   - total end-to-end loop time (position + get + any remaps)
 *   - total remap time and remap count
 *   - throughput (GiB/s) for copy-only and end-to-end
 *
 * Usage:
 *   java AssemblyReadBurst <path> <width> <height> <frameCount> [bytesPerPixel=2] [mode=copy|touch]
 *     path          : absolute path to the burst container file written by HCD test
 *     width/height  : ROI in pixels (e.g., 1020 1020)
 *     frameCount    : number of frames in the container
 *     bytesPerPixel : 1 for 8-bit, 2 for 16-bit (default 2)
 *     mode          : 'copy' (bulk get into a heap array, default) or 'touch'
 *                     ('touch' walks pages without copying out, to estimate zero-copy access)
 *
 * Notes:
 *   - No fsync/force(); we measure RAM->RAM path (page cache).
 *   - Requires frameBytes <= 2e9 (fits in one window). For larger frames, raise WINDOW_BYTES strategy accordingly.
 */
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class AssemblyReadBurst {
  // Map windows strictly below 2 GiB (MappedByteBuffer is int-indexed)
  private static final long WINDOW_BYTES = 2_000_000_000L;

  public static void main(String[] args) throws IOException {
    if (args.length < 4 || args.length > 6) {
      System.err.println("Usage: java AssemblyReadBurst <path> <width> <height> <frameCount> [bytesPerPixel=2] [mode=copy|touch]");
      System.exit(2);
    }

    Path path     = Paths.get(args[0]);
    int width     = parsePosInt(args[1], "width");
    int height    = parsePosInt(args[2], "height");
    int frames    = parsePosInt(args[3], "frameCount");
    int bpp       = (args.length >= 5) ? parsePosInt(args[4], "bytesPerPixel") : 2;
    String mode   = (args.length == 6) ? args[5].toLowerCase() : "copy";
    boolean touch = mode.equals("touch");

    final int  frameBytes     = Math.multiplyExact(Math.multiplyExact(width, height), bpp);
    if ((long) frameBytes > WINDOW_BYTES) {
      throw new IllegalArgumentException("frameBytes (" + frameBytes + ") exceeds window size (" + WINDOW_BYTES + ")");
    }
    final long containerBytes = Math.multiplyExact((long) frameBytes, (long) frames);

    // Destination for copy mode (reused)
    final byte[] dst = touch ? null : new byte[frameBytes];

    try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {

      long windowStart = -1L;         // current mapping window start
      MappedByteBuffer map = null;
      int remaps = 0;

      // Warm-up: map first window and do a tiny untimed read to fault pages
      {
        long newStart = 0L;
        int  mapLen   = (int) Math.min(WINDOW_BYTES, containerBytes - newStart);
        map = ch.map(FileChannel.MapMode.READ_ONLY, newStart, mapLen);
        windowStart = newStart;
        map.position(0);
        if (touch) { map.get(); } else { int len = Math.min(frameBytes, 64); byte[] tmp = new byte[len]; map.get(tmp); }
        map.position(0);
      }

      long copyNsTotal     = 0L;
      long remapNsTotal    = 0L;
      long endToEndStartNs = System.nanoTime();

      for (int i = 0; i < frames; i++) {
        long offset = (long) i * frameBytes;
        long end    = offset + frameBytes;

        // Remap if the frame doesn't fit entirely within current [windowStart, windowStart + cap)
        boolean needsRemap = (map == null)
            || (offset < windowStart)
            || (end > windowStart + (long) map.capacity());

        if (needsRemap) {
          long newStart = Math.max(0L, end - WINDOW_BYTES);     // ensure whole frame fits
          int  mapLen   = (int) Math.min(WINDOW_BYTES, containerBytes - newStart);

          long tMap0 = System.nanoTime();
          map = ch.map(FileChannel.MapMode.READ_ONLY, newStart, mapLen);
          long tMap1 = System.nanoTime();

          windowStart = newStart;
          remaps++;
          remapNsTotal += (tMap1 - tMap0);
        }

        int srcPos = (int) (offset - windowStart);
        map.position(srcPos);

        long t0 = System.nanoTime();
        if (touch) {
          // Zero-copy: touch every page to simulate streaming access without copying out
          final int page = 4096;
          int remaining = frameBytes;
          int pos = srcPos;
          byte acc = 0;
          while (remaining > 0) {
            acc ^= map.get(pos);                  // read one byte from each page
            int step = Math.min(page, remaining);
            pos += step;
            remaining -= step;
          }
          // prevent JVM from optimizing away
          if (acc == 7) System.out.print("");
        } else {
          map.get(dst);                            // ONE bulk read into heap buffer
        }
        long t1 = System.nanoTime();

        copyNsTotal += (t1 - t0);
      }

      long endToEndEndNs = System.nanoTime();

      long   totalBytes      = (long) frames * frameBytes;
      double totalCopyMs     = copyNsTotal / 1e6;
      double totalEndToEndMs = (endToEndEndNs - endToEndStartNs) / 1e6;
      double totalRemapMs    = remapNsTotal / 1e6;

      double giB          = 1024.0 * 1024.0 * 1024.0;
      double copyGiBs     = (totalBytes / giB) / (copyNsTotal / 1e9);
      double endToEndGiBs = (totalBytes / giB) / ((endToEndEndNs - endToEndStartNs) / 1e9);

      System.out.printf("Assembly burst read -> %s%n", path.toAbsolutePath());
      System.out.printf("Config: %dx%d @ %d B/px, %d frames, %,d-byte container, mode=%s%n",
          width, height, bpp, frames, containerBytes, touch ? "touch" : "copy");
      System.out.printf("Totals: copy=%.3f ms | end-to-end=%.3f ms | remap=%.3f ms | remaps=%d%n",
          totalCopyMs, totalEndToEndMs, totalRemapMs, remaps);
      System.out.printf("Throughput: copy-only=%.2f GiB/s | end-to-end=%.2f GiB/s (%,d bytes total)%n",
          copyGiBs, endToEndGiBs, totalBytes);
    }
  }

  private static int parsePosInt(String s, String name) {
    int v = Integer.parseInt(s);
    if (v <= 0) throw new IllegalArgumentException(name + " must be > 0");
    return v;
  }
}