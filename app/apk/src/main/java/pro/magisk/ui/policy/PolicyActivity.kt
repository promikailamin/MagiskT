package pro.magisk.ui.policy

import android.content.pm.PackageManager
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.snackbar.Snackbar
import pro.magisk.R
import pro.magisk.core.Config
import pro.magisk.core.Info
import pro.magisk.core.AppContext
import pro.magisk.core.base.ActivityExtension
import pro.magisk.core.base.IActivityExtension
import pro.magisk.core.data.magiskdb.PolicyDao
import pro.magisk.core.di.ServiceLocator
import pro.magisk.core.ktx.getLabel
import pro.magisk.core.model.su.SuPolicy
import pro.magisk.core.su.SuEvents
import pro.magisk.core.R as CoreR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class PolicyActivity : AppCompatActivity(), IActivityExtension {

    data class PolicyItem(
        val policy: SuPolicy,
        val packageName: String,
        val appName: CharSequence,
        val icon: Drawable,
        val isSharedUid: Boolean
    ) {
        val title: CharSequence
            get() = if (isSharedUid) "[SharedUID] $appName" else appName
    }

    private val policyDB: PolicyDao = ServiceLocator.policyDB
    override val extension = ActivityExtension(this)

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingContainer: View
    private lateinit var emptyText: TextView

    private val policies = mutableListOf<PolicyItem>()
    private val adapter = PolicyAdapter()

    init {
        AppCompatDelegate.setDefaultNightMode(Config.darkTheme)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_policy)

        extension.onCreate(savedInstanceState)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(CoreR.string.superuser)

        recyclerView = findViewById(R.id.policy_list)
        loadingContainer = findViewById(R.id.loading_container)
        emptyText = findViewById(R.id.empty_text)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadPolicies()

        lifecycleScope.launch {
            SuEvents.policyChanged.collectLatest { loadPolicies() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        extension.onSaveInstanceState(outState)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadPolicies() {
        lifecycleScope.launch {
            loadingContainer.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            emptyText.visibility = View.GONE

            val items = withContext(Dispatchers.IO) { loadPolicyItems() }

            policies.clear()
            policies.addAll(items)
            adapter.notifyDataSetChanged()

            loadingContainer.visibility = View.GONE
            if (items.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyText.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun loadPolicyItems(): List<PolicyItem> {
        if (!Info.showSuperUser) return emptyList()

        policyDB.deleteOutdated()
        policyDB.delete(AppContext.applicationInfo.uid)

        val result = mutableListOf<PolicyItem>()
        val pm = AppContext.packageManager

        for (policy in policyDB.fetchAll()) {
            val pkgs = if (policy.uid == Process.SYSTEM_UID) arrayOf("android")
            else pm.getPackagesForUid(policy.uid)

            if (pkgs == null) {
                policyDB.delete(policy.uid)
                continue
            }

            val maps = pkgs.mapNotNull { pkg ->
                try {
                    val info = pm.getPackageInfo(pkg, MATCH_UNINSTALLED_PACKAGES)
                    PolicyItem(
                        policy = policy,
                        packageName = info.packageName,
                        appName = info.applicationInfo?.getLabel(pm) ?: info.packageName,
                        icon = info.applicationInfo?.loadIcon(pm) ?: pm.defaultActivityIcon,
                        isSharedUid = info.sharedUserId != null
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }

            if (maps.isEmpty()) {
                policyDB.delete(policy.uid)
                continue
            }
            result.addAll(maps)
        }

        result.sortWith(compareBy(
            { it.appName.toString().lowercase(Locale.ROOT) },
            { it.packageName }
        ))
        return result
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(recyclerView, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun updatePolicy(item: PolicyItem, newPolicy: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            item.policy.policy = newPolicy
            policyDB.update(item.policy)
            SuEvents.notifyPolicyChanged()
        }
        val snackRes = if (newPolicy >= SuPolicy.ALLOW) CoreR.string.su_snack_grant
        else CoreR.string.su_snack_deny
        showSnackbar(getString(snackRes, item.title))
    }

    private fun updateNotify(item: PolicyItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            item.policy.notification = !item.policy.notification
            policyDB.update(item.policy)
            SuEvents.notifyPolicyChanged()
        }
        val res = if (item.policy.notification) CoreR.string.su_snack_notif_on
        else CoreR.string.su_snack_notif_off
        showSnackbar(getString(res, item.title))
    }

    private fun updateLogging(item: PolicyItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            item.policy.logging = !item.policy.logging
            policyDB.update(item.policy)
            SuEvents.notifyPolicyChanged()
        }
        val res = if (item.policy.logging) CoreR.string.su_snack_log_on
        else CoreR.string.su_snack_log_off
        showSnackbar(getString(res, item.title))
    }

    private fun deletePolicy(item: PolicyItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            policyDB.delete(item.policy.uid)
            SuEvents.notifyPolicyChanged()
        }
    }

    private fun confirmAndDelete(item: PolicyItem) {
        val onSuccess = {
            if (Config.suAuth) {
                withAuthentication { success ->
                    if (success) deletePolicy(item)
                }
            } else {
                AlertDialog.Builder(this)
                    .setTitle(CoreR.string.su_revoke_title)
                    .setMessage(getString(CoreR.string.su_revoke_msg, item.title))
                    .setPositiveButton(android.R.string.ok) { _, _ -> deletePolicy(item) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        onSuccess()
    }

    private fun toggleExpand(viewHolder: PolicyAdapter.ViewHolder) {
        viewHolder.isExpanded = !viewHolder.isExpanded
        viewHolder.expandContainer.visibility = if (viewHolder.isExpanded) View.VISIBLE else View.GONE
    }

    inner class PolicyAdapter : RecyclerView.Adapter<PolicyAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_policy, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = policies[position]
            holder.bind(item)
        }

        override fun getItemCount() = policies.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val card: MaterialCardView = itemView.findViewById(R.id.policy_card)
            val appIcon: ImageView = itemView.findViewById(R.id.app_icon)
            val appName: TextView = itemView.findViewById(R.id.app_name)
            val packageNameView: TextView = itemView.findViewById(R.id.package_name)
            val policySwitch: SwitchMaterial = itemView.findViewById(R.id.policy_switch)
            val policySlider: Slider = itemView.findViewById(R.id.policy_slider)
            val mainRow: View = itemView.findViewById(R.id.main_row)
            val expandContainer: View = itemView.findViewById(R.id.expand_container)
            val btnNotify: Button = itemView.findViewById(R.id.btn_notify)
            val btnLog: Button = itemView.findViewById(R.id.btn_log)
            val btnRevoke: Button = itemView.findViewById(R.id.btn_revoke)

            var isExpanded = false

            init {
                mainRow.setOnClickListener {
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION) toggleExpand(this)
                }

                policySwitch.setOnCheckedChangeListener { _, isChecked ->
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val it = policies[pos]
                        updatePolicy(it, if (isChecked) SuPolicy.ALLOW else SuPolicy.DENY)
                    }
                }

                policySlider.addOnChangeListener { _, value, fromUser ->
                    if (fromUser) {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            updatePolicy(policies[pos], value.toInt())
                        }
                    }
                }

                btnNotify.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) updateNotify(policies[pos])
                }

                btnLog.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) updateLogging(policies[pos])
                }

                btnRevoke.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) confirmAndDelete(policies[pos])
                }
            }

            fun bind(item: PolicyItem) {
                appIcon.setImageDrawable(item.icon)
                appName.text = item.title
                packageNameView.text = item.packageName

                val showSlider = Config.suRestrict || item.policy.policy == SuPolicy.RESTRICT
                policySwitch.visibility = if (showSlider) View.GONE else View.VISIBLE
                policySlider.visibility = if (showSlider) View.VISIBLE else View.GONE

                policySlider.value = item.policy.policy.toFloat()
                policySlider.setLabelFormatter { value ->
                    when (value.toInt()) {
                        SuPolicy.DENY -> getString(CoreR.string.deny)
                        SuPolicy.RESTRICT -> getString(CoreR.string.restrict)
                        SuPolicy.ALLOW -> getString(CoreR.string.grant)
                        else -> getString(CoreR.string.deny)
                    }
                }

                policySwitch.setOnCheckedChangeListener(null)
                policySwitch.isChecked = item.policy.policy >= SuPolicy.ALLOW
                policySwitch.setOnCheckedChangeListener { _, isChecked ->
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        updatePolicy(policies[pos], if (isChecked) SuPolicy.ALLOW else SuPolicy.DENY)
                    }
                }

                card.alpha = if (item.policy.policy >= SuPolicy.ALLOW) 1f else 0.5f
                btnNotify.isSelected = item.policy.notification
                btnLog.isSelected = item.policy.logging
            }
        }
    }
}
