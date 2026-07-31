/**
 * Runtime device / environment information.
 *
 * [init] queries the Magisk daemon and the `app_init` shell helper
 * to populate partition layout, root status, and daemon version.
 * Everything here is populated once at startup and is read-only
 * afterwards (private setters).
 */
package pro.magisk.core

import android.app.KeyguardManager
import android.os.Build
import androidx.lifecycle.MutableLiveData
import pro.magisk.StubApk
import pro.magisk.core.ktx.get_property
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils.fastCmd
import com.topjohnwu.superuser.ShellUtils.fastCmdResult
import kotlinx.coroutines.Runnable

/** `true` while the real APK is hosted inside the stub wrapper. */
val is_running_as_stub get() = Info.stub != null

object Info {

    /** Reference to stub-wrapper metadata (non-null only in stub mode). */
    var stub: StubApk.Data? = null

    /** Whether a rooted shell was obtained. */
    var is_rooted = false
    var no_data_exec = false
    var patch_boot_vbmeta = false

    /** Magisk daemon version envelope. */
    @JvmStatic var env = Env()
        private set
    /** System-as-root (SAR) flag. */
    @JvmStatic var is_s_a_r = false
        private set
    var legacy_s_a_r = false
        private set
    var is_a_b = false
        private set
    var slot = ""
        private set
    var is_vendor_boot = false
        private set
    @JvmField val is_zygisk_enabled = System.getenv("ZYGISK_ENABLED") == "1"
    @JvmField val is_denylist_enforced = System.getenv("DENYLIST_ENFORCED") == "1"
    @JvmStatic val is_f_d_e get() = crypto == "block"
    @JvmStatic var ramdisk = false
        private set
    private var crypto = ""

    /** Detects emulator environments through device property heuristics. */
    val is_emulator =
        Build.DEVICE.contains("vsoc")
            || get_property("ro.kernel.qemu", "0") == "1"
            || get_property("ro.boot.qemu", "0") == "1"

    /** Whether the SuperUser tab should be visible. */
    val show_super_user: Boolean get() {
        return env.isActive && (Const.USER_ID == 0
                || Config.su_multiuser_mode == Config.Value.MULTIUSER_MODE_USER)
    }

    val isDeviceSecure get() =
        AppContext.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /** Snapshot of the running Magisk daemon version. */
    class Env(
        val version_string: String = "",
        val is_debug: Boolean = false,
        code: Int = -1
    ) {
        val versionCode = when {
            code < Const.Version.MIN_VERCODE -> -1
            is_rooted -> code
            else -> -1
        }
        val is_unsupported = code > 0 && code < Const.Version.MIN_VERCODE
        val isActive = versionCode > 0
    }

    /** Queries the daemon for its version and partition metadata. */
    fun init(shell: Shell) {
        if (shell.isRoot) {
            val v = fastCmd(shell, "magisk -v").split(":")
            env = Env(
                v[0], v.size >= 3 && v[2] == "D",
                runCatching { fastCmd("magisk -V").toInt() }.getOrDefault(-1)
            )
            Config.deny_list = fastCmdResult(shell, "magisk --denylist status")
        }

        val map = mutableMapOf<String, String>()
        val list = object : CallbackList<String>(Runnable::run) {
            override fun onAddElement(e: String) {
                val split = e.split("=")
                if (split.size >= 2) {
                    map[split[0]] = split[1]
                }
            }
        }
        shell.newJob().add("(app_init)").to(list).exec()

        fun get_var(name: String) = map[name] ?: ""
        fun get_bool(name: String) = map[name].toBoolean()

        is_s_a_r = get_bool("SYSTEM_AS_ROOT")
        ramdisk = get_bool("RAMDISKEXIST")
        is_a_b = get_bool("ISAB")
        patch_boot_vbmeta = get_bool("PATCHVBMETAFLAG")
        crypto = get_var("CRYPTOTYPE")
        slot = get_var("SLOT")
        legacy_s_a_r = get_bool("LEGACYSAR")
        is_vendor_boot = get_bool("VENDORBOOT")

        Config.recovery = get_bool("RECOVERYMODE")
        Config.keep_verity = get_bool("KEEPVERITY")
        Config.keep_enc = get_bool("KEEPFORCEENCRYPT")
    }
}
