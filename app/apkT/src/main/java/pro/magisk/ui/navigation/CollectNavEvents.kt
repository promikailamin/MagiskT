/**
 * Composable helper that pipes navigation events from a [BaseViewModel]'s [navEvents] flow into
 * the [Navigator]'s back-stack. Placed in the composition tree alongside the ViewModel so the
 * coroutine is scoped to the screen's lifecycle.
 */
package pro.magisk.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import pro.magisk.arch.BaseViewModel

@Composable
fun CollectNavEvents(viewModel: BaseViewModel, navigator: Navigator) {
    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { route ->
            navigator.push(route)
        }
    }
}
