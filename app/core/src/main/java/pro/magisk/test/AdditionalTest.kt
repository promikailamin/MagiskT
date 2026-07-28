/**
 * Additional integration tests that run after {@link Environment} has set up
 * the test environment. Validates Magisk module functionality:
 *
 * <ul>
 *   <li>Module count matches expected</li>
 *   <li>LSPosed manager launches correctly</li>
 *   <li>Mount test: files added/replaced/deleted via magic mount</li>
 *   <li>Sepolicy rule applied</li>
 *   <li>Empty and invalid zygisk modules are unloaded</li>
 *   <li>Module removal (uninstaller script executed)</li>
 *   <li>Module upgrade (files from old + new module)</li>
 * </ul>
 */
package pro.magisk.test

import android.os.ParcelFileDescriptor.AutoCloseInputStream
import androidx.annotation.Keep
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import pro.magisk.core.model.module.LocalModule
import pro.magisk.core.utils.RootUtils
import pro.magisk.test.Environment.Companion.EMPTY_ZYGISK
import pro.magisk.test.Environment.Companion.INVALID_ZYGISK
import pro.magisk.test.Environment.Companion.MOUNT_TEST
import pro.magisk.test.Environment.Companion.REMOVE_TEST
import pro.magisk.test.Environment.Companion.SEPOLICY_RULE
import pro.magisk.test.Environment.Companion.UPGRADE_TEST
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

@Keep
@RunWith(AndroidJUnit4::class)
class AdditionalTest : BaseTest {

    companion object {
        private const val SHELL_PKG = "com.android.shell"
        private const val LSPOSED_CATEGORY = "org.lsposed.manager.LAUNCH_MANAGER"
        private const val LSPOSED_PKG = "org.lsposed.manager"

        private lateinit var modules: List<LocalModule>

        @BeforeClass
        @JvmStatic
        fun before() {
            BaseTest.prerequisite()
            runBlocking {
                modules = LocalModule.installed()
            }
        }
    }

    /** Press home after each test to reset the UI state. */
    @After
    fun teardown() {
        device.pressHome()
    }

    /** Verifies the expected number of installed modules. */
    @Test
    fun testModuleCount() {
        var expected = 4
        if (Environment.mount()) expected++
        if (Environment.preinit()) expected++
        if (Environment.lsposed()) expected++
        if (Environment.shamiko()) expected++
        assertEquals("Module count incorrect", expected, modules.size)
    }

    /** Launches the LSPosed manager and verifies it appears on screen. */
    @Test
    fun testLsposed() {
        assumeTrue(Environment.lsposed())

        val module = modules.find { it.id == "zygisk_lsposed" }
        assertNotNull("zygisk_lsposed is not installed", module)
        module!!
        assertFalse("zygisk_lsposed is not enabled", module.zygiskUnloaded)

        // Launch lsposed manager to ensure the module is active
        uiAutomation.executeShellCommand(
            "am start -c $LSPOSED_CATEGORY $SHELL_PKG/.BugreportWarningActivity"
        ).let { pfd -> AutoCloseInputStream(pfd).use { it.readBytes() } }

        val pattern = Pattern.compile("$LSPOSED_PKG:id/.*")
        assertNotNull(
            "LSPosed manager launch failed",
            device.wait(Until.hasObject(By.res(pattern)), TimeUnit.SECONDS.toMillis(10))
        )
    }

    /** Verifies magic mount: new file exists, deleted file gone, directory replaced. */
    @Test
    fun testModuleMount() {
        assumeTrue(Environment.mount())

        assertNotNull("$MOUNT_TEST is not installed", modules.find { it.id == MOUNT_TEST })
        assertTrue(
            "/system/fonts/newfile should exist",
            RootUtils.fs.getFile("/system/fonts/newfile").exists()
        )
        assertFalse(
            "/system/bin/screenrecord should not exist",
            RootUtils.fs.getFile("/system/bin/screenrecord").exists()
        )
        val egg = RootUtils.fs.getFile("/system/app/EasterEgg").list() ?: arrayOf()
        assertArrayEquals(
            "/system/app/EasterEgg should be replaced",
            egg,
            arrayOf("newfile")
        )
    }

    /** Verifies that a module's sepolicy.rule was applied at boot. */
    @Test
    fun testSepolicyRule() {
        assumeTrue(Environment.preinit())

        assertNotNull("$SEPOLICY_RULE is not installed", modules.find { it.id == SEPOLICY_RULE })
        assertTrue(
            "Module sepolicy.rule is not applied",
            Shell.cmd("magiskpolicy --print-rules | grep -q magisk_test").exec().isSuccess
        )
    }

    /** Verifies that a module with an empty zygisk folder is unloaded. */
    @Test
    fun testEmptyZygiskModule() {
        val module = modules.find { it.id == EMPTY_ZYGISK }
        assertNotNull("$EMPTY_ZYGISK is not installed", module)
        module!!
        assertTrue("$EMPTY_ZYGISK should be zygisk unloaded", module.zygiskUnloaded)
    }

    /** Verifies that a module with invalid zygisk libraries is unloaded. */
    @Test
    fun testInvalidZygiskModule() {
        val module = modules.find { it.id == INVALID_ZYGISK }
        assertNotNull("$INVALID_ZYGISK is not installed", module)
        module!!
        assertTrue("$INVALID_ZYGISK should be zygisk unloaded", module.zygiskUnloaded)
    }

    /** Verifies that a module marked for removal was removed and its uninstaller script ran. */
    @Test
    fun testRemoveModule() {
        assertNull("$REMOVE_TEST should be removed", modules.find { it.id == REMOVE_TEST })
        assertTrue(
            "Uninstaller of $REMOVE_TEST should be run",
            RootUtils.fs.getFile(Environment.REMOVE_TEST_MARKER).exists()
        )
    }

    /** Verifies that a module upgrade merges files from old and new versions correctly. */
    @Test
    fun testModuleUpgrade() {
        val module = modules.find { it.id == UPGRADE_TEST }
        assertNotNull("$UPGRADE_TEST is not installed", module)
        module!!
        assertFalse("$UPGRADE_TEST should be disabled", module.enable)
        assertTrue(
            "$UPGRADE_TEST should be updated",
            module.base.getChildFile("post-fs-data.sh").exists()
        )
        assertFalse(
            "$UPGRADE_TEST should be updated",
            module.base.getChildFile("service.sh").exists()
        )
    }
}
