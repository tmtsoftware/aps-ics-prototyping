

class GuidingPreprocessingSimulator {

    private static final int BURN_IN_TRIALS = 3;

    public static void main(String[] args) {
        int width = 2048;
        int height = 2048;
        int iterations = 10;

        // Parse arguments
        if (args.length >= 2) {
            try {
                width = Integer.parseInt(args[0]);
                height = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid width/height, using default 2048 x 2048.");
                width = 2048;
                height = 2048;
            }
        }

        if (args.length >= 3) {
            try {
                iterations = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid iteration count, using default of 10.");
                iterations = 10;
            }
        }

        int fileSize = width * height;
        System.out.printf("Running preprocessing simulation with fileSize=%d, iterations=%d%n", fileSize, iterations);

	// file is assumed to be used over and over for each new frame

            
        short[] input = new short[fileSize];
        short[] corrected = new short[fileSize];
        short[] back = new short[fileSize];
        float[] flat = new float[fileSize];
        short[] flatCorrected = new short[fileSize];
        
        // set up data
        for (int i=0; i<fileSize; i++) {
           input[i] = (short)i;
           flat[i] = (i%14) /14;
        }

        double totalMs = 0;
        double maxMs = 0;
        
        for (int iter = 0; iter < iterations + BURN_IN_TRIALS; iter++) {
            long startTime = System.nanoTime();

            // Simulate bad pixel check (read each pixel only)
            for (int i = 0; i < fileSize; i++) {
                short temp = input[i];  // read only
            }

            int sum = 0;
            for (int i = 0; i < fileSize; i++) {
                // background averaging read
                sum += corrected[i];
            }

            float avg = (float) sum / fileSize;

            for (int i = 0; i < fileSize; i++) {
                // background averaging subtraction
                back[i] = (short) (corrected[i] - avg);
            }

            for (int i = 0; i < fileSize; i++) {
                // flat field correction
                flatCorrected[i] = (short) (back[i] * flat[i]);
            }

            long endTime = System.nanoTime();
            double deltaMs = (endTime - startTime) / 1_000_000.0;

            if (iter >= BURN_IN_TRIALS) {
                totalMs += deltaMs;
                if (deltaMs > maxMs) maxMs = deltaMs;
            }
        }

        System.out.printf("Average time over %d iterations: %.3f ms, Max time: %.3f ms%n",
                          iterations, totalMs / iterations, maxMs);
    }
}
