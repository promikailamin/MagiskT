/**
 * [Shell.Initializer] that sets up every root / non-root shell spawned
 * by the app.
 *
 * Responsibilities:
 * - Marks [Info.isRooted] when root access is confirmed.
 * - Exports `ASH_STANDALONE=1` and points `sh` at Magisk's bundled
 *   busybox.
 * - Detects `no data exec` (Samsung) and copies busybox to a tmpfs or
 *   `/dev` workaround.
 * - Sources shell function libraries (`app_functions.sh`,
 *   `util_functions.sh`).
 * - Calls [Info.init] to populate runtime environment information.
 */
package pro.magisk.core.utils

import android.content.Context
import pro.magisk.StubApk
import pro.magisk.core.Const
import pro.magisk.core.Info
import pro.magisk.core.is_running_as_stub
import pro.magisk.core.ktx.cachedFile
import pro.magisk.core.ktx.deviceProtectedContext
import pro.magisk.core.ktx.writeTo
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.jar.JarFile

/**
 * [Shell.Initializer] called once per shell process.
 *
 * Sets up the environment: exports `ASH_STANDALONE`, points `sh` at
 * Magisk's bundled busybox, detects `no data exec` workarounds, and
 * sources shell function libraries.
 */
class ShellInit : Shell.Initializer() {
    override fun onInit(context: Context, shell: Shell): Boolean {
        if (shell.isRoot) {
            Info.is_rooted = true
            RootUtils.bind_task?.let { shell.execTask(it) }
            RootUtils.bind_task = null
        }
        shell.newJob().apply {
            add("export ASH_STANDALONE=1")

            val local_b_b: File
            if (is_running_as_stub) {
                if (!shell.isRoot)
                    return true
                val jar = JarFile(StubApk.current(context))
                val bb = jar.getJarEntry("lib/${Const.CPU_ABI}/libbusybox.so")
                local_b_b = context.deviceProtectedContext.cachedFile("busybox")
                local_b_b.delete()
                runBlocking {
                    jar.getInputStream(bb).writeTo(local_b_b, dispatcher = Dispatchers.Unconfined)
                }
                local_b_b.setExecutable(true)
            } else {
                local_b_b = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
            }

            if (shell.isRoot) {
                add("export MAGISKTMP=\$(magisk --path)")
                Info.no_data_exec = !shell.newJob()
                    .add("$local_b_b sh -c '$local_b_b true'").exec().is_success
            }

            if (Info.no_data_exec) {
                add(
                    "if [ -x \$MAGISKTMP/.magisk/busybox/busybox ]; then",
                    "  cp -af $local_b_b \$MAGISKTMP/.magisk/busybox/busybox",
                    "  exec \$MAGISKTMP/.magisk/busybox/busybox sh",
                    "else",
                    "  cp -af $local_b_b /dev/busybox",
                    "  exec /dev/busybox sh",
                    "fi"
                )
            } else {
                add("exec $local_b_b sh")
            }

            add(context.assets.open("app_functions.sh"))
            if (shell.isRoot) {
                add(context.assets.open("util_functions.sh"))
            }
        }.exec()

        Info.init(shell)
        return true
    }
}
