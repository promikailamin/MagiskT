/**
 * Lightweight stack-based navigator backed by a Compose [SnapshotStateList]. Provides push/pop
 * semantics for the navigation3 [NavDisplay]. The back-stack is saved/restored across config
 * changes via a custom [Saver].
 */
package pro.magisk.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey

/** A simple back-stack that holds [NavKey] entries and exposes them as a reactive list. */
class Navigator(initialKey: NavKey) {
    val backStack: SnapshotStateList<NavKey> = mutableStateListOf(initialKey)

    /** Push a new destination onto the stack. */
    fun push(key: NavKey) {
        backStack.add(key)
    }

    /** Pop the top destination (no-op if stack has only one entry). */
    fun pop() {
        backStack.removeLastOrNull()
    }

    /** Peek at the current (topmost) destination. */
    fun current(): NavKey? = backStack.lastOrNull()

    companion object {
        val Saver: Saver<Navigator, Any> = listSaver(
            save = { navigator -> navigator.backStack.toList() },
            restore = { savedList ->
                val initialKey = savedList.firstOrNull() ?: Route.Main
                Navigator(initialKey).also {
                    it.backStack.clear()
                    it.backStack.addAll(savedList)
                }
            }
        )
    }
}

/** Remember a [Navigator] across recompositions, saved/restored via [Navigator.Saver]. */
@Composable
fun rememberNavigator(startRoute: NavKey): Navigator {
    return rememberSaveable(startRoute, saver = Navigator.Saver) {
        Navigator(startRoute)
    }
}

/** CompositionLocal that provides the current [Navigator] to the screen tree. */
val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator not provided")
}
