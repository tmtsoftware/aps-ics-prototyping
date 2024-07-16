

class GuidingPreprocessingSimulator {

    private static final int SHORT_FILE_SIZE = 2048 * 2048;

    public static void main(String[] args) {

	// file is assumed to be used over and over for each new frame

            
        short[] input = new short[SHORT_FILE_SIZE];
        short[] corrected = new short[SHORT_FILE_SIZE];
        short[] back = new short[SHORT_FILE_SIZE];
        float[] flat = new float[SHORT_FILE_SIZE];
        short[] flatCorrected = new short[SHORT_FILE_SIZE];
        
        // set up data
          for (int i=0; i<SHORT_FILE_SIZE; i++) {
        
           // bad pixel determination by reads
           input[i] = (short)i;
        }
         for (int i=0; i<SHORT_FILE_SIZE; i++) {
        
           // bad pixel determination by reads
           flat[i] = (i%14) /14;
        }
      
        
        long startTime = System.nanoTime();
        
        // bad pixel determination by reads
        for (int i=0; i<SHORT_FILE_SIZE; i++) {
        
           // bad pixel determination by reads
            
           // median correction based on surrounding pixels
           float mean = (input[1] + input[2]  + input[3] + input[4] + input[5] + input[6]  + input[7] + input[8])/8;
            
           input[i] = (short)mean;
        }
        int sum = 0;
        for (int i=0; i<SHORT_FILE_SIZE; i++) {
        
           // background averaging read
            sum += corrected[i];
        }
        float avg = sum/SHORT_FILE_SIZE;
        for (int i=0; i<SHORT_FILE_SIZE; i++) {
        
           // background averaging subtraction
            back[i] = (short)(corrected[i] - avg);
        }
        for (int i=0; i<SHORT_FILE_SIZE; i++) {
        
           // background averaging subtraction
            flatCorrected[i] = (short)(corrected[i] * flat[i]);
        }
        
        
        
        System.out.println("Total time (ns) " + (System.nanoTime() - startTime));
       

    }
}
