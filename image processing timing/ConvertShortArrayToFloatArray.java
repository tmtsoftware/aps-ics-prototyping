public class ConvertShortArrayToFloatArray {
    public static void main(String[] args) {
        int arraySize = 8120 * 8120;

        short[] shortArray = new short[arraySize];
        // Initialize your short array here...

        float[] floatArray = new float[arraySize];

        
        double max = 0.0;
        double totalTimeInMilliseconds = 0;
        
        for (int i=0; i<100; i++) {
            
            long startTime = System.nanoTime();

            convertShortArrayToFloatArray(shortArray, floatArray);

            long endTime = System.nanoTime();

            double deltaTime = (endTime - startTime) / 1_000_000;
            totalTimeInMilliseconds += deltaTime;
            max = (max < deltaTime) ? deltaTime : max;
         }

        System.out.println("Average Conversion time 100 trials: " + totalTimeInMilliseconds/100.0 + " milliseconds,   max time = " + max);
    }

    private static void convertShortArrayToFloatArray(short[] shortArray, float[] floatArray) {
        // Perform the conversion logic here...

        for (int i = 0; i < shortArray.length; i++) {
            floatArray[i] = shortArray[i];
        }

    }
}

