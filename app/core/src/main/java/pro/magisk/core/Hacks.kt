/**
 * Hacks and extension functions that bridge Magisk's unique runtime
 * requirements with the Android framework.
 *
 * - **Resource patching** — in stub mode the real APK assets are
 *   merged into the resource table.
 * - **Locale patching** — per-app locale overrides are applied to
 *   every [Resources] instance.
 * - **Component resolution** — class names are mapped through the
 *   stub's component translation table.
 * - **Resource keep list** — certain resources are referenced only
 *   by module props or external sources; listing them here prevents
 *   R8 from stripping them.
 */
@file:Suppress("DEPRECATION")

package pro.magisk.core

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import pro.magisk.StubApk
import pro.magisk.core.ktx.unwrap
import pro.magisk.core.utils.LocaleSetting

/** Merge the real APK's asset path into a [Resources] instance. */
fun Resources.addAssetPath(path: String) = StubApk.addAssetPath(this, path)

/** Apply locale + stub asset overrides to a [Resources] instance. */
fun Resources.patch(): Resources {
    if (isRunningAsStub)
        addAssetPath(AppApkPath)
    LocaleSetting.instance.updateResource(this)
    return this
}

/** Apply resource patches to the root [Context]. */
fun Context.patch(): Context {
    unwrap().resources.patch()
    return this
}

/**
 * Wrap a context so that configuration changes (e.g. night mode,
 * locale) produce a correctly-patched child context.
 */
fun Context.wrap(): Context {
    patch()
    return object : ContextWrapper(this) {
        override fun createConfigurationContext(config: Configuration): Context {
            return super.createConfigurationContext(config).wrap()
        }
    }
}

/** Resolve a class name through the stub's component mapping table. */
fun Class<*>.cmp(pkg: String) =
    ComponentName(pkg, Info.stub?.classToComponent?.get(name) ?: name)

/** Convenience to build an [Intent] targeting a component in the current package. */
inline fun <reified T> Context.intent() = Intent().setComponent(T::class.java.cmp(packageName))

/**
 * Resources referenced only dynamically (module props, external
 * callers). Keeping them here prevents R8 from stripping them as
 * "unused".
 */
val shouldKeepResources = listOf(
    R.string.no_info_provided,
    R.string.release_notes,
    R.string.home_item_source,
    R.drawable.ic_more,
    R.array.allow_timeout,
)
