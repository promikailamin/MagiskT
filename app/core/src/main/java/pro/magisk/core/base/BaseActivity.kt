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
    fun on_activity_launch() {}
    override fun onActivityResult(result: Uri)
}

/** Marker interface — activities implementing this are not tracked by [AppContext]. */
interface UntrackedActivity

/** Interface for activities that delegate runtime-request logic to [ActivityExtension]. */
interface IActivityExtension {
    val extension: ActivityExtension
    fun with_permission(permission: String, callback: (Boolean) -> Unit) {
        extension.with_permission(permission, callback)
    }
    fun with_authentication(callback: (Boolean) -> Unit) {
        extension.with_authentication(callback)
    }
    fun get_content(type: String, callback: ContentResultCallback) {
        extension.get_content(type, callback)
    }
}

/**
 * Delegate that manages Activity Result API contracts for
 * permissions, install requests, authentication, and content
 * picking.
 */
class ActivityExtension(private val activity: ComponentActivity) {

    private var permission_callback: ((Boolean) -> Unit)? = null
    private val request_permission = activity.registerForActivityResult(RequestPermission()) {
        permission_callback?.invoke(it)
        permission_callback = null
    }

    private var install_callback: ((Boolean) -> Unit)? = null
    private val request_install = activity.registerForActivityResult(RequestInstall()) {
        install_callback?.invoke(it)
        install_callback = null
    }

    private var authenticate_callback: ((Boolean) -> Unit)? = null
    private val request_authenticate = activity.registerForActivityResult(RequestAuthentication()) {
        authenticate_callback?.invoke(it)
        authenticate_callback = null
    }

    private var content_callback: ContentResultCallback? = null
    private val get_content = activity.registerForActivityResult(GetContent()) {
        if (it != null) content_callback?.onActivityResult(it)
        content_callback = null
    }

    fun onCreate(saved_instance_state: Bundle?) {
        content_callback = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            saved_instance_state?.getParcelable(CONTENT_CALLBACK_KEY)
        } else {
            saved_instance_state
                ?.getParcelable(CONTENT_CALLBACK_KEY, ContentResultCallback::class.java)
        }
    }

    fun onSaveInstanceState(outState: Bundle) {
        content_callback?.let {
            outState.putParcelable(CONTENT_CALLBACK_KEY, it)
        }
    }

    fun with_permission(permission: String, callback: (Boolean) -> Unit) {
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
            install_callback = callback
            request_install.launch(Unit)
        } else {
            permission_callback = callback
            request_permission.launch(permission)
        }
    }

    fun with_authentication(callback: (Boolean) -> Unit) {
        authenticate_callback = callback
        request_authenticate.launch(Unit)
    }

    fun get_content(type: String, callback: ContentResultCallback) {
        content_callback = callback
        try {
            get_content.launch(type)
            callback.on_activity_launch()
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
