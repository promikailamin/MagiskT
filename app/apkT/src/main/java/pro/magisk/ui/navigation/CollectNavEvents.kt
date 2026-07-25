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
