/**
 * Placeholder ContentProvider used by the stub before dynamic loading completes.
 *
 * Registered in the stub AndroidManifest so the package manager can resolve the
 * component at runtime. Once the real APK is loaded, this is replaced by the real provider.
 */
package pro.magisk.dummy;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

public class DummyProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selection_args, String sort_order) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selection_args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selection_args) {
        return 0;
    }
}
