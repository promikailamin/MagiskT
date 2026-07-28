/**
 * Base ViewModel for screens that load data asynchronously. Provides debounced [startLoading]
 * and [reload] lifecycle methods that launch the abstract [doLoadWork] in [viewModelScope].
 */
package pro.magisk.arch

import androidx.annotation.MainThread
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

abstract class AsyncLoadViewModel : BaseViewModel() {

    private var loadingJob: Job? = null

    /** Start loading if no job is already active. Safe to call multiple times. */
    @MainThread
    fun startLoading() {
        if (loadingJob?.isActive == true) {
            return
        }
        loadingJob = viewModelScope.launch { doLoadWork() }
    }

    /** Cancel any existing load and restart. */
    @MainThread
    fun reload() {
        loadingJob?.cancel()
        loadingJob = viewModelScope.launch { doLoadWork() }
    }

    /** Implement to perform the actual background work. */
    protected abstract suspend fun doLoadWork()
}
