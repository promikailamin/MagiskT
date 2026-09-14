/**
 * Quick Settings tile that simulates pressing the physical volume-down
 * button via `input keyevent KEYCODE_VOLUME_DOWN` in the root shell.
 */
package pro.magisk.core.view.qstile

import android.view.KeyEvent

class VolumeDownTile : VolumeTile() {
    override val keyCode = KeyEvent.KEYCODE_VOLUME_DOWN
}