public class ConvertIntArrayToFloatArray {
    private static final int BURN_IN_TRIALS = 3;
    public static void main(String[] args) {
        int width = 8120;
        int height = 8120;
        int iterations = 100;
        // Parse arguments
        if (args.length >= 2) {
            try {
                width = Integer.parseInt(args[0]);
                height = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid width/height, using default 8120 x 8120.");
                width = 8120;
                height = 8120;
            }
        }

        if (args.length >= 3) {
            try {
                iterations = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid iteration count, using default of 100.");
                iterations = 100;
            }
        }

        int arraySize = width * height;
        System.out.printf("Running conversion benchmark with arraySize=%d, iterations=%d%n", arraySize, iterations);

        int[] intArray = new int[arraySize];
        // Initialize your short array here...

        float[] floatArray = new float[arraySize];

        double totalTimeInMilliseconds = 0;
        double max = 0.0;
        
        for (int i=0; i< iterations + BURN_IN_TRIALS; i++) {
            long startTime = System.nanoTime();

            convertIntArrayToFloatArray(intArray, floatArray);

            long endTime = System.nanoTime();

            double deltaTime = (endTime - startTime) / 1_000_000;
            if (i >= BURN_IN_TRIALS) {
                totalTimeInMilliseconds += deltaTime;
                if (deltaTime > max) max = deltaTime;
            }
        }

        System.out.println("Average Conversion time 100 trials: " + totalTimeInMilliseconds/iterations + " milliseconds,   max time = " + max);
    }

    private static void convertIntArrayToFloatArray(int[] intArray, float[] floatArray) {
        // Perform the conversion logic here...

        for (int i = 0; i < intArray.length; i++) {
            floatArray[i] = intArray[i];
        }

    }
}

