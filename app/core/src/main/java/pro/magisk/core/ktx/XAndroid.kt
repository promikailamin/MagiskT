/**
 * Android framework extension functions.
 *
 * Covers bitmap rendering, context unwrapping, device-protected
 * storage, keyboard hiding, package info lookup (with UID/PID
 * resolution via [RootUtils]), broadcast receiver registration, and
 * toast / intent helpers.
 */
package pro.magisk.core.ktx

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Process
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.getSystemService
import pro.magisk.core.utils.LocaleSetting
import pro.magisk.core.utils.RootUtils
import pro.magisk.utils.APKInstall
import com.topjohnwu.superuser.internal.UiThreadHandler
import java.io.File

/** Rasterize a drawable resource into a [Bitmap]. */
fun Context.getBitmap(id: Int): Bitmap {
    var drawable = getDrawable(id)!!
    if (drawable is BitmapDrawable)
        return drawable.bitmap
    if (SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
        drawable = LayerDrawable(arrayOf(drawable.background, drawable.foreground))
    }
    val bitmap = Bitmap.createBitmap(
        drawable.intrinsicWidth, drawable.intrinsicHeight,
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

/** Device-protected storage context (or self on pre-N). */
val Context.deviceProtectedContext: Context get() =
    if (SDK_INT >= Build.VERSION_CODES.N) {
        createDeviceProtectedStorageContext()
    } else { this }

/** Shortcut for `File(cacheDir, name)`. */
fun Context.cachedFile(name: String) = File(cacheDir, name)

/** Resolve the application label, respecting locale overrides. */
fun ApplicationInfo.getLabel(pm: PackageManager): String {
    runCatching {
        if (labelRes > 0) {
            val res = pm.getResourcesForApplication(this)
            LocaleSetting.instance.updateResource(res)
            return res.getString(labelRes)
        }
    }

    return loadLabel(pm).toString()
}

/** Unwrap nested [ContextWrapper]s to reach the base context. */
fun Context.unwrap(): Context {
    var context = this
    while (context is ContextWrapper)
        context = context.baseContext
    return context
}

/** Hide the software keyboard. */
fun Activity.hideKeyboard() {
    val view = currentFocus ?: return
    getSystemService<InputMethodManager>()
        ?.hideSoftInputFromWindow(view.windowToken, 0)
    view.clearFocus()
}

/** Resolve the [Activity] hosting this [View] by walking the context chain. */
val View.activity: Activity get() {
    var context = context
    while(true) {
        if (context !is ContextWrapper)
            error("View is not attached to activity")
        if (context is Activity)
            return context
        context = context.baseContext
    }
}

/** Read a system property via reflection (`android.os.SystemProperties`). */
@SuppressLint("PrivateApi")
fun getProperty(key: String, def: String): String {
    runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java, String::class.java)
        return get.invoke(clazz, key, def) as String
    }
    return def
}

/**
 * Resolve a [PackageInfo] for a given UID / PID pair.
 *
 * When multiple packages share a UID the PID is used to disambiguate
 * via [RootUtils.getAppProcess]. Shell UID falls back to
 * `com.android.shell`.
 */
@SuppressLint("InlinedApi")
@Throws(PackageManager.NameNotFoundException::class)
fun PackageManager.getPackageInfo(uid: Int, pid: Int): PackageInfo? {
    val flag = PackageManager.MATCH_UNINSTALLED_PACKAGES
    val pkgs = getPackagesForUid(uid) ?: throw PackageManager.NameNotFoundException()
    if (pkgs.size > 1) {
        if (pid <= 0) {
            return null
        }
        val proc = RootUtils.getAppProcess(pid)
        if (proc == null) {
            if (uid == Process.SHELL_UID) {
                return getPackageInfo("com.android.shell", flag)
            }
        } else if (uid == proc.uid) {
            return getPackageInfo(proc.pkgList[0], flag)
        }

        return null
    }
    if (pkgs.size == 1) {
        return getPackageInfo(pkgs[0], flag)
    }
    throw PackageManager.NameNotFoundException()
}

/** Register a [BroadcastReceiver] at runtime (works around API limits on stub APKs). */
fun Context.registerRuntimeReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
    APKInstall.registerReceiver(this, receiver, filter)
}

/** Build an intent that launches the app's own launcher activity. */
fun Context.selfLaunchIntent(): Intent {
    val pm = packageManager
    val intent = pm.getLaunchIntentForPackage(packageName)!!
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    return intent
}

/** Show a toast on the UI thread. */
fun Context.toast(msg: CharSequence, duration: Int) {
    UiThreadHandler.run { Toast.makeText(this, msg, duration).show() }
}

/** Show a toast from a string resource on the UI thread. */
fun Context.toast(resId: Int, duration: Int) {
    UiThreadHandler.run { Toast.makeText(this, resId, duration).show() }
}
