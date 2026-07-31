/**
 * ViewModel for the Superuser request dialog.
 *
 * Handles the full lifecycle of a root request: resolving the calling app's identity,
 * showing a grant/deny dialog with a countdown timer, and persisting the response.
 * Supports tapjacking protection (filtering obscured touches) and an empty accessibility
 * delegate that makes the dialog invisible to accessibility services when tapjacking
 * protection is enabled.
 */
package pro.magisk.ui.surequest

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.CountDownTimer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.Toast
import androidx.databinding.Bindable
import androidx.lifecycle.viewModelScope
import pro.magisk.BR
import pro.magisk.arch.BaseViewModel
import pro.magisk.core.AppContext
import pro.magisk.core.Config
import pro.magisk.core.R
import pro.magisk.core.data.magiskdb.PolicyDao
import pro.magisk.core.ktx.getLabel
import pro.magisk.core.ktx.toast
import pro.magisk.core.model.su.SuPolicy.Companion.ALLOW
import pro.magisk.core.model.su.SuPolicy.Companion.DENY
import pro.magisk.core.su.SuRequestHandler
import pro.magisk.core.utils.TextHolder
import pro.magisk.databinding.set
import pro.magisk.events.AuthEvent
import pro.magisk.events.DieEvent
import pro.magisk.events.ShowUIEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit.SECONDS

/** ViewModel for the floating Superuser grant/deny dialog. */
class SuRequestViewModel(
    policy_d_b: PolicyDao,
    private val timeout_prefs: SharedPreferences
) : BaseViewModel() {

    lateinit var icon: Drawable
    lateinit var title: String
    lateinit var packageName: String

    @get:Bindable
    val deny_text = DenyText()

    @get:Bindable
    var selectedItemPosition = 0
        set(value) = set(value, field, { field = it }, BR.selectedItemPosition)

    @get:Bindable
    var grant_enabled = false
        set(value) = set(value, field, { field = it }, BR.grant_enabled)

    /** Filters obscured touches (tapjacking protection). Consumes the event when obscured. */
    @SuppressLint("ClickableViewAccessibility")
    val grant_touch_listener = View.OnTouchListener { _: View, event: MotionEvent ->
        if (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0
            || event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0) {
            if (event.action == MotionEvent.ACTION_UP) {
                AppContext.toast(R.string.touch_filtered_warning, Toast.LENGTH_SHORT)
            }
            return@OnTouchListener Config.su_tapjack
        }
        false
    }

    private val handler = SuRequestHandler(AppContext.packageManager, policy_d_b)
    private val millis = SECONDS.toMillis(Config.su_default_timeout.toLong())
    private var timer = SuTimer(millis, 1000)
    private var initialized = false

    fun grant_pressed() {
        cancel_timer()
        if (Config.su_auth) {
            AuthEvent { respond(ALLOW) }.publish()
        } else {
            respond(ALLOW)
        }
    }

    fun deny_pressed() {
        respond(DENY)
    }

    fun spinner_touched(): Boolean {
        cancel_timer()
        return false
    }

    fun handle_request(intent: Intent) {
        viewModelScope.launch(Dispatchers.Default) {
            if (handler.start(intent))
                show_dialog()
            else
                DieEvent().publish()
        }
    }

    private fun show_dialog() {
        val pm = handler.pm
        val info = handler.pkg_info
        val app = info.applicationInfo

        if (app == null) {
            // Shared-UID request with no app info available
            icon = pm.defaultActivityIcon
            title = "[SharedUID] ${info.sharedUserId}"
            packageName = info.sharedUserId.toString()
        } else {
            val prefix = if (info.sharedUserId == null) "" else "[SharedUID] "
            icon = app.loadIcon(pm)
            title = "$prefix${app.getLabel(pm)}"
            packageName = info.packageName
        }

        selectedItemPosition = timeout_prefs.getInt(packageName, 0)

        timer.start()
        ShowUIEvent(if (Config.su_tapjack) EmptyAccessibilityDelegate else null).publish()
        initialized = true
    }

    private fun respond(action: Int) {
        if (!initialized) return

        timer.cancel()

        val pos = selectedItemPosition
        timeout_prefs.edit().putInt(packageName, pos).apply()

        viewModelScope.launch {
            handler.respond(action, Config.Value.TIMEOUT_LIST[pos])
            DieEvent().publish()
        }
    }

    private fun cancel_timer() {
        timer.cancel()
        deny_text.seconds = 0
    }

    /** Counts down from the configured timeout; auto-denies on expiry. */
    private inner class SuTimer(
        private val millis: Long,
        interval: Long
    ) : CountDownTimer(millis, interval) {

        override fun onTick(remains: Long) {
            if (!grant_enabled && remains <= millis - 1000) {
                grant_enabled = true
            }
            deny_text.seconds = (remains / 1000).toInt() + 1
        }

        override fun onFinish() {
            deny_text.seconds = 0
            respond(DENY)
        }

    }

    /** A [TextHolder] that appends the remaining seconds to the "Deny" label. */
    inner class DenyText : TextHolder() {
        var seconds = 0
            set(value) = set(value, field, { field = it }, BR.deny_text)

        override fun get_text(resources: Resources): String {
            return if (seconds != 0)
                "${resources.getString(R.string.deny)} ($seconds)"
            else
                resources.getString(R.string.deny)
        }
    }

    /** No-op accessibility delegate — makes the dialog invisible to accessibility services. */
    object EmptyAccessibilityDelegate : View.AccessibilityDelegate() {
        override fun sendAccessibilityEvent(host: View, eventType: Int) {}
        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?) = true
        override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {}
        override fun dispatchPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) = true
        override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {}
        override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {}
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {}
        override fun addExtraDataToAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo, extraDataKey: String, arguments: Bundle?) {}
        override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean = false
        override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProvider? = null
    }
}
