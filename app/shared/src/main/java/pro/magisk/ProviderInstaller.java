/**
 * Installs the Google Play Services security provider to add TLS 1.2+ support
 * on older Android versions.
 *
 * If Google Play Services is installed as a system app, this reflectively calls
 * its ProviderInstallerImpl to insert GmsCore's SSL provider into the Java
 * security framework, enabling modern TLS on devices that lack it natively.
 */
package pro.magisk;

import android.content.Context;
import android.content.pm.ApplicationInfo;

public class ProviderInstaller {

    /** Package name of Google Play Services. */
    private static final String GMS_PACKAGE_NAME = "com.google.android.gms";

    /**
     * Attempts to install GmsCore's security provider for TLS support.
     * Only works if Google Play Services is installed as a system app.
     * Failures are silently ignored.
     */
    public static void install(Context context) {
        try {
            ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(GMS_PACKAGE_NAME, 0);
            if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                return;
            }

            Context gms = context.createPackageContext(GMS_PACKAGE_NAME,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            gms.getClassLoader()
                    .loadClass("com.google.android.gms.common.security.ProviderInstallerImpl")
                    .getMethod("insertProvider", Context.class)
                    .invoke(null, gms);
        } catch (Exception ignored) {
        }
    }
}
