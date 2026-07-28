/**
 * Placeholder Service used by the stub before dynamic loading completes.
 *
 * Registered in the stub AndroidManifest so the package manager can resolve the
 * component at runtime. Once the real APK is loaded, this is replaced by the real service.
 */
package pro.magisk.dummy;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class DummyService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
