/**
 * Represents a Magisk module installed on the device.
 *
 * Reads metadata from `module.prop` and exposes state files
 * (`remove`, `disable`, `update`, `zygisk/`) as properties.
 */
package pro.magisk.core.model.module

import pro.magisk.core.Const
import pro.magisk.core.utils.RootUtils
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.nio.ExtendedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class LocalModule(
    val base: ExtendedFile,
) : Module() {
    override var id: String = ""
    override var name: String = ""
    override var version: String = ""
    override var versionCode: Int = -1
    var author: String = ""
    var description: String = ""

    private val remove_file = base.getChildFile("remove")
    private val disable_file = base.getChildFile("disable")
    private val update_file = base.getChildFile("update")
    val zygisk_folder = base.getChildFile("zygisk")

    val updated get() = update_file.exists()
    val is_riru = (id == "riru-core") || base.getChildFile("riru").exists()
    val is_zygisk = zygisk_folder.exists()
    val zygisk_unloaded = zygisk_folder.getChildFile("unloaded").exists()
    val has_action = base.getChildFile("action.sh").exists()

    var enable: Boolean
        get() = !disable_file.exists()
        set(enable) {
            if (enable) {
                disable_file.delete()
                Shell.cmd("copy_preinit_files").submit()
            } else {
                !disable_file.createNewFile()
                Shell.cmd("copy_preinit_files").submit()
            }
        }

    var remove: Boolean
        get() = remove_file.exists()
        set(remove) {
            if (remove) {
                if (update_file.exists()) return
                remove_file.createNewFile()
                Shell.cmd("copy_preinit_files").submit()
            } else {
                remove_file.delete()
                Shell.cmd("copy_preinit_files").submit()
            }
        }

    /** Parse module.prop into the model fields. */
    @Throws(NumberFormatException::class)
    private fun parse_props(props: List<String>) {
        for (line in props) {
            val prop = line.split("=".toRegex(), 2).map { it.trim() }
            if (prop.size != 2)
                continue

            val key = prop[0]
            val value = prop[1]
            if (key.isEmpty() || key[0] == '#')
                continue

            when (key) {
                "id" -> id = value
                "name" -> name = value
                "version" -> version = value
                "versionCode" -> versionCode = value.toInt()
                "author" -> author = value
                "description" -> description = value
            }
        }
    }

    init {
        runCatching {
            parse_props(Shell.cmd("dos2unix < $base/module.prop").exec().out)
        }

        if (id.isEmpty()) {
            id = base.name
        }

        if (name.isEmpty()) {
            name = id
        }
    }

    companion object {

        /** Check whether the module path exists. */
        fun loaded() = RootUtils.fs.get_file(Const.MODULE_PATH).exists()

        /** List all installed modules, sorted by name. */
        suspend fun installed() = withContext(Dispatchers.IO) {
            RootUtils.fs.get_file(Const.MODULE_PATH)
                .listFiles()
                .orEmpty()
                .filter { !it.isFile && !it.isHidden }
                .map { LocalModule(it) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
        }
    }
}
