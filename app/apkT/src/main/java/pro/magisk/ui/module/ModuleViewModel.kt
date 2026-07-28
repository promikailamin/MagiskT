/**
 * ViewModel for the module list. Loads installed modules from [LocalModule], computes per-item
 * metadata (notices for incompatible modules, action availability), and toggles enable/remove.
 */
package pro.magisk.ui.module

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import pro.magisk.arch.AsyncLoadViewModel
import pro.magisk.core.Const
import pro.magisk.core.Info
import pro.magisk.core.R as CoreR
import pro.magisk.core.model.module.LocalModule
import pro.magisk.core.utils.TextHolder
import pro.magisk.core.utils.asText
import pro.magisk.ui.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Wrapper around [LocalModule] that adds Compose-observable state for enabled/removed flags
 * and pre-computed UI metadata (notice text, action availability).
 */
class ModuleItem(val module: LocalModule) {
    val showNotice: Boolean
    val showAction: Boolean
    val noticeText: TextHolder

    init {
        val isZygisk = module.isZygisk
        val isRiru = module.isRiru
        val zygiskUnloaded = isZygisk && module.zygiskUnloaded

        // Show a compatibility notice when Zygisk is enabled but the module targets Riru,
        // or when Zygisk is disabled but the module requires it.
        showNotice = zygiskUnloaded ||
            (Info.isZygiskEnabled && isRiru) ||
            (!Info.isZygiskEnabled && isZygisk)
        showAction = module.hasAction && !showNotice
        noticeText =
            when {
                zygiskUnloaded -> CoreR.string.zygisk_module_unloaded.asText()
                isRiru -> CoreR.string.suspend_text_riru.asText(CoreR.string.zygisk.asText())
                else -> CoreR.string.suspend_text_zygisk.asText(CoreR.string.zygisk.asText())
            }
    }

    var isEnabled by mutableStateOf(module.enable)
    var isRemoved by mutableStateOf(module.remove)
    val isUpdated = module.updated
}

class ModuleViewModel : AsyncLoadViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val modules: List<ModuleItem> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    override suspend fun doLoadWork() {
        _uiState.update { it.copy(loading = true) }
        val moduleLoaded = Info.env.isActive &&
            withContext(Dispatchers.IO) { LocalModule.loaded() }
        if (moduleLoaded) {
            val modules = withContext(Dispatchers.Default) {
                LocalModule.installed().map { ModuleItem(it) }
            }
            _uiState.update { it.copy(loading = false, modules = modules) }
        } else {
            _uiState.update { it.copy(loading = false) }
        }
    }

    /** Navigate to the flash screen to install a module ZIP. */
    fun confirmLocalInstall(uri: Uri) {
        navigateTo(Route.Flash(Const.Value.FLASH_ZIP, uri.toString()))
    }

    /** Navigate to the action terminal screen for a module. */
    fun runAction(id: String, name: String) {
        navigateTo(Route.Action(id, name))
    }

    /** Toggle a module's enabled state (persisted via [LocalModule]). */
    fun toggleEnabled(item: ModuleItem) {
        item.isEnabled = !item.isEnabled
        item.module.enable = item.isEnabled
    }

    /** Toggle a module's remove flag (persisted via [LocalModule]). */
    fun toggleRemove(item: ModuleItem) {
        item.isRemoved = !item.isRemoved
        item.module.remove = item.isRemoved
    }
}
