public class ConvertIntArrayToFloatArray {
    public static void main(String[] args) {
        int arraySize = 8120 * 8120;

        int[] intArray = new int[arraySize];
        // Initialize your short array here...

        float[] floatArray = new float[arraySize];

        double totalTimeInMilliseconds = 0;
        double max = 0.0;
        
        for (int i=0; i<100; i++) {
            long startTime = System.nanoTime();

            convertIntArrayToFloatArray(intArray, floatArray);

            long endTime = System.nanoTime();

            double deltaTime = (endTime - startTime) / 1_000_000;
            totalTimeInMilliseconds += deltaTime;
            max = (max < deltaTime) ? deltaTime : max;
        }

        System.out.println("Average Conversion time 100 trials: " + totalTimeInMilliseconds/100.0 + " milliseconds,   max time = " + max);
    }

    private static void convertIntArrayToFloatArray(int[] intArray, float[] floatArray) {
        // Perform the conversion logic here...

        for (int i = 0; i < intArray.length; i++) {
            floatArray[i] = intArray[i];
        }

    }
}

