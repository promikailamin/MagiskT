/**
 * Base classes for Magisk Quick Settings tiles.
 *
 * Every tile disables itself ([Tile.STATE_UNAVAILABLE]) when the device
 * has no root access. Privileged settings toggles dispatch their
 * `settings put` commands through the libsu root shell; volume tiles
 * simulate the hardware volume keys via `input keyevent`.
 */
package pro.magisk.core.view.qstile

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils

/**
 * Base Quick Settings tile.
 *
 * Tiles are rendered as [Tile.STATE_UNAVAILABLE] unless the device is
 * rooted ([Shell.isAppGrantedRoot]). Subclasses implement [handleClick]
 * to perform the tile action; the action only runs when root is present.
 */
@RequiresApi(Build.VERSION_CODES.N)
abstract class QSTile : TileService() {

    /** State rendered while root is available. */
    protected open val activeState: Int = Tile.STATE_ACTIVE

    override fun onStartListening() {
        refreshState()
    }

    override fun onClick() {
        if (!isRooted()) {
            refreshState()
            return
        }
        handleClick()
    }

    /** Whether the device currently grants root to the app. */
    protected fun isRooted() = Shell.isAppGrantedRoot() == true

    /** Perform the tile action (only invoked when root is available). */
    protected abstract fun handleClick()

    /** Re-evaluate the tile appearance. Must be called on the main thread. */
    protected open fun refreshState() {
        val tile = qsTile ?: return
        tile.state = if (isRooted()) activeState else Tile.STATE_UNAVAILABLE
        tile.updateTile()
    }
}

/** Run [block] on a background thread. */
private fun background(block: () -> Unit) {
    Thread(block).start()
}

/** Run [block] on the main thread, ignoring stale callbacks. */
private fun main(block: () -> Unit) {
    Handler(Looper.getMainLooper()).post {
        try {
            block()
        } catch (e: Throwable) {
            // Service may have been destroyed before the callback ran.
        }
    }
}

/**
 * Toggle tile backed by a system global setting, e.g. USB debugging or
 * developer options. The tile reflects the live setting value and flips
 * it through the root shell.
 */
abstract class SettingToggleTile : QSTile() {

    /** Shell command that prints "1"/"0" for the current setting. */
    protected abstract val readCmd: String

    /** Shell command that sets the setting to the given state. */
    protected abstract val writeCmd: (Boolean) -> String

    override fun refreshState() {
        if (!isRooted()) {
            super.refreshState()
            return
        }
        background {
            val active = readState()
            main { setState(active) }
        }
    }

    override fun handleClick() {
        background {
            val active = !readState()
            Shell.cmd(writeCmd(active)).submit()
            main { setState(active) }
        }
    }

    private fun readState(): Boolean =
        runCatching { ShellUtils.fastCmd(Shell.getShell(), readCmd) == "1" }.getOrDefault(false)

    private fun setState(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}

/**
 * One-shot tile that simulates a hardware volume key press through the
 * root shell (`input keyevent`), like the physical volume rocker.
 */
abstract class VolumeTile : QSTile() {

    /** [android.view.KeyEvent.KEYCODE_VOLUME_UP] or `KEYCODE_VOLUME_DOWN`. */
    protected abstract val keyCode: Int

    override fun handleClick() {
        Shell.cmd("input keyevent $keyCode").submit()
    }
}