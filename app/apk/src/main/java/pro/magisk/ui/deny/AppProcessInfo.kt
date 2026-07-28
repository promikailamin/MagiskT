/**
 * Data models for the DenyList app/process resolution.
 *
 * [CmdlineListItem] parses a single `magisk --denylist ls` output line.
 * [AppProcessInfo] resolves an installed app's manifest to discover its processes
 * (activities, services, receivers, providers) and cross-references them with the
 * current denylist. Falls back to parsing the APK directly when PackageManager
 * queries exceed the binder transaction limit.
 * [ProcessInfo] is a simple data holder for a single process entry.
 */
package pro.magisk.ui.deny

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_ACTIVITIES
import android.content.pm.PackageManager.GET_PROVIDERS
import android.content.pm.PackageManager.GET_RECEIVERS
import android.content.pm.PackageManager.GET_SERVICES
import android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.content.pm.ServiceInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import androidx.core.os.ProcessCompat
import pro.magisk.core.ktx.getLabel
import java.util.Locale
import java.util.TreeSet

/** Parses a denylist entry line in the format `packageName|processName`. */
class CmdlineListItem(line: String) {
    val packageName: String
    val process: String

    init {
        val split = line.split(Regex("\\|"), 2)
        packageName = split[0]
        process = split.getOrElse(1) { packageName }
    }
}

/** Magic package name used for isolated processes in the denylist. */
const val ISOLATED_MAGIC = "isolated"

/** Resolves an installed app's processes and their denylist status. */
@SuppressLint("InlinedApi")
class AppProcessInfo(
    private val info: ApplicationInfo,
    pm: PackageManager,
    denyList: List<CmdlineListItem>
) : Comparable<AppProcessInfo> {

    private val denyList = denyList.filter {
        it.packageName == info.packageName || it.packageName == ISOLATED_MAGIC
    }

    val label = info.getLabel(pm)
    val iconImage: Drawable = runCatching { info.loadIcon(pm) }.getOrDefault(pm.defaultActivityIcon)
    val packageName: String get() = info.packageName
    val processes = fetchProcesses(pm)

    override fun compareTo(other: AppProcessInfo) = comparator.compare(this, other)

    fun isSystemApp() = info.flags and ApplicationInfo.FLAG_SYSTEM != 0

    fun isApp() = ProcessCompat.isApplicationUid(info.uid)

    private fun createProcess(name: String, pkg: String = info.packageName) =
        ProcessInfo(name, pkg, denyList.any { it.process == name && it.packageName == pkg })

    private fun ComponentInfo.getProcName(): String = processName
        ?: applicationInfo.processName
        ?: applicationInfo.packageName

    private val ServiceInfo.isIsolated get() = (flags and ServiceInfo.FLAG_ISOLATED_PROCESS) != 0
    private val ServiceInfo.useAppZygote get() = (flags and ServiceInfo.FLAG_USE_APP_ZYGOTE) != 0

    private fun Array<out ComponentInfo>?.toProcessList() =
        orEmpty().map { createProcess(it.getProcName()) }

    private fun Array<ServiceInfo>?.toProcessList() = orEmpty().map {
        if (it.isIsolated) {
            if (it.useAppZygote) {
                val proc = info.processName ?: info.packageName
                createProcess("${proc}_zygote")
            } else {
                val proc = if (SDK_INT >= Build.VERSION_CODES.Q)
                    "${it.getProcName()}:${it.name}" else it.getProcName()
                createProcess(proc, ISOLATED_MAGIC)
            }
        } else {
            createProcess(it.getProcName())
        }
    }

    /** Discovers all processes declared in the app manifest. Falls back to APK parsing on binder overflow. */
    private fun fetchProcesses(pm: PackageManager): Collection<ProcessInfo> {
        val flag = MATCH_DISABLED_COMPONENTS or MATCH_UNINSTALLED_PACKAGES or
            GET_ACTIVITIES or GET_SERVICES or GET_RECEIVERS or GET_PROVIDERS
        val packageInfo = try {
            pm.getPackageInfo(info.packageName, flag)
        } catch (e: Exception) {
            pm.getPackageArchiveInfo(info.sourceDir, flag) ?: return emptyList()
        }

        val processSet = TreeSet<ProcessInfo>(compareBy({ it.name }, { it.isIsolated }))
        processSet += packageInfo.activities.toProcessList()
        processSet += packageInfo.services.toProcessList()
        processSet += packageInfo.receivers.toProcessList()
        processSet += packageInfo.providers.toProcessList()
        return processSet
    }

    companion object {
        private val comparator = compareBy<AppProcessInfo>(
            { it.label.lowercase(Locale.ROOT) },
            { it.info.packageName }
        )
    }
}

/** A single process entry with its denylist status. */
data class ProcessInfo(
    val name: String,
    val packageName: String,
    var isEnabled: Boolean
) {
    val isIsolated = packageName == ISOLATED_MAGIC
    val isAppZygote = name.endsWith("_zygote")
}
