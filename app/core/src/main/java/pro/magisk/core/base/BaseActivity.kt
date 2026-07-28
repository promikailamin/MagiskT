/**
 * Base activity infrastructure.
 *
 * Provides reusable interfaces and helpers:
 * - [ActivityExtension] — lightweight runtime-permission, install
 *   permission, authentication, and content-picker requests via
 *   `ActivityResult` contracts.
 * - [UntrackedActivity] — marker interface that prevents
 *   [AppContext] from tracking the activity as foreground.
 * - [launchPackage] — reflection-based access to the package that
 *   launched this activity (needed pre-API 34).
 * - [relaunch] — restarts the activity with a clean intent.
 */
package pro.magisk.core.base

import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.REQUEST_INSTALL_PACKAGES
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import pro.magisk.core.R
import pro.magisk.core.ktx.reflectField
import pro.magisk.core.ktx.toast
import pro.magisk.core.utils.RequestAuthentication
import pro.magisk.core.utils.RequestInstall

/** Callback for content-picker results that is also [Parcelable] for state saving. */
interface ContentResultCallback: ActivityResultCallback<Uri>, Parcelable {
    fun onActivityLaunch() {}
    override fun onActivityResult(result: Uri)
}

/** Marker interface — activities implementing this are not tracked by [AppContext]. */
interface UntrackedActivity

/** Interface for activities that delegate runtime-request logic to [ActivityExtension]. */
interface IActivityExtension {
    val extension: ActivityExtension
    fun withPermission(permission: String, callback: (Boolean) -> Unit) {
        extension.withPermission(permission, callback)
    }
    fun withAuthentication(callback: (Boolean) -> Unit) {
        extension.withAuthentication(callback)
    }
    fun getContent(type: String, callback: ContentResultCallback) {
        extension.getContent(type, callback)
    }
}

/**
 * Delegate that manages Activity Result API contracts for
 * permissions, install requests, authentication, and content
 * picking.
 */
class ActivityExtension(private val activity: ComponentActivity) {

    private var permissionCallback: ((Boolean) -> Unit)? = null
    private val requestPermission = activity.registerForActivityResult(RequestPermission()) {
        permissionCallback?.invoke(it)
        permissionCallback = null
    }

    private var installCallback: ((Boolean) -> Unit)? = null
    private val requestInstall = activity.registerForActivityResult(RequestInstall()) {
        installCallback?.invoke(it)
        installCallback = null
    }

    private var authenticateCallback: ((Boolean) -> Unit)? = null
    private val requestAuthenticate = activity.registerForActivityResult(RequestAuthentication()) {
        authenticateCallback?.invoke(it)
        authenticateCallback = null
    }

    private var contentCallback: ContentResultCallback? = null
    private val getContent = activity.registerForActivityResult(GetContent()) {
        if (it != null) contentCallback?.onActivityResult(it)
        contentCallback = null
    }

    fun onCreate(savedInstanceState: Bundle?) {
        contentCallback = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            savedInstanceState?.getParcelable(CONTENT_CALLBACK_KEY)
        } else {
            savedInstanceState
                ?.getParcelable(CONTENT_CALLBACK_KEY, ContentResultCallback::class.java)
        }
    }

    fun onSaveInstanceState(outState: Bundle) {
        contentCallback?.let {
            outState.putParcelable(CONTENT_CALLBACK_KEY, it)
        }
    }

    fun withPermission(permission: String, callback: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            permission == WRITE_EXTERNAL_STORAGE) {
            callback(true)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            permission == POST_NOTIFICATIONS) {
            callback(true)
            return
        }
        if (permission == REQUEST_INSTALL_PACKAGES) {
            installCallback = callback
            requestInstall.launch(Unit)
        } else {
            permissionCallback = callback
            requestPermission.launch(permission)
        }
    }

    fun withAuthentication(callback: (Boolean) -> Unit) {
        authenticateCallback = callback
        requestAuthenticate.launch(Unit)
    }

    fun getContent(type: String, callback: ContentResultCallback) {
        contentCallback = callback
        try {
            getContent.launch(type)
            callback.onActivityLaunch()
        } catch (e: ActivityNotFoundException) {
            activity.toast(R.string.app_not_found, Toast.LENGTH_SHORT)
        }
    }

    companion object {
        private const val CONTENT_CALLBACK_KEY = "content_callback"
    }
}

/** The package that launched this activity (reflection fallback pre-API 34). */
val Activity.launchPackage: String? get() {
    return if (Build.VERSION.SDK_INT >= 34) {
        launchedFromPackage
    } else {
        Activity::class.java.reflectField("mReferrer").get(this) as String?
    }
}

/** Relaunch the activity with a clean intent. */
fun Activity.relaunch() {
    startActivity(Intent(intent).setFlags(0))
    finish()
}
