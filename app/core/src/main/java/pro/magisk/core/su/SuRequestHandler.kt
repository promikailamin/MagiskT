/**
 * Handles incoming SU request intents from the Magisk daemon.
 *
 * Extracts the UID, PID, and FIFO path from the intent, looks up
 * existing policy (or creates a new one), and either auto-responds
 * (deny / allow) or signals the UI that user interaction is needed.
 *
 * When the user responds, the decision is written to the FIFO and
 * persisted via [PolicyDao].
 */
package pro.magisk.core.su

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import pro.magisk.core.BuildConfig
import pro.magisk.core.Config
import pro.magisk.core.data.magiskdb.PolicyDao
import pro.magisk.core.di.ServiceLocator
import pro.magisk.core.ktx.getLabel
import pro.magisk.core.ktx.getPackageInfo
import pro.magisk.core.model.su.SuLog
import pro.magisk.core.model.su.SuPolicy
import pro.magisk.view.Notifications
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class SuRequestHandler(
    val pm: PackageManager,
    private val policyDB: PolicyDao
) {

    private lateinit var output: File
    private lateinit var policy: SuPolicy
    private var pid: Int = -1
    lateinit var pkgInfo: PackageInfo
        private set

    /**
     * Process the incoming SU request.
     * @return `true` if the policy is undetermined (user interaction needed).
     */
    suspend fun start(intent: Intent): Boolean {
        Timber.i("SuRequestHandler.start: intent=${intent.action}")
        if (!init(intent))
            return false

        if (pkgInfo.packageName == BuildConfig.APP_PACKAGE_NAME) {
            Timber.i("SuRequestHandler: self-uninstall request from pid=$pid, ignoring")
            Shell.cmd("(pm uninstall ${BuildConfig.APP_PACKAGE_NAME} >/dev/null 2>&1)&").exec()
            return false
        }

        when (Config.suAutoResponse) {
            Config.Value.SU_AUTO_DENY -> {
                Timber.i("SuRequestHandler: auto-deny (suAutoResponse)")
                respond(SuPolicy.DENY, 0)
                return false
            }
            Config.Value.SU_AUTO_ALLOW -> {
                Timber.i("SuRequestHandler: auto-allow (suAutoResponse)")
                respond(SuPolicy.ALLOW, 0)
                return false
            }
        }

        // A locked policy was adopted above; apply it directly (no prompt)
        if (policy.policy != SuPolicy.QUERY) {
            Timber.i("SuRequestHandler: applying locked policy=%d remain=%s", policy.policy, policy.remain)
            respond(policy.policy, policy.remain)
            return false
        }

        Timber.i("SuRequestHandler: policy undetermined, prompting user (uid=%d pkg=%s)",
            policy.uid, pkgInfo.packageName)
        return true
    }

    /** Parse the intent extras and look up the existing policy. */
    private suspend fun init(intent: Intent): Boolean {
        val uid = intent.getIntExtra("uid", -1)
        pid = intent.getIntExtra("pid", -1)
        val fifo = intent.getStringExtra("fifo")
        Timber.d("SuRequestHandler.init: uid=%d pid=%d fifo=%s", uid, pid, fifo)
        if (uid <= 0 || pid <= 0 || fifo == null) {
            Timber.e("Unexpected extras: uid=[${uid}], pid=[${pid}], fifo=[${fifo}]")
            return false
        }
        output = File(fifo)
        policy = policyDB.fetch(uid) ?: SuPolicy(uid).also {
            Timber.d("SuRequestHandler.init: no policy for uid=%d, created fresh", uid)
        }
        try {
            pkgInfo = pm.getPackageInfo(uid, pid) ?: PackageInfo().apply {
                val name = pm.getNameForUid(uid) ?: throw PackageManager.NameNotFoundException()
                sharedUserId = name.split(":")[0]
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.e(e, "SuRequestHandler.init: pkg not found for uid=%d pid=%d", uid, pid)
            respond(SuPolicy.DENY, -1)
            return false
        }
        // The app may have been reinstalled under a new UID while a locked policy
        // follows its package name. Adopt it (remap the row) so the old grant/lock
        // applies automatically without prompting again.
        if (policy.policy == SuPolicy.QUERY) {
            pkgInfo.packageName?.let { pkg ->
                val locked = policyDB.fetchLockedByPackage(pkg)?.takeIf { it.uid != uid }
                if (locked != null) {
                    Timber.d("SuRequestHandler.init: adopting locked policy for %s (uid %d -> %d)",
                        pkg, locked.uid, uid)
                    policyDB.remapUid(pkg, uid)
                    locked.uid = uid
                    policy = locked
                }
            }
        }
        if (!output.canWrite()) {
            Timber.e("Cannot write to $output")
            return false
        }
        Timber.d("SuRequestHandler.init: policy=${policy.policy} pkg=${pkgInfo.packageName}")
        return true
    }

    /**
     * Write the user's decision to the FIFO and persist the policy.
     *
     * @param action [SuPolicy.DENY], [SuPolicy.ALLOW], etc.
     * @param time   Timeout in minutes, or -1 for forever, 0 for single use.
     */
    suspend fun respond(action: Int, time: Long) {
        val pkg = if (::pkgInfo.isInitialized) pkgInfo.packageName else "<unknown>"
        Timber.i("SuRequestHandler.respond: action=%d time=%d min (uid=%d pkg=%s)",
            action, time, policy.uid, pkg)
        if (action == SuPolicy.ALLOW && Config.suRestrict) {
            policy.policy = SuPolicy.RESTRICT
        } else {
            policy.policy = action
        }
        if (time >= 0) {
            policy.remain = TimeUnit.MINUTES.toSeconds(time)
        } else {
            policy.remain = time
        }

        withContext(Dispatchers.IO) {
            try {
                DataOutputStream(FileOutputStream(output)).use {
                    it.writeInt(policy.policy)
                    it.flush()
                }
                Timber.d("SuRequestHandler: wrote policy=%d to fifo %s", policy.policy, output)
            } catch (e: IOException) {
                Timber.e(e, "SuRequestHandler: failed to write fifo %s", output)
            }
            if (time >= 0) {
                policyDB.update(policy)

                val appInfo = pkgInfo.applicationInfo
                val appName = appInfo?.getLabel(pm)
                    ?: pkgInfo.sharedUserId ?: "[UID] ${policy.uid}"
                val packageName = appInfo?.let { pm.getNameForUid(it.uid) }
                    ?: pkgInfo.sharedUserId ?: "[UID] ${policy.uid}"

                val log = SuLog(
                    fromUid = policy.uid,
                    toUid = 0,
                    fromPid = pid,
                    packageName = packageName,
                    appName = appName,
                    command = "",
                    action = policy.policy,
                    target = -1,
                    context = "",
                    gids = "",
                )
                ServiceLocator.logRepo.insert(log)

                val granted = policy.policy >= SuPolicy.ALLOW
                SuCallbackHandler.notify(granted, appName)

                SuEvents.notifyPolicyChanged()
                SuEvents.notifyLogUpdated()
            }
        }
    }
}
