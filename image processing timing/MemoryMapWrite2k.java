import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;

class MemoryMapWrite2k {
    private static final String SHORT_FILENAME = "/Users/smichaels/Desktop/JavaTest/imageFile";

    private static final long SHORT_FILE_SIZE = 2048 * 2048 * 2;

    public static void main(String[] args) throws IOException {

	// file is assumed to be used over and over for each new frame

            RandomAccessFile shortFile = new RandomAccessFile(SHORT_FILENAME, "rw");

            FileChannel shortChannel = shortFile.getChannel();


            // Read file into mapped buffer
            MappedByteBuffer shortMbb =
                    shortChannel.map(FileChannel.MapMode.READ_WRITE,
                            0,          // position
                            SHORT_FILE_SIZE);
            
        
        long[] delta = new long[1000];
        
        for (int j=0; j<1000; j++) {
            long startTime = System.currentTimeMillis();

            int length = shortMbb.limit() / 2;

	        shortMbb.clear(); // start at beginning

            for (int i = 0; i < length; i++) {
                shortMbb.putShort((short)(i % 32767));
            }

            delta[j] = System.currentTimeMillis() - startTime;
            //System.out.println("Write time: " + delta[j]);
            
        }
        
        float total = 0;
        long max = 0;
        
        List<Integer> overages = new ArrayList<Integer>();
        for (int i=0; i<1000; i++) {
            total = total + delta[i];
            if (delta[i] > max) max = delta[i];
            if (delta[i] > 10) overages.add(i);
        }
        
        System.out.println("Write time average: " + (total/1000.0) + " max = " + max);
        
        System.out.println("trials over 10 ms : " + overages);


            shortChannel.close();
            
            shortFile.close();
           

    }
}