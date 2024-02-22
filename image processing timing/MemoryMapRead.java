import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

class MemoryMapRead {
    private static final String SHORT_FILENAME = "/Users/smichaels/Desktop/JavaTest/imageFile";
    private static final String FLOAT_FILENAME = "/Users/smichaels/Desktop/JavaTest/floatImageFile";
    private static final long FLOAT_FILE_SIZE = 8192 * 8192 * 4;

    public static void main(String[] args) throws IOException {

	// file is assumed to be used over and over for each new frame

            RandomAccessFile shortFile = new RandomAccessFile(SHORT_FILENAME, "rw");
            RandomAccessFile floatFile = new RandomAccessFile(FLOAT_FILENAME, "rw");
            FileChannel shortChannel = shortFile.getChannel();
            FileChannel floatChannel = floatFile.getChannel();

            // Read file into mapped buffer
            MappedByteBuffer shortMbb =
                    shortChannel.map(FileChannel.MapMode.READ_WRITE,
                            0,          // position
                            shortChannel.size());
            MappedByteBuffer floatMbb =
                    floatChannel.map(FileChannel.MapMode.READ_WRITE,
                            0,          // position
                            FLOAT_FILE_SIZE);

        for (int j=0; j<10; j++) {
            long startTime = System.currentTimeMillis();

            int length = shortMbb.limit() / 2;

	    floatMbb.clear(); // start at beginning

            for (int i = 0; i < length; i++) {
                floatMbb.putFloat((float)shortMbb.getShort(i));
            }

            System.out.println("Total read and convert time: " + (System.currentTimeMillis() - startTime) + ", length = " + length);
        }

            shortChannel.close();
            floatChannel.close();
            shortFile.close();
            floatFile.close();

    }
}
