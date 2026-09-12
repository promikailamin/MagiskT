/**
 * Quick Settings tile that raises the media volume like the physical
 * volume-up button.
 */
package pro.magisk.core.view.qstile

import android.media.AudioManager

class VolumeUpTile : VolumeTile() {
    override val direction = AudioManager.ADJUST_RAISE
}