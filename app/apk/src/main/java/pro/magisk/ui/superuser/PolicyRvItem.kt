/**
 * RecyclerView item for a Superuser policy entry.
 *
 * Displays the app icon, name (prefixes "[SharedUID]" for shared-UID apps), and
 * expandable details with toggles for notification, logging, and a three-position
 * slider (Deny / Restrict / Allow). Changes are dispatched to [SuperuserViewModel].
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
import pro.magisk.databinding.set
import pro.magisk.core.R as CoreR

/** A single Superuser policy entry with action toggles and a policy slider. */
class PolicyRvItem(
    private val view_model: SuperuserViewModel,
    override val item: SuPolicy,
    val packageName: String,
    private val is_shared_uid: Boolean,
    val icon: Drawable,
    val app_name: String
) : ObservableRvItem(), DiffItem<PolicyRvItem>, ItemWrapper<SuPolicy> {

    override val layout_res = R.layout.item_policy_md2

    val title get() = if (is_shared_uid) "[SharedUID] $app_name" else app_name

    private inline fun <reified T> setImpl(new: T, old: T, setter: (T) -> Unit) {
        if (old != new) {
            setter(new)
        }
    }

    @get:Bindable
    var is_expanded = false
        set(value) = set(value, field, { field = it }, BR.expanded)

    val show_slider = Config.su_restrict || item.policy == SuPolicy.RESTRICT

    @get:Bindable
    var isEnabled
        get() = item.policy >= SuPolicy.ALLOW
        set(value) = setImpl(value, isEnabled) {
            notifyPropertyChanged(BR.enabled)
            view_model.update_policy(this, if (it) SuPolicy.ALLOW else SuPolicy.DENY)
        }

    @get:Bindable
    var slider_value
        get() = item.policy
        set(value) = setImpl(value, slider_value) {
            notifyPropertyChanged(BR.slider_value)
            notifyPropertyChanged(BR.enabled)
            view_model.update_policy(this, it)
        }

    val slider_value_to_policy_string: (Float) -> Int = { value ->
        when (value.toInt()) {
            1 -> CoreR.string.deny
            2 -> CoreR.string.restrict
            3 -> CoreR.string.grant
            else -> CoreR.string.deny
        }
    }

    @get:Bindable
    var should_notify
        get() = item.notification
        private set(value) = setImpl(value, should_notify) {
            item.notification = it
            view_model.update_notify(this)
        }

    @get:Bindable
    var should_log
        get() = item.logging
        private set(value) = setImpl(value, should_log) {
            item.logging = it
            view_model.update_logging(this)
        }

    fun toggle_expand() {
        is_expanded = !is_expanded
    }

    fun toggle_notify() {
        should_notify = !should_notify
    }

    fun toggle_log() {
        should_log = !should_log
    }

    fun revoke() {
        view_model.delete_pressed(this)
    }

    override fun item_same_as(other: PolicyRvItem) = packageName == other.packageName

    override fun content_same_as(other: PolicyRvItem) = item.policy == other.item.policy

}
