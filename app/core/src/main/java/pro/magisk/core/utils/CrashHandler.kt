/**
 * Default uncaught-exception handler that writes a detailed crash
 * report (device info, thread state, full stack trace, and all live
 * threads) to a file in the cache dir and then launches
 * [DebugActivity] to display it before killing the process.
 */
package pro.magisk.core.utils

import android.content.Intent
import android.os.Build
import android.os.Process
import pro.magisk.core.AppContext
import pro.magisk.core.base.DebugActivity
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val CRASH_FILE = "crash_info.txt"
    private val date_format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Timber.e(throwable, "Uncaught exception in thread: %s", thread.name)

        try {
            val crash_dir = File(AppContext.cacheDir, "crash_reports")
            crash_dir.mkdirs()
            val crash_file = File(crash_dir, CRASH_FILE)

            val report = build_crash_report(thread, throwable)
            crash_file.writeText(report)

            val intent = Intent(AppContext, DebugActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            AppContext.startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch crash activity")
        }

        Process.killProcess(Process.myPid())
        System.exit(1)
    }

    private fun build_crash_report(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)

        pw.println("=== CRASH REPORT ===")
        pw.println("Time: ${date_format.format(Date())}")
        pw.println()
        pw.println("--- DEVICE INFO ---")
        pw.println("Brand: ${Build.BRAND}")
        pw.println("Device: ${Build.DEVICE}")
        pw.println("Model: ${Build.MODEL}")
        pw.println("Product: ${Build.PRODUCT}")
        pw.println("Board: ${Build.BOARD}")
        pw.println("Manufacturer: ${Build.MANUFACTURER}")
        pw.println("Hardware: ${Build.HARDWARE}")
        pw.println("Display: ${Build.DISPLAY}")
        pw.println("Fingerprint: ${Build.FINGERPRINT}")
        pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        pw.println("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        pw.println("Tags: ${Build.TAGS}")
        pw.println("Type: ${Build.TYPE}")
        pw.println()
        pw.println("--- THREAD INFO ---")
        @Suppress("DEPRECATION")
        pw.println("Thread: ${thread.name} (id=${thread.id})")
        pw.println("Priority: ${thread.priority}")
        pw.println("Daemon: ${thread.isDaemon}")
        pw.println("Thread Group: ${thread.threadGroup?.name}")
        pw.println()
        pw.println("--- STACK TRACE ---")
        throwable.printStackTrace(pw)
        pw.println()

        throwable.cause?.let { cause ->
            pw.println("--- CAUSED BY ---")
            cause.printStackTrace(pw)
            pw.println()
        }

        pw.println("--- ALL THREADS ---")
        val thread_map = Thread.getAllStackTraces()
        thread_map.forEach { (t, stack) ->
            @Suppress("DEPRECATION")
            if (t.id != thread.id) {
                @Suppress("DEPRECATION")
                pw.println("Thread: ${t.name} (id=${t.id}, state=${t.state})")
                stack.forEach { element ->
                    pw.println("\tat $element")
                }
                pw.println()
            }
        }

        pw.flush()
        return sw.toString()
    }
}
