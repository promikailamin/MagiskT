/**
 * Shell and superuser extension functions.
 *
 * Provides a convenience [reboot] function that respects the recovery
 * config and an [await] extension on [Shell.Job] for use in
 * coroutines.
 */
package pro.magisk.core.ktx

import pro.magisk.core.Config
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reboot the device, optionally into recovery mode. */
fun reboot(reason: String = if (Config.recovery) "recovery" else "") {
    if (reason == "recovery") {
        Shell.cmd("/system/bin/input keyevent 26").submit()
    }
    Shell.cmd("/system/bin/svc power reboot $reason || /system/bin/reboot $reason").submit()
}

/** Execute a shell job and await its completion from a coroutine. */
suspend fun Shell.Job.await() = withContext(Dispatchers.IO) { exec() }
