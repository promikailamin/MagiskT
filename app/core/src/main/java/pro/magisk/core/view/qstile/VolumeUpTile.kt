/**
 * Quick Settings tile that simulates pressing the physical volume-up
 * button via `input keyevent KEYCODE_VOLUME_UP` in the root shell.
 */
package pro.magisk.core.view.qstile

import android.view.KeyEvent

class VolumeUpTile : VolumeTile() {
    override val keyCode = KeyEvent.KEYCODE_VOLUME_UP
}