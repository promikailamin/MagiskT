/**
 * Wrapper around {@link java.net.HttpURLConnection} providing a fluent API for
 * synchronous and asynchronous HTTP requests.
 *
 * Supports downloading responses as File, String, byte[], InputStream, JSONObject,
 * or JSONArray. Async methods use {@link android.os.AsyncTask#THREAD_POOL_EXECUTOR}
 * by default and deliver results on the main thread (or a custom Executor).
 */
package pro.magisk.net;

import android.os.AsyncTask;

import pro.magisk.utils.APKInstall;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Scanner;
import java.util.concurrent.Executor;

public class Request {
    private final HttpURLConnection conn;
    /** Custom executor for delivering async results (defaults to main thread if null). */
    private Executor executor = null;
    /** HTTP response code from the last executed request. */
    private int code = -1;

    /** Optional error handler invoked on request failures. */
    ErrorHandler err = null;

    /** Functional interface for synchronous request execution. */
    private interface Requestor<T> {
        T request() throws Exception;
    }

    /**
     * Holds the result of a synchronous request execution.
     *
     * @param <T> the response type
     */
    public class Result<T> {
        T result;

        public T getResult() {
            return result;
        }

        public int getCode() {
            return code;
        }

        public boolean isSuccess() {
            return code >= 200 && code <= 299;
        }

        public HttpURLConnection getConnection() {
            return conn;
        }
    }

    Request(HttpURLConnection c) {
        conn = c;
    }

    /** Sets a request header property. */
    public Request addHeaders(String key, String value) {
        conn.setRequestProperty(key, value);
        return this;
    }

    /** Registers a handler for request errors. */
    public Request setErrorHandler(ErrorHandler handler) {
        err = handler;
        return this;
    }

    /**
     * Sets an executor for delivering async callbacks.
     * If not set, results are posted on the main thread.
     */
    public Request setExecutor(Executor e) {
        executor = e;
        return this;
    }

    /** Opens the connection and returns a result with the response code. */
    public Result<Void> connect() {
        try {
            connect0();
        } catch (IOException e) {
            if (err != null)
                err.onError(conn, e);
        }
        return new Result<>();
    }

    /** Synchronously executes the request and returns the response as an InputStream. */
    public Result<InputStream> execForInputStream() {
        return exec(this::getInputStream);
    }

    /** Asynchronously downloads the response as an InputStream. */
    public void getAsInputStream(ResponseListener<InputStream> rs) {
        submit(this::getInputStream, rs);
    }

    /** Asynchronously downloads the response to a file. */
    public void getAsFile(File out, ResponseListener<File> rs) {
        submit(() -> dlFile(out), rs);
    }

    /** Synchronously downloads the response to a file. */
    public void execForFile(File out) {
        exec(() -> dlFile(out));
    }

    /** Asynchronously downloads the response as a byte array. */
    public void getAsBytes(ResponseListener<byte[]> rs) {
        submit(this::dlBytes, rs);
    }

    /** Synchronously downloads the response as a byte array. */
    public Result<byte[]> execForBytes() {
        return exec(this::dlBytes);
    }

    /** Asynchronously downloads the response body as a String (UTF-8). */
    public void getAsString(ResponseListener<String> rs) {
        submit(this::dlString, rs);
    }

    /** Synchronously downloads the response body as a String. */
    public Result<String> execForString() {
        return exec(this::dlString);
    }

    /** Asynchronously downloads and parses the response as a JSONObject. */
    public void getAsJSONObject(ResponseListener<JSONObject> rs) {
        submit(this::dlJSONObject, rs);
    }

    /** Synchronously downloads and parses the response as a JSONObject. */
    public Result<JSONObject> execForJSONObject() {
        return exec(this::dlJSONObject);
    }

    /** Asynchronously downloads and parses the response as a JSONArray. */
    public void getAsJSONArray(ResponseListener<JSONArray> rs) {
        submit(this::dlJSONArray, rs);
    }

    /** Synchronously downloads and parses the response as a JSONArray. */
    public Result<JSONArray> execForJSONArray() {
        return exec(this::dlJSONArray);
    }

    /** Establishes the connection and reads the response code. */
    private void connect0() throws IOException {
        conn.connect();
        code = conn.getResponseCode();
    }

    /** Executes a request synchronously, wrapping any exception via the error handler. */
    private <T> Result<T> exec(Requestor<T> req) {
        Result<T> res = new Result<>();
        try {
            res.result = req.request();
        } catch (Exception e) {
            if (err != null)
                err.onError(conn, e);
        }
        return res;
    }

    /** Submits a request to the thread pool and posts the result to the callback. */
    private <T> void submit(Requestor<T> req, ResponseListener<T> rs) {
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            try {
                T t = req.request();
                Runnable cb = () -> rs.onResponse(t);
                if (executor == null)
                    Networking.mainHandler.post(cb);
                else
                    executor.execute(cb);
            } catch (Exception e) {
                if (err != null)
                    err.onError(conn, e);
            }
        });
    }

    /** Opens the connection and returns a BufferedInputStream wrapping the response stream. */
    private BufferedInputStream getInputStream() throws IOException {
        connect0();
        InputStream in = new FilterInputStream(conn.getInputStream()) {
            @Override
            public void close() throws IOException {
                super.close();
                conn.disconnect();
            }
        };
        return new BufferedInputStream(in);
    }

    /** Reads the full response body as a UTF-8 String. */
    private String dlString() throws IOException {
        try (Scanner s = new Scanner(getInputStream(), "UTF-8")) {
            s.useDelimiter("\\A");
            return s.next();
        }
    }

    /** Downloads and parses the response as a JSONObject. */
    private JSONObject dlJSONObject() throws IOException, JSONException {
        return new JSONObject(dlString());
    }

    /** Downloads and parses the response as a JSONArray. */
    private JSONArray dlJSONArray() throws IOException, JSONException {
        return new JSONArray(dlString());
    }

    /** Downloads the response body to the specified file. */
    private File dlFile(File f) throws IOException {
        try (InputStream in = getInputStream();
             OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
            APKInstall.transfer(in, out);
        }
        return f;
    }

    /** Downloads the response body as a byte array. */
    private byte[] dlBytes() throws IOException {
        int len = conn.getContentLength();
        len = len > 0 ? len : 32;
        ByteArrayOutputStream out = new ByteArrayOutputStream(len);
        try (InputStream in = getInputStream()) {
            APKInstall.transfer(in, out);
        }
        return out.toByteArray();
    }
}
