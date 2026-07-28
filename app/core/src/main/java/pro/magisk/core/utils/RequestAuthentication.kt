/**
 * [ActivityResultContract] that prompts the user for device
 * credentials (PIN / pattern / password) via the system
 * [KeyguardManager]. Returns `true` when authentication succeeds.
 */
package pro.magisk.core.utils

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

class RequestAuthentication: ActivityResultContract<Unit, Boolean>() {

    override fun createIntent(context: Context, input: Unit) =
        context.getSystemService(KeyguardManager::class.java)
            .createConfirmDeviceCredentialIntent(null, null)

    override fun parseResult(resultCode: Int, intent: Intent?) =
        resultCode == Activity.RESULT_OK
}
