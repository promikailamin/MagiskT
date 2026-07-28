/**
 * Interfaces and utilities that wire ViewModels to their lifecycle owners.
 *
 * [ViewModelHolder] is the contract that every ViewModel-hosting UI component must satisfy.
 * [VMFactory] is a manual DI factory (no Hilt/Dagger) that constructs ViewModels with their
 * required dependencies from [pro.magisk.core.di.ServiceLocator].
 */
package pro.magisk.arch

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import pro.magisk.core.di.ServiceLocator
import pro.magisk.ui.home.HomeViewModel
import pro.magisk.ui.install.InstallViewModel
import pro.magisk.ui.log.LogViewModel
import pro.magisk.ui.superuser.SuperuserViewModel
import pro.magisk.ui.surequest.SuRequestViewModel

/** Marks a lifecycle owner that holds a [BaseViewModel] and can receive [ViewEvent]s. */
interface ViewModelHolder : LifecycleOwner, ViewModelStoreOwner {

    val viewModel: BaseViewModel

    fun startObserveLiveData() {
        viewModel.viewEvents.observe(this, this::onEventDispatched)
    }

    /**
     * Called for all [ViewEvent]s published by associated viewModel.
     */
    fun onEventDispatched(event: ViewEvent) {}
}

/**
 * Manual [ViewModelProvider.Factory] that constructs ViewModels with their injected dependencies.
 * Falls back to reflection ([Class.newInstance]) for ViewModels without special constructor args.
 */
object VMFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when (modelClass) {
            HomeViewModel::class.java -> HomeViewModel()
            LogViewModel::class.java -> LogViewModel(ServiceLocator.logRepo)
            SuperuserViewModel::class.java -> SuperuserViewModel(ServiceLocator.policyDB)
            InstallViewModel::class.java -> InstallViewModel()
            SuRequestViewModel::class.java ->
                SuRequestViewModel(ServiceLocator.policyDB, ServiceLocator.timeoutPrefs)
            else -> modelClass.newInstance()
        } as T
    }
}

/** Lazily obtains a ViewModel scoped to the [ViewModelHolder] using [VMFactory]. */
inline fun <reified VM : ViewModel> ViewModelHolder.viewModel() =
    lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, VMFactory)[VM::class.java]
    }
