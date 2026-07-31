/**
 * Base Fragment for all screens using DataBinding and ViewModel event dispatching.
 *
 * Handles:
 * - DataBinding inflation with ViewModel and lifecycle-owner wiring
 * - MenuProvider registration for fragments that host options menus
 * - ViewModel state save/restore
 * - Delegation of [ViewEvent] to executor interfaces ([ContextExecutor], [ActivityExecutor], [FragmentExecutor])
 * - Navigation action safety check before navigating
 * - Snackbar anchor/view delegates (overridable by subclasses)
 */
package pro.magisk.arch

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.databinding.DataBindingUtil
import androidx.databinding.OnRebindCallback
import androidx.databinding.ViewDataBinding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDirections
import pro.magisk.BR

/** Shared base Fragment for all DataBinding-backed screens. */
abstract class BaseFragment<Binding : ViewDataBinding> : Fragment(), ViewModelHolder {

    val activity get() = getActivity() as? NavigationActivity<*>
    protected lateinit var binding: Binding
    protected abstract val layout_res: Int

    private val navigation get() = activity?.navigation
    open val snackbar_view: View? get() = null
    open val snackbar_anchor_view: View? get() = null

    override fun onCreate(saved_instance_state: Bundle?) {
        super.onCreate(saved_instance_state)
        start_observe_live_data()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        saved_instance_state: Bundle?
    ): View? {
        binding = DataBindingUtil.inflate<Binding>(inflater, layout_res, container, false).also {
            it.setVariable(BR.view_model, view_model)
            it.lifecycleOwner = viewLifecycleOwner
        }
        if (this is MenuProvider) {
            activity?.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.STARTED)
        }
        saved_instance_state?.let { view_model.on_restore_state(it) }
        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        view_model.on_save_state(outState)
    }

    override fun onStart() {
        super.onStart()
        // Clear any subtitle left by a previous destination
        activity?.supportActionBar?.subtitle = null
    }

    override fun on_event_dispatched(event: ViewEvent) = when(event) {
        is ContextExecutor -> event(requireContext())
        is ActivityExecutor -> activity?.let { event(it) } ?: Unit
        is FragmentExecutor -> event(this)
        else -> Unit
    }

    open fun on_key_event(event: KeyEvent): Boolean {
        return false
    }

    open fun onBackPressed(): Boolean = false

    override fun onViewCreated(view: View, saved_instance_state: Bundle?) {
        super.onViewCreated(view, saved_instance_state)
        binding.addOnRebindCallback(object : OnRebindCallback<Binding>() {
            override fun on_pre_bind(binding: Binding): Boolean {
                this@BaseFragment.on_pre_bind(binding)
                return true
            }
        })
    }

    override fun onResume() {
        super.onResume()
        view_model.let {
            if (it is AsyncLoadViewModel)
                it.start_loading()
        }
    }

    protected open fun on_pre_bind(binding: Binding) {
        (binding.root as? ViewGroup)?.startAnimations()
    }

    /** Navigates via this [NavDirections] action only if the destination exists in the nav graph. */
    fun NavDirections.navigate() {
        navigation?.currentDestination?.getAction(actionId)?.let { navigation!!.navigate(this) }
    }
}
