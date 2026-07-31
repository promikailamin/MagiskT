/**
 * Magisk installation / patching engine.
 *
 * [MagiskInstallImpl] is the abstract base that handles the full
 * pipeline: finding the boot image, extracting binaries from the APK,
 * processing payload / tar / factory-image formats, patching the boot
 * image with `boot_patch.sh`, and writing output to a file or
 * flashing it directly.
 *
 * Concrete operations are exposed through [MagiskInstaller] inner
 * classes: [Direct], [Patch], [SecondSlot], [Emulator], [Uninstall],
 * [Restore], and [FixEnv].
 */
package pro.magisk.core.tasks

import android.net.Uri
import android.os.Process
import android.system.Os
import androidx.annotation.WorkerThread
import androidx.core.os.postDelayed
import pro.magisk.StubApk
import pro.magisk.core.AppApkPath
import pro.magisk.core.AppContext
import pro.magisk.core.BuildConfig
import pro.magisk.core.Config
import pro.magisk.core.Const
import pro.magisk.core.Info
import pro.magisk.core.is_running_as_stub
import pro.magisk.core.ktx.copyAll
import pro.magisk.core.ktx.deviceProtectedContext
import pro.magisk.core.ktx.writeTo
import pro.magisk.core.utils.DataSourceChannel
import pro.magisk.core.utils.DummyList
import pro.magisk.core.utils.MediaStoreUtils
import pro.magisk.core.utils.MediaStoreUtils.inputStream
import pro.magisk.core.utils.MediaStoreUtils.openFd
import pro.magisk.core.utils.MediaStoreUtils.output_stream
import pro.magisk.core.utils.RootUtils
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.internal.UiThreadHandler
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Abstract base for Magisk installation operations.
 *
 * Subclasses implement [operations] to perform a specific task
 * (direct install, patch file, OTA second-slot, etc.).
 */
