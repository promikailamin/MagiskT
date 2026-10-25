/**
 * Base classes for Magisk Quick Settings tiles.
 *
 * Every tile disables itself ([Tile.STATE_UNAVAILABLE]) when the device
 * has no root access. Privileged settings toggles dispatch their read and
 * write commands through the libsu root shell.
 */
package pro.magisk.core.view.qstile

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.topjohnwu.superuser.Shell
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
        if (!isAvailable()) {
            refreshState()
            return
        }
        handleClick()
    }

    /** Whether the device currently grants root to the app. */
    protected fun isRooted() = Shell.isAppGrantedRoot() == true

    protected open fun isAvailable() = isRooted()

    /** Perform the tile action (only invoked when [isAvailable]). */
    protected abstract fun handleClick()

    /** Re-evaluate the tile appearance. Must be called on the main thread. */
    protected open fun refreshState() {
        val tile = qsTile ?: return
        tile.state = if (isAvailable()) activeState else Tile.STATE_UNAVAILABLE
        tile.updateTile()
    }
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

    /** Parse the output of [readCmd] into a boolean. */
    protected open fun parseState(output: String) = output.trim() == "1"

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun refreshState() {
        if (!isAvailable()) {
            super.refreshState()
            return
        }
        val gen = generation.get()
        post {
            val active = readState() ?: return@post
            main {
                if (gen == generation.get()) setState(active)
            }
        }
    }

    override fun handleClick() {
        val tile = qsTile ?: return
        val previous = tile.state == Tile.STATE_ACTIVE
        val desired = !previous
        val gen = generation.incrementAndGet()

        setState(desired)

        post {
            val written = writeState(desired)
            val actual = readState()
            main {
                if (gen != generation.get()) return@main
                when {
                    actual != null -> setState(actual)
                    written -> setState(desired)
                    else -> setState(previous)
                }
            }
        }
    }

    private fun post(block: () -> Unit) {
        runCatching { io.execute(block) }
    }

    private fun readState(): Boolean? = runCatching {
        val result = Shell.cmd(readCmd).exec()
        if (!result.isSuccess) return@runCatching null
        parseState(result.out.joinToString("\n"))
    }.getOrNull()

    private fun writeState(active: Boolean): Boolean = runCatching {
        Shell.cmd(writeCmd(active)).exec().isSuccess
    }.getOrDefault(false)

    private fun setState(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
