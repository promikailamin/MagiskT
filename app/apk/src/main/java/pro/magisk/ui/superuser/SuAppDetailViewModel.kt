/**
 * ViewModel for the SU app detail screen.
 *
 * Loads the full app details (name, package, UID, version, install info) and the
 * Superuser policy for a single app, exposing the complete SU management UI:
 * access policy (Deny/Restrict/Allow), notifications, logging, lock, and revoke.
 * A locked policy following the package name is adopted/remapped if the app was
 * reinstalled under a new UID.
 */
package pro.magisk.ui.superuser

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.graphics.drawable.Drawable
import androidx.databinding.Bindable
import androidx.lifecycle.viewModelScope
import pro.magisk.BR
import pro.magisk.arch.AsyncLoadViewModel
import pro.magisk.core.AppContext
import pro.magisk.core.Config
import pro.magisk.core.data.magiskdb.PolicyDao
import pro.magisk.core.model.su.SuPolicy
import pro.magisk.core.utils.asText
import pro.magisk.databinding.set
import pro.magisk.dialog.SuperuserRevokeDialog
import pro.magisk.events.AuthEvent
import pro.magisk.events.SnackbarEvent
import pro.magisk.ui.appmanager.AppDetail
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils.fastCmd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import pro.magisk.core.R as CoreR

