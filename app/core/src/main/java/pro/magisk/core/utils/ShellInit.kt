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
import pro.magisk.core.isRunningAsStub
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
            Info.isRooted = true
            RootUtils.bindTask?.let { shell.execTask(it) }
            RootUtils.bindTask = null
        }
        shell.newJob().apply {
            add("export ASH_STANDALONE=1")

            val localBB: File
            if (isRunningAsStub) {
                if (!shell.isRoot)
                    return true
                val jar = JarFile(StubApk.current(context))
                val bb = jar.getJarEntry("lib/${Const.CPU_ABI}/libbusybox.so")
                localBB = context.deviceProtectedContext.cachedFile("busybox")
                localBB.delete()
                runBlocking {
                    jar.getInputStream(bb).writeTo(localBB, dispatcher = Dispatchers.Unconfined)
                }
                localBB.setExecutable(true)
            } else {
                localBB = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
            }

            if (shell.isRoot) {
                add("export MAGISKTMP=\$(magisk --path)")
                Info.noDataExec = !shell.newJob()
                    .add("$localBB sh -c '$localBB true'").exec().isSuccess
            }

            if (Info.noDataExec) {
                add(
                    "if [ -x \$MAGISKTMP/.magisk/busybox/busybox ]; then",
                    "  cp -af $localBB \$MAGISKTMP/.magisk/busybox/busybox",
                    "  exec \$MAGISKTMP/.magisk/busybox/busybox sh",
                    "else",
                    "  cp -af $localBB /dev/busybox",
                    "  exec /dev/busybox sh",
                    "fi"
                )
            } else {
                add("exec $localBB sh")
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
