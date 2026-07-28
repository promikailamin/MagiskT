/**
 * Placeholder BroadcastReceiver used by the stub before dynamic loading completes.
 *
 * Registered in the stub AndroidManifest so the package manager can resolve the
 * component at runtime. Once the real APK is loaded, this is replaced by the real receiver.
 */
package pro.magisk.dummy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class DummyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {}
}
