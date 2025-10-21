import mmcorej.CMMCore;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;

public class MMAcquireAndWriteBenchmarkOld {

    private static byte[] convertToBytes(Object img) {
        if (img instanceof short[]) {
            short[] s = (short[]) img;
            ByteBuffer buffer = ByteBuffer.allocate(s.length * 2);
            for (short val : s) buffer.putShort(val);
            return buffer.array();
        } else if (img instanceof byte[]) {
            return (byte[]) img;
        } else if (img instanceof int[]) {
            int[] i = (int[]) img;
            ByteBuffer buffer = ByteBuffer.allocate(i.length * 4);
            for (int val : i) buffer.putInt(val);
            return buffer.array();
        }
        throw new IllegalArgumentException("Unsupported image type: " + img.getClass());
    }

    public static void main(String[] args) {
        try {
            // --- Initialize MMCore ---
            CMMCore core = new CMMCore();
            core.loadSystemConfiguration("config/MMConfig_Demo.cfg");

            // --- Memory-mapped file setup ---
            String mmapFile = "mm_benchmark.dat";
            int numFrames = 10;  // number of test images
            int bytesPerPixel = (int) core.getBytesPerPixel();
            int width = (int) core.getImageWidth();
            int height = (int) core.getImageHeight();
            int frameSize = width * height * bytesPerPixel;

            System.out.println("DemoCamera loaded. Image size: " + width + " x " + height);

            RandomAccessFile raf = new RandomAccessFile(mmapFile, "rw");
            raf.setLength((long) frameSize * numFrames);
            FileChannel channel = raf.getChannel();
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, raf.length());

            // --- Benchmark loop ---
            for (int i = 0; i < numFrames; i++) {
                long t0 = System.nanoTime();
                core.snapImage();
                long t1 = System.nanoTime();

                Object img = core.getImage();
                long t2 = System.nanoTime();

                byte[] data = convertToBytes(img);
                long t3 = System.nanoTime();

                // write into correct frame slot in mmap file
                buffer.position(i * frameSize);
                buffer.put(data);
                long t4 = System.nanoTime();

                System.out.printf("Frame %d timings (ms): Snap=%.3f, Get=%.3f, Convert=%.3f, Write=%.3f%n",
                        i,
                        (t1 - t0) / 1e6,
                        (t2 - t1) / 1e6,
                        (t3 - t2) / 1e6,
                        (t4 - t3) / 1e6);
            }

            // cleanup
            channel.close();
            raf.close();
            System.out.println("Benchmark finished. Data written to " + mmapFile);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
