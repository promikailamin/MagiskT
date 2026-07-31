/**
 * Abstraction for a piece of text that can either be a plain string, a
 * string resource ID, or a string resource with format arguments (which
 * may themselves be [TextHolder]s, resolved recursively). Enables lazy
 * resolution of translated strings in ViewModel / model code that does
 * not otherwise have access to [Resources].
 */
package pro.magisk.core.utils

import android.content.res.Resources

abstract class TextHolder {

    open val isEmpty: Boolean get() = false
    abstract fun get_text(resources: Resources): String

    /** A [TextHolder] backed by an already-resolved [String]. */
    class Str(private val value: String) : TextHolder() {
        override val isEmpty get() = value.isEmpty()
        override fun get_text(resources: Resources) = value
    }

    /** A [TextHolder] backed by a string resource ID. */
    open class Resource(protected val value: Int) : TextHolder() {
        override val isEmpty get() = value == 0
        override fun get_text(resources: Resources) = resources.getString(value)
    }

    /** A [TextHolder] backed by a formatted string resource with vararg arguments. */
    class ResourceArgs(
        value: Int,
        private vararg val params: Any
    ) : Resource(value) {
        override fun get_text(resources: Resources): String {
            val args = params.map { if (it is TextHolder) it.get_text(resources) else it }
            return resources.getString(value, *args.toTypedArray())
        }
    }

    companion object {
        val EMPTY = Str("")
    }
}

fun Int.asText(): TextHolder = TextHolder.Resource(this)
fun Int.asText(vararg params: Any): TextHolder = TextHolder.ResourceArgs(this, *params)
fun String.asText(): TextHolder = TextHolder.Str(this)
