/**
 * Flashes a Magisk module ZIP by extracting the installer script
 * (`module_installer.sh`) and passing the ZIP to it via shell.
 *
 * The ZIP is copied to a temp directory unless it is already a local
 * file. Console and log output are collected in the provided lists.
 */
package pro.magisk.core.tasks

import android.net.Uri
import androidx.core.net.toFile
import pro.magisk.core.AppContext
import pro.magisk.core.Const
import pro.magisk.core.ktx.writeTo
import pro.magisk.core.utils.MediaStoreUtils.displayName
import pro.magisk.core.utils.MediaStoreUtils.inputStream
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Flashes a module ZIP by invoking the legacy
 * `update-binary` protocol via shell.
 *
 * @param mUri  URI of the ZIP to flash.
 * @param console Output list for user-facing console messages.
 * @param logs    Output list for detailed log entries.
 */
open class FlashZip(
    private val mUri: Uri,
    private val console: MutableList<String>,
    private val logs: MutableList<String>
) {

    private val installDir = File(AppContext.cacheDir, "flash")
    private lateinit var zipFile: File

    @Throws(IOException::class)
    private suspend fun flash(): Boolean {
        installDir.deleteRecursively()
        installDir.mkdirs()

        zipFile = if (mUri.scheme == "file") {
            mUri.toFile()
        } else {
            File(installDir, "install.zip").also {
                console.add("- Copying zip to temp directory")
                try {
                    mUri.inputStream().writeTo(it)
                } catch (e: IOException) {
                    when (e) {
                        is FileNotFoundException -> console.add("! Invalid Uri")
                        else -> console.add("! Cannot copy to cache")
                    }
                    throw e
                }
            }
        }

        try {
            val binary = File(installDir, "update-binary")
            AppContext.assets.open("module_installer.sh").use { it.writeTo(binary) }
        } catch (e: IOException) {
            console.add("! Unzip error")
            throw e
        }

        console.add("- Installing ${mUri.displayName}")

        return Shell.cmd("sh $installDir/update-binary dummy 1 \'$zipFile\'")
            .to(console, logs).exec().isSuccess
    }

    /** Execute the flash operation on [Dispatchers.IO]. */
    open suspend fun exec() = withContext(Dispatchers.IO) {
        try {
            if (!flash()) {
                console.add("! Installation failed")
                false
            } else {
                true
            }
        } catch (e: IOException) {
            Timber.e(e)
            false
        } finally {
            Shell.cmd("cd /", "rm -rf $installDir ${Const.TMPDIR}").submit()
        }
    }
}
