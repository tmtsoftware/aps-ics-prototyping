import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

class MemoryMapRead2k {
    private static final String SHORT_FILENAME = "/Users/smichaels/Desktop/JavaTest/imageFile";
    private static final int SHORT_FILE_SIZE = 2048 * 2048 * 2;

    public static void main(String[] args) throws IOException {

	// file is assumed to be used over and over for each new frame

            RandomAccessFile shortFile = new RandomAccessFile(SHORT_FILENAME, "rw");
            
            FileChannel shortChannel = shortFile.getChannel();
            

            // Read file into mapped buffer
            MappedByteBuffer shortMbb =
                    shortChannel.map(FileChannel.MapMode.READ_WRITE,
                            0,          // position
                            SHORT_FILE_SIZE);
            

        for (int j=0; j<10; j++) {
            long startTime = System.currentTimeMillis();

            int length = SHORT_FILE_SIZE / 2;

	    //floatMbb.clear(); // start at beginning

            for (int i = 0; i < length; i++) {
                short temp = shortMbb.getShort(i);
            }

            System.out.println("Total read and convert time: " + (System.currentTimeMillis() - startTime) + ", length = " + length);
        }

            shortChannel.close();
        
            shortFile.close();
            

    }
}
