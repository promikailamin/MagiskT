/**
 * Manual ViewModel factory (no Hilt/Dagger). Resolves ViewModel dependencies from the
 * [ServiceLocator] singleton for those ViewModels that require injected services.
 */
package pro.magisk.arch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import pro.magisk.core.di.ServiceLocator
import pro.magisk.ui.home.HomeViewModel
import pro.magisk.ui.install.InstallViewModel
import pro.magisk.ui.log.LogViewModel
import pro.magisk.ui.superuser.SuperuserViewModel
import pro.magisk.ui.surequest.SuRequestViewModel

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
