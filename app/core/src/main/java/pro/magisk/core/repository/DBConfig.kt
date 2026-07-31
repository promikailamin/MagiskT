/**
 * Delegate-based property bindings for the MagiskDB-backed settings /
 * string stores.
 *
 * [DBConfig] provides factory methods that return [ReadWriteProperty]
 * delegates. Writes are dispatched to the corresponding DAO
 * (asynchronously by default, synchronously when `sync = true`).
 */
package pro.magisk.core.repository

import pro.magisk.core.data.magiskdb.SettingsDao
import pro.magisk.core.data.magiskdb.StringDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

interface DBConfig {
    val settings_d_b: SettingsDao
    val string_d_b: StringDao
    val coroutine_scope: CoroutineScope

    fun db_settings(
        name: String,
        default: Int
    ) = IntDBProperty(name, default)

    fun db_settings(
        name: String,
        default: Boolean
    ) = BoolDBProperty(name, default)

    fun db_strings(
        name: String,
        default: String,
        sync: Boolean = false
    ) = StringDBProperty(name, default, sync)

}

/** Delegate that reads/writes an integer from/to [SettingsDao]. */
class IntDBProperty(
    private val name: String,
    private val default: Int
) : ReadWriteProperty<DBConfig, Int> {

    var value: Int? = null

    @Synchronized
    override fun getValue(thisRef: DBConfig, property: KProperty<*>): Int {
        if (value == null)
            value = runBlocking { thisRef.settings_d_b.fetch(name, default) }
        return value as Int
    }

    override fun setValue(thisRef: DBConfig, property: KProperty<*>, value: Int) {
        synchronized(this) {
            this.value = value
        }
        thisRef.coroutine_scope.launch {
            thisRef.settings_d_b.put(name, value)
        }
    }
}

/** Delegate that reads/writes a boolean (stored as 0/1) from/to [SettingsDao]. */
open class BoolDBProperty(
    name: String,
    default: Boolean
) : ReadWriteProperty<DBConfig, Boolean> {

    val base = IntDBProperty(name, if (default) 1 else 0)

    override fun getValue(thisRef: DBConfig, property: KProperty<*>): Boolean =
        base.getValue(thisRef, property) != 0

    override fun setValue(thisRef: DBConfig, property: KProperty<*>, value: Boolean) =
        base.setValue(thisRef, property, if (value) 1 else 0)
}

/** Delegate that reads/writes a string from/to [StringDao]. */
class StringDBProperty(
    private val name: String,
    private val default: String,
    private val sync: Boolean
) : ReadWriteProperty<DBConfig, String> {

    private var value: String? = null

    @Synchronized
    override fun getValue(thisRef: DBConfig, property: KProperty<*>): String {
        if (value == null)
            value = runBlocking {
                thisRef.string_d_b.fetch(name, default)
            }
        return value!!
    }

    override fun setValue(thisRef: DBConfig, property: KProperty<*>, value: String) {
        synchronized(this) {
            this.value = value
        }
        if (value.isEmpty()) {
            if (sync) {
                runBlocking {
                    thisRef.string_d_b.delete(name)
                }
            } else {
                thisRef.coroutine_scope.launch {
                    thisRef.string_d_b.delete(name)
                }
            }
        } else {
            if (sync) {
                runBlocking {
                    thisRef.string_d_b.put(name, value)
                }
            } else {
                thisRef.coroutine_scope.launch {
                    thisRef.string_d_b.put(name, value)
                }
            }
        }
    }
}
