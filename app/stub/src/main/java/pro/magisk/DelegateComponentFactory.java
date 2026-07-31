/**
 * Entry point for dynamic class loading on Android Q+.
 *
 * Registered via AndroidManifest as the application's AppComponentFactory, this class
 * intercepts all component instantiation requests (activities, services, receivers,
 * providers) and delegates them to the dynamically loaded APK's own AppComponentFactory.
 * Before dynamic loading completes, fallback dummy components are returned.
 */
package pro.magisk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

import pro.magisk.dummy.DummyProvider;
import pro.magisk.dummy.DummyReceiver;
import pro.magisk.dummy.DummyService;

@SuppressLint("NewApi")
public class DelegateComponentFactory extends AppComponentFactory {

    /** The real AppComponentFactory from the dynamically loaded APK, set after loading. */
    AppComponentFactory receiver;

    /**
     * Registers this instance in DynLoad so the loading pipeline can set {@link #receiver}.
     */
    public DelegateComponentFactory() {
        DynLoad.component_factory = this;
    }

    /** Replaces the platform class loader with DelegateClassLoader to route through our DCL. */
    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo info) {
        return new DelegateClassLoader();
    }

    /** Always instantiates StubApplication, which triggers dynamic loading in its attachBaseContext. */
    @Override
    public Application instantiateApplication(ClassLoader cl, String className) {
        return new StubApplication();
    }

    /** Delegates activity creation to the loaded APK's factory, or returns a DownloadActivity fallback. */
    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        if (receiver != null)
            return receiver.instantiateActivity(DynLoad.active_class_loader, className, intent);
        return create(className, DownloadActivity.class);
    }

    /** Delegates receiver creation to the loaded APK's factory, or returns a DummyReceiver fallback. */
    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        if (receiver != null)
            return receiver.instantiateReceiver(DynLoad.active_class_loader, className, intent);
        return create(className, DummyReceiver.class);
    }

    /** Delegates service creation to the loaded APK's factory, or returns a DummyService fallback. */
    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        if (receiver != null)
            return receiver.instantiateService(DynLoad.active_class_loader, className, intent);
        return create(className, DummyService.class);
    }

    /** Delegates provider creation to the loaded APK's factory, or returns a DummyProvider fallback. */
    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        if (receiver != null)
            return receiver.instantiateProvider(DynLoad.active_class_loader, className);
        return create(className, DummyProvider.class);
    }

    /**
     * Attempts to load and instantiate a class by name from the active class loader.
     * Falls back to a default instance if the class is not found.
     */
    private <T> T create(String name, Class<T> fallback)
            throws IllegalAccessException, InstantiationException {
        try {
            // noinspection unchecked
            return (T) DynLoad.active_class_loader.loadClass(name).newInstance();
        } catch (ClassNotFoundException e) {
            return fallback.newInstance();
        }
    }

}
