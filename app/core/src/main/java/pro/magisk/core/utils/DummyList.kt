/**
 * An empty, immutable [AbstractList] that silently ignores all mutation
 * attempts. Used as a no-op console/log sink when caller-supplied lists
 * are not needed (e.g. [MagiskInstallImpl] subclasses that only exec
 * callbacks).
 */
package pro.magisk.core.utils

object DummyList : java.util.AbstractList<String>() {

    override val size: Int get() = 0

    override fun get(index: Int): String {
        throw IndexOutOfBoundsException()
    }

    override fun add(element: String): Boolean = false

    override fun add(index: Int, element: String) {}

    override fun clear() {}
}


