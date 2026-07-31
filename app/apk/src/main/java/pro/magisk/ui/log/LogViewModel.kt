/**
 * ViewModel for the log viewer screen.
 *
 * Fetches Superuser access logs and Magisk daemon logs via [LogRepository], then populates
 * two diff-aware lists. Supports saving a comprehensive debug log (device info, properties,
 * kernel, mountinfo, Magisk logs, logcat) to a file, as well as clearing logs.
 */
package pro.magisk.ui.log

import android.system.Os
import androidx.databinding.Bindable
import androidx.lifecycle.viewModelScope
import pro.magisk.BR
import pro.magisk.arch.AsyncLoadViewModel
import pro.magisk.core.BuildConfig
import pro.magisk.core.Info
import pro.magisk.core.R
import pro.magisk.core.ktx.time_format_standard
import pro.magisk.core.ktx.toTime
import pro.magisk.core.repository.LogRepository
import pro.magisk.core.utils.MediaStoreUtils
import pro.magisk.core.utils.MediaStoreUtils.output_stream
import pro.magisk.databinding.bind_extra
import pro.magisk.databinding.diffList
import pro.magisk.databinding.set
import pro.magisk.events.SnackbarEvent
import pro.magisk.view.TextItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream

/** ViewModel that loads and manages Superuser + Magisk daemon logs. */
class LogViewModel(
    private val repo: LogRepository
) : AsyncLoadViewModel() {
    @get:Bindable
    var loading = true
        private set(value) = set(value, field, { field = it }, BR.loading)

    val item_empty = TextItem(R.string.log_data_none)
    val item_magisk_empty = TextItem(R.string.log_data_magisk_none)

    val items = diffList<SuLogRvItem>()
    val extra_bindings = bind_extra {
        it.put(BR.view_model, this)
    }

    val logs = diffList<LogRvItem>()
    var magisk_log_raw = " "

    override suspend fun do_load_work() {
        loading = true

        val (su_logs, suDiff) = withContext(Dispatchers.Default) {
            magisk_log_raw = repo.fetch_magisk_logs()
            val new_logs = magisk_log_raw.split('\n').map { LogRvItem(it) }
            logs.update(new_logs)
            val su_logs = repo.fetch_su_logs().map { SuLogRvItem(it) }
            su_logs to items.calculate_diff(su_logs)
        }

        items.firstOrNull()?.is_top = false
        items.lastOrNull()?.is_bottom = false
        items.update(su_logs, suDiff)
        items.firstOrNull()?.is_top = true
        items.lastOrNull()?.is_bottom = true
        loading = false
    }

    /** Writes a comprehensive debug log file to the MediaStore. */
    fun save_magisk_log() = with_external_r_w {
        viewModelScope.launch(Dispatchers.IO) {
            val filename = "magisk_log_%s.log".format(
                System.currentTimeMillis().toTime(time_format_standard))
            val log_file = MediaStoreUtils.get_file(filename)
            log_file.uri.output_stream().bufferedWriter().use { file ->
                file.write("---Detected Device Info---\n\n")
                file.write("isAB=${Info.is_a_b}\n")
                file.write("isSAR=${Info.is_s_a_r}\n")
                file.write("ramdisk=${Info.ramdisk}\n")
                val uname = Os.uname()
                file.write("kernel=${uname.sysname} ${uname.machine} ${uname.release} ${uname.version}\n")

                file.write("\n\n---System Properties---\n\n")
                ProcessBuilder("getprop").start()
                    .inputStream.reader().use { it.copyTo(file) }

                file.write("\n\n---Environment Variables---\n\n")
                System.getenv().forEach { (key, value) -> file.write("${key}=${value}\n") }

                file.write("\n\n---System MountInfo---\n\n")
                FileInputStream("/proc/self/mountinfo").reader().use { it.copyTo(file) }

                file.write("\n---Magisk Logs---\n")
                file.write("${Info.env.version_string} (${Info.env.versionCode})\n\n")
                if (Info.env.isActive) file.write(magisk_log_raw)

                file.write("\n---Manager Logs---\n")
                file.write("${BuildConfig.APP_VERSION_NAME} (${BuildConfig.APP_VERSION_CODE})\n\n")
                ProcessBuilder("logcat", "-d").start()
                    .inputStream.reader().use { it.copyTo(file) }
            }
            SnackbarEvent(log_file.toString()).publish()
        }
    }

    fun clear_magisk_log() = repo.clear_magisk_logs {
        SnackbarEvent(R.string.logs_cleared).publish()
        start_loading()
    }

    fun clear_log() = viewModelScope.launch {
        repo.clear_logs()
        SnackbarEvent(R.string.logs_cleared).publish()
        start_loading()
    }
}
