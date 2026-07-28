package pro.magisk.core.base

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import pro.magisk.core.R
import pro.magisk.core.AppContext
import pro.magisk.core.utils.CrashHandler
import java.io.File

class DebugActivity : ComponentActivity(), UntrackedActivity {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        val crashFile = File(cacheDir, "crash_reports/crash_info.txt")
        val crashInfo = if (crashFile.exists()) crashFile.readText() else "No crash info available."

        findViewById<TextView>(R.id.crash_info).text = crashInfo
        findViewById<TextView>(R.id.crash_timestamp).text = crashFile
            .takeIf { it.exists() }
            ?.lastModified()
            ?.let { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(it)) }
            ?: ""

        findViewById<Button>(R.id.btn_copy).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("crash", crashInfo))
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_share).setOnClickListener {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, crashInfo)
            }
            startActivity(Intent.createChooser(share, "Share crash report"))
        }

        findViewById<Button>(R.id.btn_restart).setOnClickListener {
            crashFile.delete()
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
            }
            finish()
            Runtime.getRuntime().exit(0)
        }
    }
}
