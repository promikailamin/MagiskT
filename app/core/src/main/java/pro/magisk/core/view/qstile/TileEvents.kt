/**
 * In-process events emitted by Quick Settings tiles.
 *
 * Tiles run in the same process as the manager app, so a plain observable is
 * enough to let open screens react to changes made from the QS panel without
 * waiting for an activity pause/resume cycle.
 */
package pro.magisk.core.view.qstile

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/** Notifications for setting changes performed outside the manager UI. */
object TileEvents {

    private val _settingChanged = MutableLiveData<Unit>()

    /** Emits whenever a tile successfully changes one of its settings. */
    val settingChanged: LiveData<Unit> get() = _settingChanged

    /** Publish a setting change to any listening screen. */
    fun notifySettingChanged() {
        _settingChanged.postValue(Unit)
    }
}
