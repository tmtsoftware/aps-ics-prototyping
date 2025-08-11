import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class MemoryMapWriteBurst {
  // Defaults: 1020x1020 @ 16-bit, 1000 frames, /tmp/aps
  static final int   DEFAULT_WIDTH   = 1020;
  static final int   DEFAULT_HEIGHT  = 1020;
  static final int   DEFAULT_BPP     = 2;                 // bytes per pixel (16-bit)
  static final int   DEFAULT_FRAMES  = 1000;

  // Map windows strictly below 2 GiB (MappedByteBuffer is int-indexed)
  static final long  WINDOW_BYTES    = 2_000_000_000L;

  public static void main(String[] args) throws IOException {
    // Usage: java MemoryMapWriteBurst [width height frameCount [outDir] [bytesPerPixel]]
    if (args.length == 1 || args.length == 2 || args.length > 5) {
      System.err.println("Usage: java MemoryMapWriteBurst [width height frameCount [outDir] [bytesPerPixel]]");
      System.exit(2);
    }

    int  width   = DEFAULT_WIDTH;
    int  height  = DEFAULT_HEIGHT;
    int  frames  = DEFAULT_FRAMES;
    int  bpp     = DEFAULT_BPP;
    Path outDir  = Paths.get("/tmp", "aps");

    if (args.length >= 3) {
      width  = parsePosInt(args[0], "width");
      height = parsePosInt(args[1], "height");
      frames = parsePosInt(args[2], "frameCount");
    }
    if (args.length >= 4) outDir = Paths.get(args[3]);
    if (args.length == 5) bpp    = parsePosInt(args[4], "bytesPerPixel");

    Files.createDirectories(outDir);

    final int  frameBytes      = Math.multiplyExact(Math.multiplyExact(width, height), bpp);
    if ((long) frameBytes > WINDOW_BYTES) {
      throw new IllegalArgumentException("frameBytes (" + frameBytes + ") exceeds window size (" + WINDOW_BYTES + ")");
    }
    final long containerBytes  = Math.multiplyExact((long) frameBytes, (long) frames);
    final Path path            = outDir.resolve(String.format(
        "burst_container_%dx%d_%dbpp_%dframes.bin", width, height, bpp * 8, frames));

    // Simulated µManager image bytes (reused each frame; contents irrelevant to timing)
    final byte[] src = new byte[frameBytes];

    try (FileChannel ch = FileChannel.open(
        path,
        StandardOpenOption.CREATE, StandardOpenOption.READ,
        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

      ch.truncate(containerBytes);

      long windowStart = -1L;         // byte offset of current mapping window
      MappedByteBuffer map = null;
      int remaps = 0;

      // Warm-up: map first window and do one untimed copy to settle JIT/OS
      {
        long newStart = 0L;
        int  mapLen   = (int) Math.min(WINDOW_BYTES, containerBytes - newStart);
        map = ch.map(FileChannel.MapMode.READ_WRITE, newStart, mapLen);
        windowStart = newStart;
        map.position(0);
        map.put(src);
        map.position(0);
      }

      long copyNsTotal     = 0L;          // sum of per-frame copy times
      long remapNsTotal    = 0L;          // sum of remap times (mapping only)
      long endToEndStartNs = System.nanoTime();

      for (int i = 0; i < frames; i++) {
        long offset = (long) i * frameBytes;
        long end    = offset + frameBytes;

        // Remap if the frame doesn't fit entirely within current [windowStart, windowStart + cap)
        boolean needsRemap = (map == null)
            || (offset < windowStart)
            || (end > windowStart + (long) map.capacity());

        if (needsRemap) {
          long newStart = Math.max(0L, end - WINDOW_BYTES);     // window that contains the entire frame
          int  mapLen   = (int) Math.min(WINDOW_BYTES, containerBytes - newStart);

          long tMap0 = System.nanoTime();
          map = ch.map(FileChannel.MapMode.READ_WRITE, newStart, mapLen);
          long tMap1 = System.nanoTime();

          windowStart = newStart;
          remaps++;
          remapNsTotal += (tMap1 - tMap0);
        }

        int dstPos = (int) (offset - windowStart);   // guaranteed >= 0 and <= capacity - frameBytes
        map.position(dstPos);

        long t0 = System.nanoTime();
        map.put(src);                                // ONE bulk copy per frame
        long t1 = System.nanoTime();

        copyNsTotal += (t1 - t0);
      }

      long endToEndEndNs = System.nanoTime();

      long   totalBytes       = (long) frames * frameBytes;
      double totalCopyMs      = copyNsTotal / 1e6;
      double totalEndToEndMs  = (endToEndEndNs - endToEndStartNs) / 1e6;
      double totalRemapMs     = remapNsTotal / 1e6;

      double giB              = 1024.0 * 1024.0 * 1024.0;
      double copyGiBs         = (totalBytes / giB) / (copyNsTotal / 1e9);                  // copy-only throughput
      double endToEndGiBs     = (totalBytes / giB) / ((endToEndEndNs - endToEndStartNs) / 1e9); // loop throughput

      System.out.printf("Burst bulk-copy -> %dx%d @ %d B/px, %d frames, container %,d bytes%n",
          width, height, bpp, frames, containerBytes);
      System.out.printf("Path: %s%n", path.toAbsolutePath());
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