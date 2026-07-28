/**
 * Base activity for all Magisk app screens.
 *
 * Provides:
 * - DataBinding setup ([setContentView]) with ViewModel and lifecycle owner binding
 * - ViewModel event observation ([startObserveLiveData])
 * - Window insets handling (RikkaX insets) and edge-to-edge configuration
 * - Snackbar display helper ([showSnackbar])
 * - Dark-theme initialisation from [Config]
 * - Workarounds for stub-APK edge cases (reflection-hack for config flags)
 * - Navigation bar transparency on gesture-nav devices
 * - [ViewGroup.startAnimations] extension for layout transition animations
 */
package pro.magisk.arch

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.res.use
import androidx.core.view.WindowCompat
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.snackbar.Snackbar
import pro.magisk.BR
import pro.magisk.R
import pro.magisk.core.Config
import pro.magisk.core.base.ActivityExtension
import pro.magisk.core.base.IActivityExtension
import pro.magisk.core.isRunningAsStub
import pro.magisk.core.ktx.reflectField
import pro.magisk.core.wrap
import rikka.insets.WindowInsetsHelper
import rikka.layoutinflater.view.LayoutInflaterFactory

/** Shared base Activity for all UI screens. */
abstract class UIActivity<Binding : ViewDataBinding>
    : AppCompatActivity(), ViewModelHolder, IActivityExtension {

    protected lateinit var binding: Binding
    protected abstract val layoutRes: Int
    override val extension = ActivityExtension(this)

    protected val binded get() = ::binding.isInitialized

    open val snackbarView get() = binding.root
    open val snackbarAnchorView: View? get() = null

    init {
        AppCompatDelegate.setDefaultNightMode(Config.darkTheme)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.wrap())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        layoutInflater.factory2 = LayoutInflaterFactory(delegate)
            .addOnViewCreatedListener(WindowInsetsHelper.LISTENER)

        extension.onCreate(savedInstanceState)
        if (isRunningAsStub) {
            // Suppress spurious "false" stack traces logged by AppCompatDelegateImpl
            // when the stub APK's delegate doesn't have the expected config-flags fields.
            val delegate = delegate
            val clz = delegate.javaClass
            clz.reflectField("mActivityHandlesConfigFlagsChecked").set(delegate, true)
            clz.reflectField("mActivityHandlesConfigFlags").set(delegate, 0)
        }

        super.onCreate(savedInstanceState)

        startObserveLiveData()

        // Explicitly propagate the windowBackground drawable (not always inherited)
        obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
            .use { it.getDrawable(0) }
            .also { window.setBackgroundDrawable(it) }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        // On gesture-nav devices the navbar is short → make it fully transparent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window?.decorView?.post {
                val insetBottom = window.decorView.rootWindowInsets?.systemWindowInsetBottom ?: 0
                if (insetBottom < Resources.getSystem().displayMetrics.density * 40) {
                    window.navigationBarColor = Color.TRANSPARENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        window.navigationBarDividerColor = Color.TRANSPARENT
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = false
                        window.isStatusBarContrastEnforced = false
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        extension.onSaveInstanceState(outState)
    }

    /** Inflates the layout via DataBinding and wires [viewModel] and lifecycle owner. */
    fun setContentView() {
        binding = DataBindingUtil.setContentView<Binding>(this, layoutRes).also {
            it.setVariable(BR.viewModel, viewModel)
            it.lifecycleOwner = this
        }
    }

    fun setAccessibilityDelegate(delegate: View.AccessibilityDelegate?) {
        binding.root.rootView.accessibilityDelegate = delegate
    }

    fun showSnackbar(
        message: CharSequence,
        length: Int = Snackbar.LENGTH_SHORT,
        builder: Snackbar.() -> Unit = {}
    ) = Snackbar.make(snackbarView, message, length)
        .setAnchorView(snackbarAnchorView).apply(builder).show()

    override fun onResume() {
        super.onResume()
        // Trigger async loading for screens that need it
        viewModel.let {
            if (it is AsyncLoadViewModel)
                it.startLoading()
        }
    }

    override fun onEventDispatched(event: ViewEvent) = when (event) {
        is ContextExecutor -> event(this)
        is ActivityExecutor -> event(this)
        else -> Unit
    }
}

/** Applies an [AutoTransition] animation to this [ViewGroup] for layout changes. */
fun ViewGroup.startAnimations() {
    val transition = AutoTransition()
        .setInterpolator(FastOutSlowInInterpolator())
        .setDuration(400)
        .excludeTarget(R.id.main_toolbar, true)
    TransitionManager.beginDelayedTransition(
        this,
        transition
    )
}
