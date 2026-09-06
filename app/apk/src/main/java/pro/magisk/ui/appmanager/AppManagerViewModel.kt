/**
 * ViewModel for the App manager screen.
 *
 * Loads all installed applications (excluding self) and classifies them as
 * user / system / core apps. App details (version, size, signature, install
 * time/source) are gathered on demand when an item is expanded.
 * Uninstall uses `pm uninstall` (with `pm uninstall --user 0` for system apps),
 * enable/disable uses `pm enable` / `pm disable-user`. A search query filters
 * the list by app name or package name.
 */
package pro.magisk.ui.appmanager

import android.annotation.SuppressLint
import android.content.pm.PackageManager.GET_SIGNATURES
import android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.os.Build
import android.view.View
import android.view.ViewParent
import androidx.databinding.Bindable
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView
import pro.magisk.BR
import pro.magisk.arch.AsyncLoadViewModel
import pro.magisk.arch.startAnimations
import pro.magisk.core.AppContext
import pro.magisk.core.R
import pro.magisk.core.ktx.concurrentMap
import pro.magisk.databinding.bindExtra
import pro.magisk.databinding.filterList
import pro.magisk.databinding.set
import pro.magisk.events.SnackbarEvent
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils.fastCmd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ViewModel for the App manager screen. */
class AppManagerViewModel : AsyncLoadViewModel() {

    private var expandedItem: AppManagerRvItem? = null

    val items = filterList<AppManagerRvItem>(viewModelScope)
    val extraBindings = bindExtra {
        it.put(BR.viewModel, this)
    }

    /** Search query filtering the list by app name or package name. */
    var query = ""
        set(value) {
            field = value
            doQuery()
        }

    /** Last scroll position, saved before a reload and restored afterwards (search off only). */
    var savedPos = 0

    /** Pixel offset of the saved scroll position. */
    var savedOffset = 0

    @get:Bindable
    var loading = true
        private set(value) = set(value, field, { field = it }, BR.loading)

    @SuppressLint("InlinedApi")
    override suspend fun doLoadWork() {
        loading = true
        expandedItem = null
        val apps = withContext(Dispatchers.Default) {
            val pm = AppContext.packageManager
            val apps = pm.getInstalledApplications(MATCH_UNINSTALLED_PACKAGES).run {
                asFlow()
                    .filter { AppContext.packageName != it.packageName }
                    .concurrentMap { AppManagerRvItem(it, pm) }
                    .toCollection(ArrayList(size))
            }
            apps.sort()
            apps
        }
        items.set(apps)
        doQuery()
        loading = false
    }

    /** Refilters the visible list by the current search query. */
    private fun doQuery() {
        val q = query
        items.filter { it.label.contains(q, true) || it.packageName.contains(q, true) }
    }

    /** Expands the tapped item (collapsing any other expanded one) with a fast animation. */
    fun onItemClick(item: AppManagerRvItem, v: View) {
        var parent: ViewParent? = v.parent
        while (parent != null && parent !is RecyclerView) parent = parent.parent
        (parent as? RecyclerView)?.startAnimations(FAST_ANIMATION_DURATION)
        if (expandedItem != item) {
            expandedItem?.isExpanded = false
            expandedItem = item
            item.isExpanded = true
            if (item.detail == null) loadDetail(item)
        } else {
            item.isExpanded = false
            expandedItem = null
        }
    }

    /** Lazily gathers app detail into the item once it is expanded. */
    private fun loadDetail(item: AppManagerRvItem) {
        viewModelScope.launch {
            item.detail = withContext(Dispatchers.IO) { gatherDetail(item) }
        }
    }

    fun uninstall(item: AppManagerRvItem) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                val pkg = item.packageName
                val (primary, fallback) = if (item.needsUserUninstall) {
                    "pm uninstall --user 0 $pkg" to "pm uninstall $pkg"
                } else {
                    "pm uninstall $pkg" to "pm uninstall --user 0 $pkg"
                }
                Shell.cmd(primary).exec().isSuccess || Shell.cmd(fallback).exec().isSuccess
            }
            SnackbarEvent(if (success) R.string.app_manager_uninstall_success
                else R.string.app_manager_uninstall_failed).publish()
            if (success) reload()
        }
    }

    /** Enables or disables the app depending on its current state. */
    fun toggleEnabled(item: AppManagerRvItem) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                val cmd = if (item.isDisabled) "pm enable ${item.packageName}"
                    else "pm disable-user ${item.packageName}"
                Shell.cmd(cmd).exec().isSuccess
            }
            val res = when {
                success && item.isDisabled -> R.string.app_manager_enable_success
                success -> R.string.app_manager_disable_success
                item.isDisabled -> R.string.app_manager_enable_failed
                else -> R.string.app_manager_disable_failed
            }
            SnackbarEvent(res).publish()
            if (success) reload()
        }
    }

    private fun reload() {
        viewModelScope.launch { doLoadWork() }
    }

    /** Collects app detail fields for the info dialog. */
    @SuppressLint("InlinedApi")
    private fun gatherDetail(item: AppManagerRvItem): AppDetail {
        val pm = AppContext.packageManager
        val pkg = item.packageName
        val pkgInfo = runCatching {
            pm.getPackageInfo(pkg, GET_SIGNING_CERTIFICATES or MATCH_UNINSTALLED_PACKAGES)
        }.getOrNull() ?: runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, GET_SIGNATURES or MATCH_UNINSTALLED_PACKAGES)
        }.getOrNull()

        return AppDetail(
            version = pkgInfo?.let {
                buildString {
                    append(it.versionName ?: "")
                    if (it.versionCode > 0) append(" (${it.versionCode})")
                }
            }.takeIf { !it.isNullOrBlank() } ?: "?",
            size = appSize(item),
            signature = signature(pkg),
            installTime = pkgInfo?.firstInstallTime
                ?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it)) }
                ?: "?",
            installSource = installSource(pkg)
        )
    }

    private fun appSize(item: AppManagerRvItem): String {
        val shell = Shell.getShell()
        val dirs = listOf(item.sourceDir, item.dataDir)
            .filter { it.isNotBlank() }
            .joinToString(" ") { "'$it'" }
        if (dirs.isEmpty()) return "?"
        val out = runCatching { fastCmd(shell, "du -sk $dirs 2>/dev/null") }.getOrNull().orEmpty()
        val kb = out.lines().mapNotNull { line ->
            line.trim().substringBefore('\t').toLongOrNull()
        }.sum()
        return formatBytes(kb * 1024)
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        return when {
            kb >= 1024 -> String.format(Locale.US, "%.1f MB", kb / 1024)
            kb >= 1 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun signature(pkg: String): String {
        val pm = AppContext.packageManager
        val bytes = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(pkg, GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
            }
        }.getOrNull() ?: return "?"
        return runCatching {
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString(":") { "%02X".format(it) }
        }.getOrDefault("?")
    }

    private fun installSource(pkg: String): String {
        val pm = AppContext.packageManager
        val source = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pkg)
            }
        }.getOrNull()
        return source?.takeIf { it.isNotBlank() } ?: "?"
    }

    companion object {
        private const val FAST_ANIMATION_DURATION = 150L
    }
}
