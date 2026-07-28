/**
 * Manages the dynamically loaded Magisk APK file and provides helpers for
 * resource injection and process restart.
 *
 * The real APK is stored as "current.apk" in a device-protected data directory
 * (on N+) or the regular data directory. An "update.apk" file can be staged
 * and is renamed to "current.apk" on next launch.
 *
 * Also contains the {@link Data} inner class used to pass data between the stub
 * and the loaded application via a hidden Object[] constructor parameter.
 */
package pro.magisk;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;

public class StubApk {
    /** Cached dynamic files directory. */
    private static File dynDir;
    /** Cached AssetManager.addAssetPath reflection Method. */
    private static Method addAssetPath;

    /**
     * Returns (and creates if needed) the directory for dynamic APK storage.
     * Uses device-protected storage on N+ for direct boot awareness.
     */
    private static File getDynDir(ApplicationInfo info) {
        if (dynDir == null) {
            final String dataDir;
            if (SDK_INT >= Build.VERSION_CODES.N) {
                dataDir = info.deviceProtectedDataDir;
            } else {
                dataDir = info.dataDir;
            }
            dynDir = new File(dataDir, "dyn");
            dynDir.mkdirs();
        }
        return dynDir;
    }

    /** Returns the current APK file path. */
    public static File current(Context c) {
        return new File(getDynDir(c.getApplicationInfo()), "current.apk");
    }

    /** Returns the current APK file path. */
    public static File current(ApplicationInfo info) {
        return new File(getDynDir(info), "current.apk");
    }

    /** Returns the update APK file path (staged for next launch). */
    public static File update(Context c) {
        return new File(getDynDir(c.getApplicationInfo()), "update.apk");
    }

    /** Returns the update APK file path (staged for next launch). */
    public static File update(ApplicationInfo info) {
        return new File(getDynDir(info), "update.apk");
    }

    /** Creates a ResourcesLoader for the given path (directory or APK file), API 30+. */
    @TargetApi(Build.VERSION_CODES.R)
    private static ResourcesLoader getResourcesLoader(File path) throws IOException {
        var loader = new ResourcesLoader();
        ResourcesProvider provider;
        if (path.isDirectory()) {
            provider = ResourcesProvider.loadFromDirectory(path.getPath(), null);
        } else {
            var fd = ParcelFileDescriptor.open(path, MODE_READ_ONLY);
            provider = ResourcesProvider.loadFromApk(fd);
        }
        loader.addProvider(provider);
        return loader;
    }

    /**
     * Injects additional resources (from a file path) into the given Resources object.
     * Uses Resources.addLoaders on R+ or AssetManager.addAssetPath via reflection on older.
     */
    public static void addAssetPath(Resources res, String path) {
        if (SDK_INT >= Build.VERSION_CODES.R) {
            try {
                res.addLoaders(getResourcesLoader(new File(path)));
            } catch (IOException ignored) {}
        } else {
            AssetManager asset = res.getAssets();
            try {
                if (addAssetPath == null)
                    addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
                addAssetPath.invoke(asset, path);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Restarts the current process by launching the launcher intent and calling
     * Runtime.exit. The activity finishes its affinity first so it does not
     * reappear in the back stack.
     */
    public static void restartProcess(Activity activity) {
        Intent intent = activity.getPackageManager()
                .getLaunchIntentForPackage(activity.getPackageName());
        activity.finishAffinity();
        activity.startActivity(intent);
        Runtime.getRuntime().exit(0);
    }

    /**
     * Container for data passed between the stub APK and the dynamically loaded application.
     *
     * Uses an Object array internally so it can be passed through a hidden constructor
     * parameter (the real Application is expected to have a constructor accepting Object).
     *
     * Fields:
     * <ul>
     *   <li>STUB_VERSION — version of the stub</li>
     *   <li>CLASS_COMPONENT_MAP — mapping from real class names to stub component names</li>
     *   <li>ROOT_SERVICE — the real RootService class</li>
     * </ul>
     */
    public static class Data {
        private static final int STUB_VERSION = 0;
        private static final int CLASS_COMPONENT_MAP = 1;
        private static final int ROOT_SERVICE = 2;
        private static final int ARR_SIZE = 3;

        private final Object[] arr;

        public Data() { arr = new Object[ARR_SIZE]; }
        public Data(Object o) { arr = (Object[]) o; }
        public Object getObject() { return arr; }

        public int getVersion() { return (int) arr[STUB_VERSION]; }
        public void setVersion(int version) { arr[STUB_VERSION] = version; }
        public Map<String, String> getClassToComponent() {
            // noinspection unchecked
            return (Map<String, String>) arr[CLASS_COMPONENT_MAP];
        }
        public void setClassToComponent(Map<String, String> map) {
            arr[CLASS_COMPONENT_MAP] = map;
        }
        public Class<?> getRootService() { return (Class<?>) arr[ROOT_SERVICE]; }
        public void setRootService(Class<?> service) { arr[ROOT_SERVICE] = service; }
    }
}
