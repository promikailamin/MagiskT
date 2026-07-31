/**
 * Base [ContentProvider] that patches the context (locale + stub
 * assets) before any other lifecycle method runs.
 *
 * All content-provider methods return no-op defaults; subclasses
 * override [call] to handle daemon callbacks.
 */
package pro.magisk.core.base

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import pro.magisk.core.patch

open class BaseProvider : ContentProvider() {
    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context.patch(), info)
    }
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selection_args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selection_args: Array<out String>?) = 0
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selection_args: Array<out String>?, sort_order: String?): Cursor? = null
}
