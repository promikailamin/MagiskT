/**
 * A custom {@link BaseDexClassLoader} that reverses the standard class resolution order.
 *
 * Standard Android class loading checks the parent first. This loader checks the
 * boot classpath first, then the dex file it wraps, then falls back to the parent.
 * This ensures classes from the dynamically loaded APK take priority over the
 * host app's classes when they share package names.
 *
 * For root services, the optimized directory is set to null to bypass DexFile
 * security checks that would otherwise fail when running as root.
 */
package pro.magisk.utils;

import android.os.Process;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;

import dalvik.system.BaseDexClassLoader;

public class DynamicClassLoader extends BaseDexClassLoader {

    /** Creates a DynamicClassLoader with the application's class loader as parent. */
    public DynamicClassLoader(File apk) {
        this(apk, DynamicClassLoader.class.getClassLoader());
    }

    /**
     * Creates a DynamicClassLoader for the given APK file.
     *
     * @param apk    the APK file to load dex from
     * @param parent the parent class loader for fallback resolution
     */
    public DynamicClassLoader(File apk, ClassLoader parent) {
        // optimizedDirectory is null for root (uid 0) to bypass DexFile security checks
        super(apk.getPath(), Process.myUid() == 0 ? null : apk.getParentFile(), null, parent);
    }

    /**
     * Loads a class with reversed priority:
     * 1. Already loaded classes
     * 2. Boot classpath (system class loader)
     * 3. This loader's dex files
     * 4. Parent class loader
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> cls = findLoadedClass(name);
        if (cls != null)
            return cls;

        try {
            return getSystemClassLoader().loadClass(name);
        } catch (ClassNotFoundException ignored) {
            try {
                return findClass(name);
            } catch (ClassNotFoundException from_super) {
                try {
                    return getParent().loadClass(name);
                } catch (ClassNotFoundException e) {
                    throw from_super;
                }
            }
        }
    }

    /**
     * Finds a resource with reversed priority matching the loadClass order.
     */
    @Override
    public URL getResource(String name) {
        URL resource = getSystemClassLoader().getResource(name);
        if (resource != null)
            return resource;
        resource = findResource(name);
        if (resource != null)
            return resource;
        resource = getParent().getResource(name);
        return resource;
    }

    /**
     * Returns an enumeration of all resources with the given name, combining
     * results from the system class loader, this loader's dex, and the parent.
     */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        return new CompoundEnumeration<>(getSystemClassLoader().getResources(name),
                findResources(name), getParent().getResources(name));
    }
}
