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
    public Request add_headers(String key, String value) { return this; }

    @Override
    public Result<InputStream> exec_for_input_stream() { fail(); return new Result<>(); }

    @Override
    public void get_as_file(File out, ResponseListener<File> rs) { fail(); }

    @Override
    public void exec_for_file(File out) { fail(); }

    @Override
    public void get_as_string(ResponseListener<String> rs) { fail(); }

    @Override
    public Result<String> exec_for_string() { fail(); return new Result<>(); }

    @Override
    public void get_as_j_s_o_n_object(ResponseListener<JSONObject> rs) { fail(); }

    @Override
    public Result<JSONObject> exec_for_j_s_o_n_object() { fail(); return new Result<>(); }

    @Override
    public void get_as_j_s_o_n_array(ResponseListener<JSONArray> rs) { fail(); }

    @Override
    public Result<JSONArray> exec_for_j_s_o_n_array() { fail(); return new Result<>(); }

    /** Invokes the error handler with the stored exception. */
    private void fail() {
        if (err != null)
            err.onError(null, ex);
    }
}
