/**
 * Stub root service that loads the real magiskd root service from the dynamically loaded APK.
 *
 * This ContextWrapper acts as a proxy: in {@link #attachBaseContext}, it loads the real APK,
 * instantiates the real Application (to populate StubApk.Data with the real RootService class),
 * then creates and attaches the real root service. This allows magiskd to run from the hidden APK.
 */
package pro.magisk;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Constructor;

public class StubRootService extends ContextWrapper {

    public StubRootService() {
        super(null);
    }

    @Override
    protected void attachBaseContext(Context base) {
        ClassLoader loader = DynLoad.loadApk(base);
        if (loader == null)
            return;

        try {
            // Instantiate the real Application so it populates StubApk.Data with the
            // real RootService class reference
            var data = DynLoad.createApkData();
            File apk = StubApk.current(base);
            PackageManager pm = base.getPackageManager();
            PackageInfo pkgInfo = pm.getPackageArchiveInfo(apk.getPath(), 0);
            loader.loadClass(pkgInfo.applicationInfo.className)
                    .getConstructor(Object.class)
                    .newInstance(data.getObject());

            // Create the real RootService instance and attach the stub's context
            Constructor<?> ctor = data.getRootService().getConstructor(Object.class);
            ctor.setAccessible(true);
            Object service = ctor.newInstance(this);
            DynLoad.attachContext(service, base);
        } catch (Exception e) {
            Log.e(StubRootService.class.getSimpleName(), "", e);
        }
    }
}
