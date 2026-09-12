/**
 * Base classes for Magisk Quick Settings tiles.
 *
 * Every tile disables itself ([Tile.STATE_UNAVAILABLE]) when the device
 * has no root access. Privileged settings toggles dispatch their
 * `settings put` commands through the libsu root shell; volume tiles
 * adjust the media stream through [AudioManager].
 */
package pro.magisk.core.view.qstile

import android.content.Context
import android.media.AudioManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import pro.magisk.core.view.qstile.QSTile.Companion.background
import pro.magisk.core.view.qstile.QSTile.Companion.main

/**
 * Base Quick Settings tile.
 *
 * Tiles are rendered as [Tile.STATE_UNAVAILABLE] unless the device is
 * rooted ([Shell.isAppGrantedRoot]). Subclasses implement [handleClick]
 * to perform the tile action; the action only runs when root is present.
 */
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

    /** Perform the tile action (only invoked when root is available). */
    protected abstract fun handleClick()

    /** Re-evaluate the tile appearance. Must be called on the main thread. */
    protected open fun refreshState() {
        val tile = qsTile ?: return
        tile.state = if (isRooted()) activeState else Tile.STATE_UNAVAILABLE
        tile.updateTile()
    }

    companion object {
        /** Run [block] on a background thread. */
        fun background(block: () -> Unit) {
            Thread(block).start()
        }

        /** Run [block] on the calling [TileService]'s main thread. */
        fun main(service: TileService, block: () -> Unit) {
            service.runOnUiThread {
                try {
                    block()
                } catch (e: Throwable) {
                    // Service may have been destroyed before the callback ran.
                }
            }
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
            main(this@SettingToggleTile) { setState(active) }
        }
    }

    override fun handleClick() {
        background {
            val active = !readState()
            Shell.cmd(writeCmd(active)).submit()
            main(this@SettingToggleTile) { setState(active) }
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
 * One-shot tile that adjusts the media volume like the hardware volume
 * rocker. Root is still required so the tile obeys the global root-only
 * contract of every Magisk tile.
 */
abstract class VolumeTile : QSTile() {

    /** [AudioManager.ADJUST_RAISE] or [AudioManager.ADJUST_LOWER]. */
    protected abstract val direction: Int

    override fun handleClick() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND
        )
    }
}