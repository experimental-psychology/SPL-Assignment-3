package bgu.spl.net.impl.stomp;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class SqlBridge {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 7778;

    public static synchronized String executeSql(String sql) {
        try (Socket socket = new Socket(HOST, PORT);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            out.write((sql + "\0").getBytes("UTF-8"));
            out.flush();

            StringBuilder response = new StringBuilder();
            int b;
            while ((b = in.read()) != -1 && b != 0) {
                response.append((char) b);
            }
            return response.toString();

        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }
}