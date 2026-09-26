/**
 * ContentProvider that acts as a callback bridge from the Magisk
 * daemon (running as root) to the Java process.
 *
 * SU requests are answered entirely inside the daemon, so the app no
 * longer exposes any privileged callback surface: every call is
 * acknowledged with an empty bundle.
 */
package pro.magisk.core

import android.os.Bundle
import pro.magisk.core.base.BaseProvider

class Provider : BaseProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = Bundle.EMPTY
}
