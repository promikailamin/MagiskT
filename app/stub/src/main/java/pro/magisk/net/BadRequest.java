/**
 * A no-op Request subclass returned when the initial connection setup fails.
 *
 * All methods immediately invoke the error handler with the stored IOException,
 * preventing any further network operations on a connection that was never established.
 */
package pro.magisk.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

class BadRequest extends Request {

    /** The exception that occurred during connection setup. */
    private final IOException ex;

    BadRequest(IOException e) { super(null); ex = e; }

    @Override
    public Request addHeaders(String key, String value) { return this; }

    @Override
    public Result<InputStream> execForInputStream() { fail(); return new Result<>(); }

    @Override
    public void getAsFile(File out, ResponseListener<File> rs) { fail(); }

    @Override
    public void execForFile(File out) { fail(); }

    @Override
    public void getAsString(ResponseListener<String> rs) { fail(); }

    @Override
    public Result<String> execForString() { fail(); return new Result<>(); }

    @Override
    public void getAsJSONObject(ResponseListener<JSONObject> rs) { fail(); }

    @Override
    public Result<JSONObject> execForJSONObject() { fail(); return new Result<>(); }

    @Override
    public void getAsJSONArray(ResponseListener<JSONArray> rs) { fail(); }

    @Override
    public Result<JSONArray> execForJSONArray() { fail(); return new Result<>(); }

    /** Invokes the error handler with the stored exception. */
    private void fail() {
        if (err != null)
            err.onError(null, ex);
    }
}
