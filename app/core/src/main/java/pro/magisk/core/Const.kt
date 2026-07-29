package pro.magisk.core

import android.os.Build
import android.os.Process
import pro.magisk.core.BuildConfig.APP_VERSION_CODE

/**
 * Well-known constants shared across the Magisk ecosystem.
 *
 * Organised into logical groups – paths, version guards,
 * URL links, Intent keys, flash-action values, and navigation
 * section identifiers.
 */
@Suppress("DEPRECATION")
object Const {

    /** Primary ABI of the device (e.g. arm64-v8a). */
    val CPU_ABI: String get() = Build.SUPPORTED_ABIS[0]

    /**
     * 32-bit fallback ABI, if one exists.
     * `null` when the device is purely 32-bit or purely 64-bit.
     */
    val CPU_ABI_32 =
        if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) null
        else Build.SUPPORTED_32_BIT_ABIS.firstOrNull()

    // -----------------------------------------------------------------
    //  File-system paths
    // -----------------------------------------------------------------
    const val MODULE_PATH  = "/data/adb/modules"
    const val TMPDIR = "/dev/tmp"
    const val MAGISK_LOG = "/cache/magisk.log"

    // -----------------------------------------------------------------
    //  Process info
    // -----------------------------------------------------------------
    /** App-specific user ID (multiuser offset). */
    val USER_ID = Process.myUid() / 100000

    /** Version-guard helpers – call sites check whether the running
     *  Magisk daemon is recent enough for a given feature. */
    object Version {
        const val MIN_VERSION = "v22.0"
        const val MIN_VERCODE = 22000

        /** A non-zero last-two digits signals a canary build. */
        private fun isCanary() = (Info.env.versionCode % 100) != 0
        fun atLeast_24_0() = Info.env.versionCode >= 24000 || isCanary()
        fun atLeast_25_0() = Info.env.versionCode >= 25000 || isCanary()
        fun atLeast_28_0() = Info.env.versionCode >= 28000 || isCanary()
        fun atLeast_30_1() = Info.env.versionCode >= 30100 || isCanary()
    }

    /** Community / project URLs. */
    object Url {
        const val PATREON_URL = "https://www.patreon.com/topjohnwu"
        const val SOURCE_CODE_URL = "https://github.com/promikailamin/MagiskT"
    }

    /** Intent extra / bundle key names. */
    object Key {
        const val OPEN_SECTION = "section"
        const val PREV_CONFIG = "prev_config"
    }

    /** Values used to signal flash-action type in install intents. */
    object Value {
        const val FLASH_ZIP = "flash"
        const val PATCH_FILE = "patch"
        const val FLASH_MAGISK = "magisk"
        const val FLASH_INACTIVE_SLOT = "slot"
        const val UNINSTALL = "uninstall"
    }

    /** Bottom-navigation / Compose-destination identifiers. */
    object Nav {
        const val HOME = "home"
        const val SETTINGS = "settings"
        const val MODULES = "modules"
        const val SUPERUSER = "superuser"
    }
}
