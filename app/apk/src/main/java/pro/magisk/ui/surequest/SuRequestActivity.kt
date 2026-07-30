/**
 * Superuser request dialog — shown as a floating activity when an app requests root access.
 *
 * This is a special "untracked" activity (it does not belong to the main nav graph).
 * It listens for `REQUEST` actions and shows a grant/deny dialog with a countdown timer.
 * Other actions (e.g. logging callbacks) are dispatched directly to [SuCallbackHandler].
 * Overlay windows are hidden on Android 12+ to prevent tapjacking.
 */
package pro.magisk.ui.surequest

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import pro.magisk.R
import pro.magisk.arch.UIActivity
import pro.magisk.arch.viewModel
import pro.magisk.core.base.UntrackedActivity
import pro.magisk.core.su.SuCallbackHandler
import pro.magisk.core.su.SuCallbackHandler.REQUEST
import pro.magisk.databinding.ActivityRequestBinding
import pro.magisk.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Floating activity that handles Superuser grant/deny requests. */
open class SuRequestActivity : UIActivity<ActivityRequestBinding>(), UntrackedActivity {

    override val layoutRes: Int = R.layout.activity_request
    override val viewModel: SuRequestViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }
        setTheme(Theme.selected.themeRes)
        super.onCreate(savedInstanceState)

        if (intent.action == Intent.ACTION_VIEW) {
            val action = intent.getStringExtra("action")
            if (action == REQUEST) {
                viewModel.handleRequest(intent)
            } else {
                // Non-request action (e.g. logging callback) — handle and finish
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        SuCallbackHandler.run(this@SuRequestActivity, action, intent.extras)
                    }
                    finish()
                }
            }
        } else {
            finish()
        }
    }

    override fun getTheme(): Resources.Theme {
        val theme = super.getTheme()
        theme.applyStyle(R.style.Foundation_Floating, true)
        return theme
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        viewModel.denyPressed()
    }

    override fun finish() {
        super.finishAndRemoveTask()
    }
}