abstract class MagiskInstallImpl protected constructor(
    protected val console: MutableList<String>,
    private val logs: MutableList<String>
) {

    private lateinit var install_dir: ExtendedFile
    private lateinit var src_boot: ExtendedFile

    private val shell = Shell.getShell()
    private val use_root_dir = shell.isRoot && Info.no_data_exec
    protected val context get() = AppContext.deviceProtectedContext

    private val root_f_s get() = RootUtils.fs
    private val local_f_s get() = FileSystemManager.getLocal()

    private val dest_name: String by lazy {
        if (Config.rand_name) {
            val alpha = "abcdefghijklmnopqrstuvwxyz"
            val alpha_num = "$alpha${alpha.uppercase(Locale.ROOT)}0123456789"
            val random = SecureRandom()
            StringBuilder("magisk_patched-${BuildConfig.APP_VERSION_CODE}_").run {
                for (i in 1..5) {
                    append(alpha_num[random.nextInt(alpha_num.length)])
                }
                toString()
            }
        } else {
            "magisk_patched"
        }
    }

    /** Locate the boot image for the given slot. */
    private fun find_image(slot: String): Boolean {
        val cmd =
            "RECOVERYMODE=${Config.recovery} " +
            "VENDORBOOT=${Info.is_vendor_boot} " +
            "SLOT=$slot " +
            "find_boot_image; echo \$BOOTIMAGE"
        val boot_path = ("($cmd)").fsh()
        if (boot_path.isEmpty()) {
            console.add("! Unable to detect target image")
            return false
        }
        src_boot = root_f_s.get_file(boot_path)
        console.add("- Target image: $boot_path")
        return true
    }

    /** Locate the boot image for the current slot. */
    private fun find_image(): Boolean {
        return find_image(Info.slot)
    }

    private fun find_secondary(): Boolean {
        val slot = if (Info.slot == "_a") "_b" else "_a"
        console.add("- Target slot: $slot")
        return find_image(slot)
    }

    private suspend fun extract_files(): Boolean {
        console.add("- Device platform: ${Const.CPU_ABI}")
        console.add("- Installing: ${BuildConfig.APP_VERSION_NAME} (${BuildConfig.APP_VERSION_CODE})")

        install_dir = local_f_s.get_file(context.filesDir.parent, "install")
        install_dir.deleteRecursively()
        install_dir.mkdirs()

        try {
            // Extract binaries
            if (is_running_as_stub) {
                ZipFile.builder().setFile(StubApk.current(context)).get().use { zf ->
                    zf.entries.asSequence().filter {
                        !it.isDirectory && it.name.startsWith("lib/${Const.CPU_ABI}/")
                    }.forEach {
                        val n = it.name.substring(it.name.lastIndexOf('/') + 1)
                        val name = n.substring(3, n.length - 3)
                        val dest = File(install_dir, name)
                        zf.getInputStream(it).writeTo(dest)
                        dest.setExecutable(true)
                    }

                    val abi32 = Const.CPU_ABI_32
                    if (Process.is64Bit() && abi32 != null) {
                        val entry = zf.getEntry("lib/$abi32/libmagisk.so")
                        if (entry != null) {
                            val magisk32 = File(install_dir, "magisk32")
                            zf.getInputStream(entry).writeTo(magisk32)
                        }
                    }
                }
            } else {
                val info = context.applicationInfo
                val libs = File(info.nativeLibraryDir).listFiles { _, name ->
                    name.startsWith("lib") && name.endsWith(".so")
                } ?: emptyArray()

                for (lib in libs) {
                    val name = lib.name.substring(3, lib.name.length - 3)
                    Os.symlink(lib.path, "$install_dir/$name")
                }

                // Also extract magisk32 on 64-bit devices that supports 32-bit
                val abi32 = Const.CPU_ABI_32
                if (Process.is64Bit() && abi32 != null) {
                    val name = "lib/$abi32/libmagisk.so"
                    val entry = javaClass.classLoader!!.getResourceAsStream(name)
                    if (entry != null) {
                        val magisk32 = File(install_dir, "magisk32")
                        entry.writeTo(magisk32)
                    }
                }
            }

            // Extract scripts
            for (script in listOf("util_functions.sh", "boot_patch.sh", "addon.d.sh", "stub.apk")) {
                val dest = File(install_dir, script)
                context.assets.open(script).writeTo(dest)
            }
            // Extract chromeos tools
            File(install_dir, "chromeos").mkdir()
            for (file in listOf("futility", "kernel_data_key.vbprivk", "kernel.keyblock")) {
                val name = "chromeos/$file"
                val dest = File(install_dir, name)
                context.assets.open(name).writeTo(dest)
            }
        } catch (e: Exception) {
            console.add("! Unable to extract files")
            Timber.e(e)
            return false
        }

        if (use_root_dir) {
            // Move everything to tmpfs to workaround Samsung bullshit
            root_f_s.get_file(Const.TMPDIR).also {
                arrayOf(
                    "rm -rf $it",
                    "mkdir -p $it",
                    "cp_readlink $install_dir $it",
                    "rm -rf $install_dir"
                ).sh()
                install_dir = it
            }
        }

        return true
    }

    private suspend fun InputStream.copyAndCloseOut(out: OutputStream) =
        out.use { copyAll(it, 1024 * 1024) }

    private class NoAvailableStream(s: InputStream) : FilterInputStream(s) {
        // Make sure available is never called on the actual stream and always return 0
        // to reduce max buffer size and avoid OOM
        override fun available() = 0
    }

    private class NoBootException : IOException()

    inner class BootItem(private val entry: TarArchiveEntry) {
        val name = entry.name.replace(".lz4", "")
        var file = install_dir.getChildFile(name)

        suspend fun copyTo(tar_out: TarArchiveOutputStream) {
            entry.name = name
            entry.size = file.length()
            file.newInputStream().use {
                console.add("-- Writing   : $name")
                tar_out.putArchiveEntry(entry)
                it.copyAll(tar_out)
                tar_out.closeArchiveEntry()
            }
        }
    }

    @Throws(IOException::class)
    private suspend fun process_tar(
        tarIn: TarArchiveInputStream,
        tar_out: TarArchiveOutputStream
    ): BootItem {
        console.add("- Processing tar file")
        var entry: TarArchiveEntry? = tarIn.nextEntry

        fun decompressed_stream(): InputStream {
            val stream = if (tarIn.currentEntry.name.endsWith(".lz4"))
                FramedLZ4CompressorInputStream(tarIn, true) else tarIn
            return NoAvailableStream(stream)
        }

        var boot: BootItem? = null
        var init_boot: BootItem? = null
        var recovery: BootItem? = null

        while (entry != null) {
            val boot_item: BootItem?
            if (entry.name.startsWith("boot.img")) {
                boot_item = BootItem(entry)
                boot = boot_item
            } else if (entry.name.startsWith("init_boot.img")) {
                boot_item = BootItem(entry)
                init_boot = boot_item
            } else if (Config.recovery && entry.name.contains("recovery.img")) {
                boot_item = BootItem(entry)
                recovery = boot_item
            } else {
                boot_item = null
            }

            if (boot_item != null) {
                console.add("-- Extracting: ${boot_item.name}")
                decompressed_stream().copyAndCloseOut(boot_item.file.newOutputStream())
            } else if (entry.name.contains("vbmeta.img")) {
                val raw_data = decompressed_stream().readBytes()
                // Valid vbmeta.img should be at least 256 bytes
                if (raw_data.size < 256)
                    continue

                // vbmeta partition exist, disable boot vbmeta patch
                Info.patch_boot_vbmeta = false

                val name = entry.name.replace(".lz4", "")
                console.add("-- Patching  : $name")

                // Patch flags to AVB_VBMETA_IMAGE_FLAGS_HASHTREE_DISABLED |
                // AVB_VBMETA_IMAGE_FLAGS_VERIFICATION_DISABLED
                ByteBuffer.wrap(raw_data).putInt(120, 3)

                // Fetch the next entry first before modifying current entry
                val vbmeta = entry
                entry = tarIn.nextEntry

                // Update entry with new information
                vbmeta.name = name
                vbmeta.size = raw_data.size.toLong()

                // Write output
                tar_out.putArchiveEntry(vbmeta)
                tar_out.write(raw_data)
                tar_out.closeArchiveEntry()
                continue
            } else if (entry.name.contains("userdata.img")) {
                console.add("-- Skipping  : ${entry.name}")
            } else {
                console.add("-- Copying   : ${entry.name}")
                tar_out.putArchiveEntry(entry)
                tarIn.copyAll(tar_out)
                tar_out.closeArchiveEntry()
            }
            entry = tarIn.nextEntry ?: break
        }

        // Patch priority: recovery > init_boot > boot
        return when {
            recovery != null -> {
                if (boot != null) {
                    // Repack boot image to prevent auto restore
                    arrayOf(
                        "cd $install_dir",
                        "chmod -R 755 .",
                        "./magiskboot unpack boot.img",
                        "./magiskboot repack boot.img",
                        "cat new-boot.img > boot.img",
                        "./magiskboot cleanup",
                        "rm -f new-boot.img",
                        "cd /").sh()
                    boot.copyTo(tar_out)
                }
                recovery
            }
            init_boot != null -> {
                boot?.copyTo(tar_out)
                init_boot
            }
            boot != null -> boot
            else -> throw NoBootException()
        }
    }

    private suspend fun process_file(uri: Uri): Boolean {
        val out_stream: OutputStream
        val out_file: MediaStoreUtils.UriFile
        var boot_item: BootItem? = null

        // Process input file
        try {
            PushbackInputStream(uri.inputStream().buffered(1024 * 1024), 512).use { src ->
                val head = ByteArray(512)
                if (src.read(head) != head.size) {
                    console.add("! Invalid input file")
                    return false
                }
                src.unread(head)

                val magic = head.copyOf(4)
                val tar_magic = head.copyOfRange(257, 262)

                src_boot = if (tar_magic.contentEquals("ustar".toByteArray())) {
                    // tar file
                    out_file = MediaStoreUtils.get_file("$dest_name.tar")
                    val os = out_file.uri.output_stream().buffered(1024 * 1024)
                    out_stream = TarArchiveOutputStream(os).also {
                        it.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
                        it.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                    }

                    try {
                        boot_item = process_tar(TarArchiveInputStream(src), out_stream)
                        boot_item.file
                    } catch (e: IOException) {
                        out_stream.close()
                        out_file.delete()
                        throw e
                    }
                } else {
                    // raw image
                    out_file = MediaStoreUtils.get_file("$dest_name.img")
                    out_stream = out_file.uri.output_stream()
                    val channel = FileInputStream(uri.openFd().fileDescriptor).channel
                    val boot = install_dir.getChildFile("boot.img")

                    try {
                        if (magic.contentEquals("CrAU".toByteArray())) {
                            DataSourceChannel(channel).use { source ->
                                Payload(source).extract(boot, { console.add(it) }, { logs.add(it) })
                            }
                        } else if (magic.contentEquals("PK\u0003\u0004".toByteArray())) {
                            ExtractImage(boot, console, logs).consume(DataSourceChannel(channel))
                        } else {
                            console.add("- Copying image to cache")
                            src.copyAndCloseOut(boot.newOutputStream())
                        }
                        boot
                    } catch (e: IOException) {
                        out_stream.close()
                        out_file.delete()
                        throw e
                    }
                }
            }
        } catch (e: IOException) {
            if (e is NoBootException)
                console.add("! No boot image found")
            console.add("! Process error")
            Timber.e(e)
            return false
        }

        // Patch file
        if (!patch_boot()) {
            out_file.delete()
            return false
        }

        // Output file
        try {
            val new_boot = install_dir.getChildFile("new-boot.img")
            if (boot_item != null) {
                boot_item.file = new_boot
                boot_item.copyTo(out_stream as TarArchiveOutputStream)
            } else {
                new_boot.newInputStream().use { it.copyAll(out_stream, 1024 * 1024) }
            }
            new_boot.delete()

            console.add("")
            console.add("****************************")
            console.add(" Output file is written to ")
            console.add(" $out_file ")
            console.add("****************************")
        } catch (e: IOException) {
            console.add("! Failed to output to $out_file")
            out_file.delete()
            Timber.e(e)
            return false
        } finally {
            out_stream.close()
        }

        // Fix up binaries
        src_boot.delete()
        "cp_readlink $install_dir".sh()

        return true
    }

    private fun patch_boot(): Boolean {
        val new_boot = install_dir.getChildFile("new-boot.img")
        if (!use_root_dir) {
            // Create output files before hand
            new_boot.createNewFile()
            File(install_dir, "stock_boot.img").createNewFile()
        }

        val cmds = arrayOf(
            "cd $install_dir",
            "KEEPFORCEENCRYPT=${Config.keep_enc} " +
            "KEEPVERITY=${Config.keep_verity} " +
            "PATCHVBMETAFLAG=${Info.patch_boot_vbmeta} " +
            "RECOVERYMODE=${Config.recovery} " +
            "LEGACYSAR=${Info.legacy_s_a_r} " +
            "sh boot_patch.sh $src_boot")
        val is_success = cmds.sh().is_success

        shell.newJob().add("./magiskboot cleanup", "cd /").exec()

        return is_success
    }

    private fun flash_boot() = "direct_install $install_dir $src_boot".sh().is_success

    private suspend fun post_o_t_a(): Boolean {
        try {
            val bootctl = File.createTempFile("bootctl", null, context.cacheDir)
            context.assets.open("bootctl").writeTo(bootctl)
            "post_ota $bootctl".sh()
        } catch (e: IOException) {
            console.add("! Unable to download bootctl")
            Timber.e(e)
            return false
        }

        console.add("*************************************************************")
        console.add(" Next reboot will boot to second slot!")
        console.add(" Go back to System Updates and press Restart to complete OTA")
        console.add("*************************************************************")
        return true
    }

    private fun Array<String>.eq() = shell.newJob().add(*this).to(console, logs).enqueue()
    private fun String.sh() = shell.newJob().add(this).to(console, logs).exec()
    private fun Array<String>.sh() = shell.newJob().add(*this).to(console, logs).exec()
    private fun String.fsh() = ShellUtils.fastCmd(shell, this)
    private fun Array<String>.fsh() = ShellUtils.fastCmd(shell, *this)

    protected suspend fun patch_file(file: Uri) = extract_files() && process_file(file)

    protected suspend fun direct() = find_image() && extract_files() && patch_boot() && flash_boot()

    protected suspend fun second_slot() =
        find_secondary() && extract_files() && patch_boot() && flash_boot() && post_o_t_a()

    protected suspend fun fix_env() = extract_files() && "fix_env $install_dir".sh().is_success

    protected fun restore() = find_image() && "restore_imgs $src_boot".sh().is_success

    protected fun uninstall() = "run_uninstaller $AppApkPath".sh().is_success

    @WorkerThread
    protected abstract suspend fun operations(): Boolean

    open suspend fun exec(): Boolean {
        if (have_active_session.getAndSet(true))
            return false

        val result = withContext(Dispatchers.IO) { operations() }
        have_active_session.set(false)
        if (result)
            return true

        // Not every operation initializes installDir
        if (::install_dir.isInitialized)
            Shell.cmd("rm -rf $install_dir").submit()
        return false
    }

    companion object {
        private var have_active_session = AtomicBoolean(false)
    }
}

    /**
     * [MagiskInstallImpl] variant that prints a final "All done!"
     * or "Installation failed" message to the console.
     */
    abstract class ConsoleInstaller(
    console: MutableList<String>,
    logs: MutableList<String>
) : MagiskInstallImpl(console, logs) {
    override suspend fun exec(): Boolean {
        val success = super.exec()
        if (success) {
            console.add("- All done!")
        } else {
            console.add("! Installation failed")
        }
        return success
    }
}

