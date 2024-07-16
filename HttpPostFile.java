import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class HttpPostFile {

    private static final String SHORT_FILENAME = "/Users/smichaels/Desktop/JavaTest/imageFile";
    
    public static void main(String[] args) {
        String targetURL = "https://reqbin.com/echo/post/json";
        
        String filePath = SHORT_FILENAME;
        String boundary = "===" + System.currentTimeMillis() + "===";

        File uploadFile = new File(filePath);

        try {
            // Set up the connection
            URL url = new URL(targetURL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            // Create the form data
            StringBuilder sb = new StringBuilder();
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(uploadFile.getName()).append("\"\r\n");
            sb.append("Content-Type: ").append("text/plain").append("\r\n");
            sb.append("\r\n");

            byte[] headerBytes = sb.toString().getBytes("UTF-8");
            byte[] footerBytes = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");

            // Upload the file
            OutputStream outputStream = connection.getOutputStream();
            outputStream.write(headerBytes);

            FileInputStream fileInputStream = new FileInputStream(uploadFile);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            fileInputStream.close();

            outputStream.write(footerBytes);
            outputStream.flush();
            outputStream.close();

            // Get the response
            int responseCode = connection.getResponseCode();
            System.out.println("Response Code: " + responseCode);

            InputStream responseStream = connection.getInputStream();
            byte[] responseBytes = responseStream.readAllBytes();
            responseStream.close();
            System.out.println("Response: " + new String(responseBytes, "UTF-8"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

