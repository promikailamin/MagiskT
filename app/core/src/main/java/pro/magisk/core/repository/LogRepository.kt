/**
 * Repository that exposes the Magisk daemon logs (via shell commands).
 *
 * Magisk logs are read from `MAGISK_LOG` or pulled from `logcat` as a
 * fallback.
 */
package pro.magisk.core.repository

import pro.magisk.core.Const
import pro.magisk.core.Info
import pro.magisk.core.ktx.await
import com.topjohnwu.superuser.Shell


class LogRepository {

    /** Fetch Magisk daemon logs (from file or logcat). */
    suspend fun fetchMagiskLogs(): String {
        val list = object : AbstractMutableList<String>() {
            val buf = StringBuilder()
            override val size get() = 0
            override fun get(index: Int): String = ""
            override fun removeAt(index: Int): String = ""
            override fun set(index: Int, element: String): String = ""
            override fun add(index: Int, element: String) {
                if (element.isNotEmpty()) {
                    buf.append(element)
                    buf.append('\n')
                }
            }
        }
        if (Info.env.isActive) {
            Shell.cmd("cat ${Const.MAGISK_LOG} || logcat -d -s Magisk").to(list).await()
        } else {
            Shell.cmd("logcat -d").to(list).await()
        }
        return list.buf.toString()
    }

    fun clearMagiskLogs(cb: (Shell.Result) -> Unit) =
        Shell.cmd("echo -n > ${Const.MAGISK_LOG}").submit(cb)

}
