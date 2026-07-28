/**
 * Callback interface for receiving the result of an asynchronous HTTP request.
 *
 * @param <T> the response type (e.g. String, File, InputStream, JSONObject)
 */
package pro.magisk.net;

public interface ResponseListener<T> {
    void onResponse(T response);
}
