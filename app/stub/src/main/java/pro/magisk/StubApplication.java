/**
 * Stub Application that triggers dynamic loading of the real Magisk APK.
 *
 * In {@link #attachBaseContext}, it delegates to {@link DynLoad#loadAndInitializeApp}
 * which locates the real APK, creates a DynamicClassLoader, instantiates the real
 * Application, and wires up component name remapping.
 */
package pro.magisk;

import android.app.Application;
import android.content.Context;

public class StubApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        DynLoad.load_and_initialize_app(this);
    }
}
