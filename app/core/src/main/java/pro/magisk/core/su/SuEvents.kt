/**
 * SharedFlow-based event bus for SU-related UI updates.
 *
 * ViewModels and screens collect [policyChanged] and [logUpdated]
 * to refresh their state when a policy or log entry changes.
 */
package pro.magisk.core.su

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object SuEvents {
    private val _policyChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val policyChanged = _policyChanged.asSharedFlow()

    private val _logUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val logUpdated = _logUpdated.asSharedFlow()

    fun notifyPolicyChanged() {
        _policyChanged.tryEmit(Unit)
    }

    fun notifyLogUpdated() {
        _logUpdated.tryEmit(Unit)
    }
}
