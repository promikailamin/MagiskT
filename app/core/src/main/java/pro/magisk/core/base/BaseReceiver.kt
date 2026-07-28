/**
 * Base [BroadcastReceiver] that patches the context (locale + stub
 * assets) before dispatch.
 *
 * Subclasses override [onReceive] and must call `super.onReceive`
 * to apply the patches.
 */
package pro.magisk.core.base

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.CallSuper
import pro.magisk.core.patch

abstract class BaseReceiver : BroadcastReceiver() {
    @CallSuper
    override fun onReceive(context: Context, intent: Intent?) {
        context.patch()
    }
}