/** [MagiskInstallImpl] variant that invokes a callback on completion. */
abstract class CallBackInstaller : MagiskInstallImpl(DummyList, DummyList) {
    suspend fun exec(callback: (Boolean) -> Unit): Boolean {
        val success = exec()
        callback(success)
        return success
    }
}

/**
 * Concrete installation operations.
 *
 * Each inner class maps to a user-visible action in the app UI:
 * - [Direct] — flash directly to the current boot partition.
 * - [Patch] — patch a boot image file and save the result.
 * - [SecondSlot] — flash to the inactive slot (for OTA).
 * - [Emulator] — fix the environment (used by emulators).
 * - [Uninstall] — remove Magisk and optionally the app.
 * - [Restore] — restore stock boot image.
 * - [FixEnv] — fix environment without flashing.
 */
class MagiskInstaller {

    class Patch(
        private val uri: Uri,
        console: MutableList<String>,
        logs: MutableList<String>
    ) : ConsoleInstaller(console, logs) {
        override suspend fun operations() = patch_file(uri)
    }

    class SecondSlot(
        console: MutableList<String>,
        logs: MutableList<String>
    ) : ConsoleInstaller(console, logs) {
        override suspend fun operations() = second_slot()
    }

    class Direct(
        console: MutableList<String>,
        logs: MutableList<String>
    ) : ConsoleInstaller(console, logs) {
        override suspend fun operations() = direct()
    }

    class Emulator(
        console: MutableList<String>,
        logs: MutableList<String>
    ) : ConsoleInstaller(console, logs) {
        override suspend fun operations() = fix_env()
    }

    class Uninstall(
        console: MutableList<String>,
        logs: MutableList<String>
    ) : ConsoleInstaller(console, logs) {
        override suspend fun operations() = uninstall()

        override suspend fun exec(): Boolean {
            val success = super.exec()
            if (success) {
                UiThreadHandler.handler.postDelayed(3000) {
                    Shell.cmd("pm uninstall ${context.packageName}").exec()
                }
            }
            return success
        }
    }

    class Restore : CallBackInstaller() {
        override suspend fun operations() = restore()
    }

    class FixEnv : CallBackInstaller() {
        override suspend fun operations() = fix_env()
    }
}
