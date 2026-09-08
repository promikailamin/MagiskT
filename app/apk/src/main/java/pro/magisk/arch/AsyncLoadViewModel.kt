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
import timber.log.Timber

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
            Timber.tag(tag).d("startLoading: skip, job ${loadingJob} still active")
            return
        }
        val time = System.currentTimeMillis()
        Timber.tag(tag).d("startLoading: launching doLoadWork")
        loadingJob = viewModelScope.launch {
            Timber.tag(tag).d("doLoadWork started")
            try {
                doLoadWork()
                Timber.tag(tag).d("doLoadWork finished in ${System.currentTimeMillis() - time} ms")
            } catch (t: Throwable) {
                Timber.tag(tag).e(t, "doLoadWork failed after ${System.currentTimeMillis() - time} ms")
                throw t
            } finally {
                loadingJob = null
            }
        }
    }

    /** Implement this to perform the actual async data-loading work. */
    protected abstract suspend fun doLoadWork()

    /** Per-instance tag so logs identify the concrete screen. */
    private val tag: String
        get() = javaClass.simpleName
}