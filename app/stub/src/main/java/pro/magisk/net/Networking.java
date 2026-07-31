/**
 * Simple HTTP networking utility based on {@link java.net.HttpURLConnection}.
 *
 * Provides convenience methods for GET requests and network connectivity checks.
 * Uses a background thread pool for async requests and posts results back to the
 * main thread via a Handler.
 */
package pro.magisk.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public class Networking {

    private static final int READ_TIMEOUT = 15000;
    private static final int CONNECT_TIMEOUT = 15000;
    /** Handler for posting callbacks to the main thread. */
    static Handler main_handler = new Handler(Looper.getMainLooper());

    /** Creates a Request for the given URL and HTTP method. Returns a BadRequest on connection failure. */
    private static Request request(String url, String method) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            return new Request(conn);
        } catch (IOException e) {
            return new BadRequest(e);
        }
    }

    /** Convenience: creates a GET request for the given URL. */
    public static Request get(String url) {
        return request(url, "GET");
    }

    /** Returns true if the device currently has an active network connection. */
    public static boolean check_network_status(Context context) {
        ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network_info = manager.getActiveNetworkInfo();
        return network_info != null && network_info.isConnected();
    }
}
