/**
 * Callback interface for handling HTTP request errors.
 *
 * Implementations receive both the (possibly null) HttpURLConnection and
 * the exception that occurred, allowing for detailed error reporting.
 */
package pro.magisk.net;

import java.net.HttpURLConnection;

public interface ErrorHandler {
    void onError(HttpURLConnection conn, Exception e);
}
