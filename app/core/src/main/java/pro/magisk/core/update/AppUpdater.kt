/**
 * Self-hosted update system backed by this repository's GitHub Releases.
 *
 * Two rolling releases are published by the build workflow: `build` for the
 * main branch and `test` for the test branch. An installed APK knows which
 * channel it belongs to through [BuildConfig.BUILD_CHANNEL], so it always
 * follows the matching release and downloads the debug or release APK based
 * on the APK's [BuildConfig.BUILD_TYPE].
 */
package pro.magisk.core.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import pro.magisk.core.BuildConfig
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Metadata for an update published in a GitHub release. */
data class UpdateInfo(
    val channel: String,
    val tag: String,
    val commit: String,
    val apkName: String,
    val apkUrl: String,
    val releaseUrl: String,
    val releaseNotes: String
)

/** Result of trying to install a downloaded update. */
enum class InstallResult {
    INSTALLED,
    USER_ACTION_REQUIRED,
    FAILED
}

object AppUpdater {

    const val CHANNEL_MAIN = "main"
    const val CHANNEL_TEST = "test"

    private const val REPO = "promikailamin/MagiskT"
    private const val USER_AGENT = "MagiskT-Updater"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val UPDATE_DIR = "updates"

    private val commitRegex = Regex(
        """(?im)^\s*#?\s*Commit\s*:\s*([0-9a-f]{7,40})\b"""
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun parseCommit(notes: String): String =
        commitRegex.find(notes)?.groupValues?.get(1)?.lowercase().orEmpty()

    /** Channel baked into the installed APK. */
    val channel: String
        get() = BuildConfig.BUILD_CHANNEL.ifBlank { CHANNEL_MAIN }

    /** Debug APKs must receive the debug asset, release APKs the release asset. */
    val isDebugBuild: Boolean
        get() = BuildConfig.BUILD_TYPE.equals("debug", ignoreCase = true)

    /** Rolling release tag that belongs to [channel]. */
    val releaseTag: String
        get() = if (channel == CHANNEL_TEST) "test" else "build"

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Fetches the release for the installed channel and returns it only when
     * its commit differs from the running APK's commit.
     */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable(context)) return@withContext null

        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/tags/$releaseTag")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.d("update check: HTTP %d for %s", response.code, releaseTag)
                    return@withContext null
                }
                val json = JSONObject(response.body.string())
                val notes = json.optString("body")
                val commit = parseCommit(notes)
                if (commit.isBlank()) return@withContext null

                val current = BuildConfig.BUILD_COMMIT.trim().lowercase()
                // A local build is never identical to a published release, so it
                // is allowed to update. CI builds compare their short commit hash.
                if (current != "local" && current.isNotEmpty() && current == commit) {
                    return@withContext null
                }

                val apkName = if (isDebugBuild) "app-debug.apk" else "app-release.apk"
                val apkUrl = findAssetUrl(json, apkName)
                if (apkUrl.isBlank()) {
                    Timber.w("update check: %s missing from %s", apkName, releaseTag)
                    return@withContext null
                }

                UpdateInfo(
                    channel = channel,
                    tag = releaseTag,
                    commit = commit,
                    apkName = apkName,
                    apkUrl = apkUrl,
                    releaseUrl = json.optString("html_url"),
                    releaseNotes = notes
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "update check failed")
            null
        }
    }

    /** Whether the update can be installed through `pm install` without user interaction. */
    suspend fun canInstallSilently(): Boolean = withContext(Dispatchers.IO) { hasRoot() }

    /** Downloads the selected APK into the app's cache and reports 0..100 progress. */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        val target = File(dir, info.apkName)
        val partial = File(dir, "${info.apkName}.part")

        val request = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed: HTTP ${response.code}")
            }
            val body = response.body
            val total = body.contentLength()
            var copied = 0L
            var lastPercent = -1

            partial.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            val percent = (copied * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) { onProgress(percent) }
                            }
                        }
                    }
                }
            }
        }

        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        if (target.length() == 0L) throw IOException("Downloaded APK is empty")
        target
    }

    /**
     * Installs the APK with `pm install` when root is available. Otherwise the
     * system package installer is opened so the user can confirm the update.
     */
    suspend fun install(context: Context, apk: File): InstallResult = withContext(Dispatchers.IO) {
        if (hasRoot()) {
            val result = runCatching {
                Shell.cmd("pm install -r -d '${apk.absolutePath}'").exec()
            }.getOrNull()
            val output = result?.out?.joinToString("\n").orEmpty()
            if (result != null && output.contains("Success", ignoreCase = true)) {
                return@withContext InstallResult.INSTALLED
            }
            Timber.w("pm install failed, falling back to package installer: %s", output)
        }

        try {
            withContext(Dispatchers.Main) {
                val authority = "${BuildConfig.APP_PACKAGE_NAME}.$UPDATE_DIR"
                val uri = FileProvider.getUriForFile(context, authority, apk)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                    .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
                context.startActivity(intent)
            }
            InstallResult.USER_ACTION_REQUIRED
        } catch (e: Exception) {
            Timber.e(e, "failed to open package installer")
            InstallResult.FAILED
        }
    }

    private fun hasRoot(): Boolean = runCatching {
        Shell.getShell().isRoot
    }.getOrDefault(false)

    private fun findAssetUrl(json: JSONObject, apkName: String): String {
        val assets = json.optJSONArray("assets") ?: return ""
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == apkName) {
                return asset.optString("browser_download_url")
            }
        }
        return ""
    }
}
