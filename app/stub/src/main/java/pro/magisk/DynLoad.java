/**
 * Core engine for dynamically loading the real Magisk APK from inside the stub.
 *
 * Responsibilities:
 * 1. Locate and load the real APK from internal storage, an update file, or the previously
 *    installed app's APK.
 * 2. Create a {@link DynamicClassLoader} pointing to the real APK's dex.
 * 3. Instantiate the real Application class, passing stub data via an Object array constructor.
 * 4. Set up the component name mapping so that Android resolves stub component names to the
 *    real APK's components.
 * 5. Optionally install a {@link DelegateClassLoader} into LoadedApk on pre-Q devices.
 */
package pro.magisk;

import static pro.magisk.BuildConfig.APPLICATION_ID;

import android.app.AppComponentFactory;
import android.app.Application;
import android.app.job.JobService;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;

import pro.magisk.utils.APKInstall;
import pro.magisk.utils.DynamicClassLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class DynLoad {

    /** The DelegateComponentFactory instance set during construction. */
    static Object component_factory;
    /** The currently active class loader pointing to the real APK (or fallback stub loader). */
    static ClassLoader active_class_loader = DynLoad.class.getClassLoader();

    /** Creates a Data object populated with stub version, empty mapping, and StubRootService. */
    static StubApk.Data create_apk_data() {
        var data = new StubApk.Data();
        data.set_version(BuildConfig.STUB_VERSION);
        data.set_class_to_component(new HashMap<>());
        data.set_root_service(StubRootService.class);
        return data;
    }

    /**
     * Calls attachBaseContext on an object via reflection.
     * Used to wire up the real Application with the stub's Context.
     */
    static void attach_context(Object o, Context context) {
        if (!(o instanceof ContextWrapper))
            return;
        try {
            Method m = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            m.setAccessible(true);
            m.invoke(o, context);
        } catch (Exception ignored) { /* Impossible */ }
    }

    /**
     * Locates and loads the real Magisk APK.
     *
     * Search order:
     * 1. If an update APK exists, rename it to current.apk.
     * 2. In DEBUG builds, copy from external storage for development convenience.
     * 3. If current.apk exists, load it.
     * 4. If running under a different package name (hidden), copy the real app's APK.
     *
     * @return a DynamicClassLoader for the real APK, or null if no APK is available.
     */
    static DynamicClassLoader load_apk(Context context) {
        File apk = StubApk.current(context);
        File update = StubApk.update(context);

        if (update.exists()) {
            update.renameTo(apk);
        }

        if (BuildConfig.DEBUG) {
            try {
                File external = new File(context.getExternalFilesDir(null), "magisk.apk");
                if (external.exists()) {
                    apk.delete();
                    try {
                        var in = new FileInputStream(external);
                        var out = new FileOutputStream(apk);
                        apk.setReadOnly();
                        try (in; out) {
                            APKInstall.transfer(in, out);
                        }
                    } catch (IOException e) {
                        Log.e(DynLoad.class.getSimpleName(), "", e);
                        apk.delete();
                    } finally {
                        external.delete();
                    }
                }
            } catch (SecurityException e) {
                // Do not crash in root service
            }
        }

        if (apk.exists()) {
            apk.setReadOnly();
            return new DynamicClassLoader(apk);
        }

        // Attempt to copy from the previously installed app (used after hiding/renaming)
        if (!context.getPackageName().equals(APPLICATION_ID)) {
            try {
                var info = context.getPackageManager().getApplicationInfo(APPLICATION_ID, 0);
                apk.delete();
                var src = new FileInputStream(info.sourceDir);
                var out = new FileOutputStream(apk);
                apk.setReadOnly();
                try (src; out) {
                    APKInstall.transfer(src, out);
                }
                return new DynamicClassLoader(apk);
            } catch (PackageManager.NameNotFoundException ignored) {
            } catch (IOException e) {
                Log.e(DynLoad.class.getSimpleName(), "", e);
                apk.delete();
            }
        }

        return null;
    }

    /**
     * Loads the real APK and initializes the real Application object.
     *
     * Steps:
     * 1. On pre-Q devices, replace LoadedApk.mClassLoader with a DelegateClassLoader.
     * 2. Query the stub's own PackageInfo.
     * 3. Call {@link #loadApk} to obtain a DynamicClassLoader.
     * 4. If successful, generate component name mapping, create the real Application,
     *    set up the AppComponentFactory delegate, and update activeClassLoader.
     * 5. On failure, fall back to StubClassLoader.
     */
    static void load_and_initialize_app(Application context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            replace_class_loader(context);

        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                | PackageManager.GET_PROVIDERS | PackageManager.GET_RECEIVERS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DISABLED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        var pm = context.getPackageManager();

        final PackageInfo stub_info;
        try {
            // noinspection WrongConstant
            stub_info = pm.getPackageInfo(context.getPackageName(), flags);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }

        File apk = StubApk.current(context);

        final var cl = load_apk(context);
        if (cl != null) try {
            // noinspection WrongConstant
            var apk_info = pm.getPackageArchiveInfo(apk.getPath(), flags);
            var mapping = generate_mapping(stub_info, apk_info);

            var data = create_apk_data();
            var map = data.get_class_to_component();
            // Build inverse mapping: real component class name → stub component name
            for (var e : mapping.entrySet()) {
                map.put(e.getValue(), e.getKey());
            }

            var app_info = apk_info.applicationInfo;
            // The real Application must have a constructor accepting Object (the Data array)
            var app = cl.loadClass(app_info.className)
                    .getConstructor(Object.class)
                    .newInstance(data.get_object());

            // Wire up the AppComponentFactory delegate so Android creates real components
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && component_factory != null) {
                var delegate = (DelegateComponentFactory) component_factory;
                if (app_info.appComponentFactory == null) {
                    delegate.receiver = new AppComponentFactory();
                } else {
                    Object factory = cl.loadClass(app_info.appComponentFactory).newInstance();
                    delegate.receiver = (AppComponentFactory) factory;
                }
            }

            active_class_loader = new MappingClassLoader(cl, mapping);

            attach_context(app, context);
        } catch (Exception e) {
            Log.e(DynLoad.class.getSimpleName(), "", e);
            apk.delete();
        } else {
            active_class_loader = new StubClassLoader(stub_info);
        }
    }

    /**
     * On API < 29, replaces the platform's LoadedApk.mClassLoader with a DelegateClassLoader
     * so that all component resolution goes through the dynamically loaded APK.
     */
    private static void replace_class_loader(Context context) {
        // Unwrap to the base ContextImpl
        while (context instanceof ContextWrapper) {
            context = ((ContextWrapper) context).getBaseContext();
        }

        try {
            Field m_info = context.getClass().getDeclaredField("mPackageInfo");
            m_info.setAccessible(true);
            Object loaded_apk = m_info.get(context);
            assert loaded_apk != null;
            Field mcl = loaded_apk.getClass().getDeclaredField("mClassLoader");
            mcl.setAccessible(true);
            mcl.set(loaded_apk, new DelegateClassLoader());
        } catch (Exception e) {
            Log.e(DynLoad.class.getSimpleName(), "", e);
        }
    }

    /**
     * Generates a mapping from stub component names to real APK component names.
     *
     * Both APKs are expected to declare the same set of components in the same order.
     * For activities and services, exported/permission attributes are used to distinguish
     * the main vs. secondary component when ordering differs.
     */
    private static Map<String, String> generate_mapping(PackageInfo stub, PackageInfo app) {
        var mapping = new HashMap<String, String>();
        {
            var src = stub.activities;
            var dest = app.activities;

            final ActivityInfo sa;
            final ActivityInfo da;
            final ActivityInfo sb;
            final ActivityInfo db;
            // Match by exported flag: exported activity goes first
            if (src[0].exported) {
                sa = src[0];
                sb = src[1];
            } else {
                sa = src[1];
                sb = src[0];
            }
            if (dest[0].exported) {
                da = dest[0];
                db = dest[1];
            } else {
                da = dest[1];
                db = dest[0];
            }
            mapping.put(sa.name, da.name);
            mapping.put(sb.name, db.name);
        }

        {
            var src = stub.services;
            var dest = app.services;

            final ServiceInfo sa;
            final ServiceInfo da;
            final ServiceInfo sb;
            final ServiceInfo db;
            // Match by permission: JobService bind permission identifies the main service
            if (JobService.PERMISSION_BIND.equals(src[0].permission)) {
                sa = src[0];
                sb = src[1];
            } else {
                sa = src[1];
                sb = src[0];
            }
            if (JobService.PERMISSION_BIND.equals(dest[0].permission)) {
                da = dest[0];
                db = dest[1];
            } else {
                da = dest[1];
                db = dest[0];
            }
            mapping.put(sa.name, da.name);
            mapping.put(sb.name, db.name);
        }

        {
            var src = stub.receivers;
            var dest = app.receivers;
            mapping.put(src[0].name, dest[0].name);
        }

        {
            var src = stub.providers;
            var dest = app.providers;
            mapping.put(src[0].name, dest[0].name);
        }
        return mapping;
    }
}
