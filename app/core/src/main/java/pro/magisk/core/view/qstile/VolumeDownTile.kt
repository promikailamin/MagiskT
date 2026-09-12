/**
 * Quick Settings tile that lowers the media volume like the physical
 * volume-down button.
 */
package pro.magisk.core.view.qstile

import android.media.AudioManager

class VolumeDownTile : VolumeTile() {
    override val direction = AudioManager.ADJUST_LOWER
}