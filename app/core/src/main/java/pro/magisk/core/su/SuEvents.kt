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
    val policy_changed = _policyChanged.asSharedFlow()

    private val _logUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val log_updated = _logUpdated.asSharedFlow()

    fun notify_policy_changed() {
        _policyChanged.tryEmit(Unit)
    }

    fun notify_log_updated() {
        _logUpdated.tryEmit(Unit)
    }
}
