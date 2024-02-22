import java.io.*;

class DiskWrite {
    public static void main(String[] args) {

        //int BUFFER_SIZE = 32768;
        int BUFFER_SIZE = 131072;
        int IMAGE_SIZE = 8120 * 8120 * 2;
        File testFile = new File("/Users/smichaels/Desktop/JavaTest/imageFile");
        long delta = 0;
        long total = 0;
        long max = 0;
        long min = 10000;


        for (int i=0; i<100; i++) {
            long startTime = System.currentTimeMillis();

            try (
                    OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(testFile), BUFFER_SIZE);
            ) {
                byte[] buffer = new byte[IMAGE_SIZE];
                int bytesRead = -1;
                outputStream.write(buffer);

            } catch (IOException ex) {
                ex.printStackTrace();
            }
            long endTime = System.currentTimeMillis();
            delta = endTime - startTime;
            total = total + delta;
            if (max < delta) max = delta;
            if (min > delta) min = delta;
            System.out.println(endTime - startTime);
        }
        System.out.println(total/100L + " " + max + " " + min);


        for (int i=0; i<100; i++) {
            int reads = 0;
        long startTime = System.currentTimeMillis();
        try (
                InputStream inputStream = new BufferedInputStream(new FileInputStream(testFile), BUFFER_SIZE);

        ) {
            byte[] buffer = new byte[IMAGE_SIZE];
            int bytesRead = -1;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        long endTime = System.currentTimeMillis();
        delta = endTime - startTime;
        total = total + delta;
        if (max < delta) max = delta;
        if (min > delta) min = delta;
        System.out.println((endTime - startTime) + " " + reads);

    }
        System.out.println(total/100L + " " + max + " " + min);



 
    }


}
