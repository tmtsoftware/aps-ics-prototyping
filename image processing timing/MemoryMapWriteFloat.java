import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

class MemoryMapWriteFloat {
    private static final String FLOAT_FILENAME = "/Users/smichaels/Desktop/JavaTest/floatImageFile";

    private static final long FLOAT_FILE_SIZE = 8192 * 8192 * 4;

    public static void main(String[] args) throws IOException {

	// file is assumed to be used over and over for each new frame

            RandomAccessFile floatFile = new RandomAccessFile(FLOAT_FILENAME, "rw");

            FileChannel floatChannel = floatFile.getChannel();


            // Read file into mapped buffer
            MappedByteBuffer floatMbb =
                    floatChannel.map(FileChannel.MapMode.READ_WRITE,
                            0,          // position
                            FLOAT_FILE_SIZE);
            

        for (int j=0; j<10; j++) {
            long startTime = System.currentTimeMillis();

            int length = floatMbb.limit() / 4;

	        floatMbb.clear(); // start at beginning

            for (int i = 0; i < length; i++) {
                floatMbb.putFloat((float)(i));
            }

            System.out.println("Total writetime: " + (System.currentTimeMillis() - startTime) + ", length = " + length);
        }

            floatChannel.close();
            
            floatFile.close();
           

    }
}
