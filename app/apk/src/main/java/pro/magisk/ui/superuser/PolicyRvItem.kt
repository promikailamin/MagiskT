/**
 * RecyclerView item for a Superuser policy entry.
 *
 * Displays the app icon, name (prefixes "[SharedUID]" for shared-UID apps), and a
 * quick access-policy control on the right (a Deny/Allow switch, or a
 * Deny/Restrict/Allow slider when restriction is available). Tapping the row
 * opens the SU app detail screen; long-pressing toggles the locked state.
 */
package pro.magisk.ui.superuser

import android.graphics.drawable.Drawable
import androidx.databinding.Bindable
import pro.magisk.BR
import pro.magisk.R
import pro.magisk.core.Config
import pro.magisk.core.model.su.SuPolicy
import pro.magisk.databinding.DiffItem
import pro.magisk.databinding.ItemWrapper
import pro.magisk.databinding.ObservableRvItem
import pro.magisk.core.R as CoreR

/** A single Superuser policy entry with a quick policy control. */
class PolicyRvItem(
    private val viewModel: SuperuserViewModel,
    override val item: SuPolicy,
    val packageName: String,
    private val isSharedUid: Boolean,
    val icon: Drawable,
    val appName: String
) : ObservableRvItem(), DiffItem<PolicyRvItem>, ItemWrapper<SuPolicy> {

    override val layoutRes = R.layout.item_policy_md2

    @get:Bindable
    val title
        get() = (if (isSharedUid) "[SharedUID] $appName" else appName)
            .let { if (item.locked) "🔒 $it" else it }

    private inline fun <reified T> setImpl(new: T, old: T, setter: (T) -> Unit) {
        if (old != new) {
            setter(new)
        }
    }

    val showSlider = Config.suRestrict || item.policy == SuPolicy.RESTRICT

    @get:Bindable
    var isEnabled
        get() = item.policy >= SuPolicy.ALLOW
        set(value) = setImpl(value, isEnabled) {
            notifyPropertyChanged(BR.enabled)
            viewModel.updatePolicy(this, if (it) SuPolicy.ALLOW else SuPolicy.DENY)
        }

    @get:Bindable
    var sliderValue
        get() = item.policy
        set(value) = setImpl(value, sliderValue) {
            notifyPropertyChanged(BR.sliderValue)
            notifyPropertyChanged(BR.enabled)
            viewModel.updatePolicy(this, it)
        }

    val sliderValueToPolicyString: (Float) -> Int = { value ->
        when (value.toInt()) {
            1 -> CoreR.string.deny
            2 -> CoreR.string.restrict
            3 -> CoreR.string.grant
            else -> CoreR.string.deny
        }
    }

    @get:Bindable
    var shouldLock
        get() = item.locked
        private set(value) = setImpl(value, shouldLock) {
            item.locked = it
            notifyPropertyChanged(BR.title)
            viewModel.updateLocked(this)
        }

    fun toggleLock() {
        shouldLock = !shouldLock
    }

    /** Opens the SU app detail screen for this entry. */
    fun openDetail() {
        viewModel.openDetail(this)
    }

    override fun itemSameAs(other: PolicyRvItem) = packageName == other.packageName

    override fun contentSameAs(other: PolicyRvItem) =
        item.policy == other.item.policy && item.locked == other.item.locked

}