/** ViewModel for the SU app detail screen. */
class SuAppDetailViewModel(
    private val db: PolicyDao
) : AsyncLoadViewModel() {

    lateinit var args: SuAppDetailFragmentArgs

    @get:Bindable
    var loading = true
        private set(value) = set(value, field, { field = it }, BR.loading)

    private var sourceDir = ""
    private var dataDir = ""
    private var isSharedUid = false

    private var policy: SuPolicy? = null
    private var detail: AppDetail? = null

    @get:Bindable
    var icon: Drawable = AppContext.packageManager.defaultActivityIcon
        private set

    val packageName get() = args.packageName
    val appName get() = args.appName
    val uid get() = args.uid

    @get:Bindable
    val title
        get() = (if (isSharedUid) "[SharedUID] $appName" else appName)
            .let { if (policy?.locked == true) "🔒 $it" else it }

    val infoName get() = appName
    val infoPackage get() = packageName
    val infoUid get() = uid.toString()
    val infoVersion get() = detail?.version.orEmpty()
    val infoSize get() = detail?.size.orEmpty()
    val infoSignature get() = detail?.signature.orEmpty()
    val infoInstallTime get() = detail?.installTime.orEmpty()
    val infoInstallSource get() = detail?.installSource.orEmpty()
    val infoSourceDir get() = sourceDir
    val infoDataDir1 get() = "/storage/emulated/0/Android/data/$packageName"
    val infoDataDir2 get() = dataDir.ifBlank { "/data/data/$packageName" }

    val showSlider get() = Config.suRestrict || policy?.policy == SuPolicy.RESTRICT

    val sliderValueToPolicyString: (Float) -> Int = { value ->
        when (value.toInt()) {
            1 -> CoreR.string.deny
            2 -> CoreR.string.restrict
            3 -> CoreR.string.grant
            else -> CoreR.string.deny
        }
    }

    @get:Bindable
    var enabled
        get() = policy?.policy?.let { it >= SuPolicy.ALLOW } ?: false
        set(value) = updatePolicy(if (value) SuPolicy.ALLOW else SuPolicy.DENY)

    @get:Bindable
    var sliderValue
        get() = policy?.policy ?: SuPolicy.DENY
        set(value) = updatePolicy(value)

    @get:Bindable
    var shouldNotify
        get() = policy?.notification ?: true
        set(value) {
            if (value == shouldNotify) return
            updateFlag({ it.notification = value }, BR.shouldNotify,
                if (value) CoreR.string.su_snack_notif_on else CoreR.string.su_snack_notif_off)
        }

    @get:Bindable
    var shouldLog
        get() = policy?.logging ?: true
        set(value) {
            if (value == shouldLog) return
            updateFlag({ it.logging = value }, BR.shouldLog,
                if (value) CoreR.string.su_snack_log_on else CoreR.string.su_snack_log_off)
        }

    @get:Bindable
    var shouldLock
        get() = policy?.locked ?: false
        set(value) {
            if (value == shouldLock) return
            updateFlag({ it.locked = value }, BR.shouldLock,
                if (value) CoreR.string.su_snack_lock_on else CoreR.string.su_snack_lock_off)
        }

    /** Applies a boolean policy flag and persists it with a snackbar confirmation. */
    private fun updateFlag(apply: (SuPolicy) -> Unit, fieldId: Int, snackbarRes: Int) {
        val p = policy ?: return
        apply(p)
        notifyPropertyChanged(fieldId)
        viewModelScope.launch {
            db.update(p)
            SnackbarEvent(snackbarRes.asText(appName)).publish()
        }
    }

    /** Persists a new policy level, prompting for authentication when enabled. */
    fun updatePolicy(newPolicy: Int) {
        val p = policy ?: return
        if (p.policy == newPolicy) return
        fun updateState() = viewModelScope.launch {
            p.policy = newPolicy
            db.update(p)
            notifyPropertyChanged(BR.enabled)
            notifyPropertyChanged(BR.sliderValue)
            val res = if (newPolicy >= SuPolicy.ALLOW) CoreR.string.su_snack_grant
                else CoreR.string.su_snack_deny
            SnackbarEvent(res.asText(appName)).publish()
        }
        if (Config.suAuth) {
            AuthEvent { updateState() }.publish()
        } else {
            updateState()
        }
    }

    /** Revokes the Superuser rights of the app (locked entries must be unlocked first). */
    fun revoke() {
        val p = policy ?: return
        if (p.locked) {
            SnackbarEvent(CoreR.string.su_snack_revoke_locked.asText(appName)).publish()
            return
        }
        fun updateState() = viewModelScope.launch {
            db.delete(p.uid)
            back()
        }
        if (Config.suAuth) {
            AuthEvent { updateState() }.publish()
        } else {
            SuperuserRevokeDialog(appName) { updateState() }.show()
        }
    }

    private class LoadResult(
        val icon: Drawable,
        val sourceDir: String,
        val dataDir: String,
        val appInfo: ApplicationInfo?,
        val isSharedUid: Boolean,
        val policy: SuPolicy,
        val detail: AppDetail
    )

    override suspend fun doLoadWork() {
        loading = true
        val result = withContext(Dispatchers.IO) { load() }
        icon = result.icon
        sourceDir = result.sourceDir
        dataDir = result.dataDir
        isSharedUid = result.isSharedUid
        policy = result.policy
        detail = result.detail
        loading = false
        notifyChange()
        loadSize(result)
    }

    /** Computes the app size (a slow `du` shell walk) off the critical path. */
    private fun loadSize(result: LoadResult) {
        viewModelScope.launch(Dispatchers.IO) {
            val size = appSize(result.appInfo)
            withContext(Dispatchers.Main) {
                detail = detail?.copy(size = size)
                notifyChange()
            }
        }
    }

    @SuppressLint("InlinedApi")
    private suspend fun load(): LoadResult {
        val pm = AppContext.packageManager
        val appInfo = runCatching {
            pm.getApplicationInfo(packageName, MATCH_UNINSTALLED_PACKAGES)
        }.getOrNull()
        val pkgInfo = runCatching {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES or MATCH_UNINSTALLED_PACKAGES)
        }.getOrNull() ?: runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES or MATCH_UNINSTALLED_PACKAGES)
        }.getOrNull()

        var policy = db.fetch(uid)
        if (policy == null) {
            // The app may have been reinstalled: adopt a locked policy found by package
            db.fetchLockedByPackage(packageName)?.takeIf { it.uid != uid }?.let { locked ->
                db.remapUid(packageName, uid)
                locked.uid = uid
                policy = locked
            }
        }
        if (policy == null) policy = SuPolicy(uid)
        if (policy.packageName != packageName) {
            policy.packageName = packageName
            db.update(policy)
        }

        return LoadResult(
            icon = appInfo?.loadIcon(pm) ?: pm.defaultActivityIcon,
            sourceDir = appInfo?.publicSourceDir ?: appInfo?.sourceDir.orEmpty(),
            dataDir = appInfo?.dataDir.orEmpty(),
            appInfo = appInfo,
            isSharedUid = pkgInfo?.sharedUserId != null,
            policy = policy,
            detail = gatherDetail(pkgInfo)
        )
    }

    private fun gatherDetail(pkgInfo: PackageInfo?): AppDetail {
        val pkg = packageName
        return AppDetail(
            version = pkgInfo?.let {
                buildString {
                    append(it.versionName ?: "")
                    if (it.versionCode > 0) append(" (${it.versionCode})")
                }
            }.takeIf { !it.isNullOrBlank() } ?: "?",
            size = AppContext.getString(CoreR.string.loading),
            signature = signature(pkgInfo),
            installTime = pkgInfo?.firstInstallTime
                ?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it)) }
                ?: "?",
            installSource = installSource(pkg)
        )
    }

    private fun appSize(appInfo: ApplicationInfo?): String {
        val dirs = listOf(
            appInfo?.publicSourceDir ?: appInfo?.sourceDir.orEmpty(),
            appInfo?.dataDir.orEmpty()
        ).filter { it.isNotBlank() }
            .joinToString(" ") { "'$it'" }
        if (dirs.isEmpty()) return "?"
        val shell = Shell.getShell()
        val out = runCatching { fastCmd(shell, "du -sk $dirs 2>/dev/null") }.getOrNull().orEmpty()
        val kb = out.lines().mapNotNull { line ->
            line.trim().substringBefore('\t').toLongOrNull()
        }.sum()
        val bytes = kb * 1024
        val sizeKb = bytes / 1024.0
        return when {
            sizeKb >= 1024 -> String.format(Locale.US, "%.1f MB", sizeKb / 1024)
            sizeKb >= 1 -> String.format(Locale.US, "%.1f KB", sizeKb)
            else -> "$bytes B"
        }
    }

    private fun signature(pkgInfo: PackageInfo?): String {
        val bytes = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pkgInfo?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                pkgInfo?.signatures?.firstOrNull()?.toByteArray()
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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pkg)
            }
        }.getOrNull()
        return source?.takeIf { it.isNotBlank() } ?: "?"
    }
}