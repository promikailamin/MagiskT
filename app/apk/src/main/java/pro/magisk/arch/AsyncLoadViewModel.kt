/**
 * ViewModel with a controlled async-load lifecycle.
 *
 * [startLoading] is safe to call from any lifecycle callback (e.g. `onResume`) because it
 * guards against launching duplicate coroutines. Subclasses implement [doLoadWork] to perform
 * their one-shot data-loading operation.
 */
package pro.magisk.arch

import androidx.annotation.MainThread
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Base ViewModel for screens that need to load data once per resume.
 * Call [startLoading] from the Fragment/Activity lifecycle methods.
 */
abstract class AsyncLoadViewModel : BaseViewModel() {

    private var loadingJob: Job? = null

    @MainThread
    fun startLoading() {
        // Prevent multiple loading jobs from running concurrently
        if (loadingJob?.isActive == true) {
            return
        }
        loadingJob = viewModelScope.launch { doLoadWork() }
    }

    /** Implement this to perform the actual async data-loading work. */
    protected abstract suspend fun doLoadWork()
}
