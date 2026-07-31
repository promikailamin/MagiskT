/**
 * Custom ClassLoader implementations used by the stub APK's dynamic loading mechanism.
 *
 * Three loaders serve distinct roles:
 * - {@link MappingClassLoader}: wraps the real DCL to remap stub component names to real ones.
 * - {@link StubClassLoader}: fallback when no real APK is loaded; maps all declared components
 *   to their respective dummy implementations.
 * - {@link DelegateClassLoader}: thin proxy that forwards all loads to the active dynamic loader.
 */
package pro.magisk;

import android.content.pm.PackageInfo;

import pro.magisk.dummy.DummyProvider;
import pro.magisk.dummy.DummyReceiver;
import pro.magisk.dummy.DummyService;

import java.util.HashMap;
import java.util.Map;

/**
 * Class loader that remaps stub component names to their real counterparts in the loaded APK.
 * Used by the platform (via LoadedApk.mClassLoader) so that Android resolves the correct
 * Activity/Service/Provider/Receiver class from the real APK.
 */
class MappingClassLoader extends ClassLoader {

    /** Mapping from stub component class names to real component class names. */
    private final Map<String, String> mapping;

    MappingClassLoader(ClassLoader parent, Map<String, String> m) {
        super(parent);
        mapping = m;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        String clz = mapping.get(name);
        name = clz != null ? clz : name;
        return super.loadClass(name, resolve);
    }
}

/**
 * Fallback class loader used when no real APK has been dynamically loaded yet.
 * Maps every component declared in the stub manifest to its corresponding dummy class,
 * so Android can instantiate them without crashing before the real APK is available.
 */
class StubClassLoader extends ClassLoader {

    /** Mapping from component class names to their dummy implementations. */
    private final Map<String, Class<?>> mapping = new HashMap<>();

    StubClassLoader(PackageInfo info) {
        super(StubClassLoader.class.getClassLoader());
        for (var c : info.activities) {
            mapping.put(c.name, DownloadActivity.class);
        }
        for (var c : info.services) {
            mapping.put(c.name, DummyService.class);
        }
        for (var c : info.providers) {
            mapping.put(c.name, DummyProvider.class);
        }
        for (var c : info.receivers) {
            mapping.put(c.name, DummyReceiver.class);
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> clz = mapping.get(name);
        return clz == null ? super.loadClass(name, resolve) : clz;
    }
}

/**
 * Thin class loader that delegates every load request to {@link DynLoad#activeClassLoader}.
 * Installed into the platform's LoadedApk on pre-Q devices so all component resolution
 * goes through the dynamically loaded APK.
 */
class DelegateClassLoader extends ClassLoader {

    DelegateClassLoader() {
        super();
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        return DynLoad.active_class_loader.loadClass(name);
    }
}
