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
import pro.magisk.core.utils.MediaStoreUtils.display_name
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
    private val m_uri: Uri,
    private val console: MutableList<String>,
    private val logs: MutableList<String>
) {

    private val install_dir = File(AppContext.cacheDir, "flash")
    private lateinit var zip_file: File

    @Throws(IOException::class)
    private suspend fun flash(): Boolean {
        install_dir.deleteRecursively()
        install_dir.mkdirs()

        zip_file = if (m_uri.scheme == "file") {
            m_uri.toFile()
        } else {
            File(install_dir, "install.zip").also {
                console.add("- Copying zip to temp directory")
                try {
                    m_uri.inputStream().writeTo(it)
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
            val binary = File(install_dir, "update-binary")
            AppContext.assets.open("module_installer.sh").use { it.writeTo(binary) }
        } catch (e: IOException) {
            console.add("! Unzip error")
            throw e
        }

        console.add("- Installing ${m_uri.display_name}")

        return Shell.cmd("sh $install_dir/update-binary dummy 1 \'$zip_file\'")
            .to(console, logs).exec().is_success
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
            Shell.cmd("cd /", "rm -rf $install_dir ${Const.TMPDIR}").submit()
        }
    }
}
