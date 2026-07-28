/**
 * Tab-based main screen layout. Hosts a [HorizontalPager] of five pages (Home, Modules, Superuser,
 * Log, Settings) with a floating bottom navigation bar. Visibility of each tab depends on the
 * runtime environment (e.g. Superuser hidden when MagiskSU is unavailable).
 */
package pro.magisk.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import pro.magisk.R
import pro.magisk.arch.VMFactory
import pro.magisk.core.Info
import pro.magisk.core.model.module.LocalModule
import pro.magisk.ui.home.HomeScreen
import pro.magisk.ui.home.HomeViewModel
import pro.magisk.ui.install.InstallViewModel
import pro.magisk.ui.log.LogScreen
import pro.magisk.ui.log.LogViewModel
import pro.magisk.ui.module.ModuleScreen
import pro.magisk.ui.module.ModuleViewModel
import pro.magisk.ui.navigation.CollectNavEvents
import pro.magisk.ui.navigation.LocalNavigator
import pro.magisk.ui.settings.SettingsScreen
import pro.magisk.ui.settings.SettingsViewModel
import pro.magisk.ui.superuser.SuperuserScreen
import pro.magisk.ui.superuser.SuperuserViewModel
import kotlinx.coroutines.launch
import pro.magisk.core.R as CoreR

/**
 * The five tabs available in the main navigation. Order here determines the order in the pager,
 * but [MainScreen] may filter out some tabs depending on device state.
 */
enum class Tab(val titleRes: Int, val iconRes: Int) {
    MODULES(CoreR.string.modules, R.drawable.ic_module),
    SUPERUSER(CoreR.string.superuser, CoreR.drawable.ic_superuser),
    HOME(CoreR.string.section_home, R.drawable.ic_home),
    LOG(CoreR.string.logs, R.drawable.ic_bug),
    SETTINGS(CoreR.string.settings, R.drawable.ic_settings);
}

/**
 * Root tab scaffold. Creates the ViewModels for each page and wires navigation events through
 * [CollectNavEvents]. Only tabs that are relevant in the current environment are shown.
 */
@Composable
fun MainScreen(initialTab: Int = Tab.HOME.ordinal) {
    val navigator = LocalNavigator.current
    val visibleTabs = remember {
        Tab.entries.filter { tab ->
            when (tab) {
                Tab.SUPERUSER -> Info.showSuperUser
                Tab.MODULES -> Info.env.isActive && LocalModule.loaded()
                else -> true
            }
        }
    }
    val initialPage = visibleTabs.indexOf(Tab.entries[initialTab]).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { visibleTabs.size })

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = visibleTabs.size - 1,
            userScrollEnabled = true,
        ) { page ->
            when (visibleTabs[page]) {
                Tab.HOME -> {
                    val vm: HomeViewModel = viewModel(factory = VMFactory)
                    val installVm: InstallViewModel = viewModel(factory = VMFactory)
                    LaunchedEffect(Unit) { vm.startLoading() }
                    CollectNavEvents(vm, navigator)
                    CollectNavEvents(installVm, navigator)
                    HomeScreen(vm, installVm)
                }
                Tab.SUPERUSER -> {
                    val activity = LocalActivity.current as MainActivity
                    val vm: SuperuserViewModel = viewModel(viewModelStoreOwner = activity, factory = VMFactory)
                    LaunchedEffect(Unit) {
                        vm.authenticate = { onSuccess ->
                            activity.extension.withAuthentication { if (it) onSuccess() }
                        }
                        vm.startLoading()
                    }
                    SuperuserScreen(vm)
                }
                Tab.LOG -> {
                    val vm: LogViewModel = viewModel(factory = VMFactory)
                    LaunchedEffect(Unit) { vm.startLoading() }
                    LogScreen(vm)
                }
                Tab.MODULES -> {
                    val vm: ModuleViewModel = viewModel(factory = VMFactory)
                    LaunchedEffect(Unit) { vm.startLoading() }
                    CollectNavEvents(vm, navigator)
                    ModuleScreen(vm)
                }
                Tab.SETTINGS -> {
                    val activity = LocalActivity.current as MainActivity
                    val vm: SettingsViewModel = viewModel(factory = VMFactory)
                    LaunchedEffect(Unit) {
                        vm.authenticate = { onSuccess ->
                            activity.extension.withAuthentication { if (it) onSuccess() }
                        }
                    }
                    CollectNavEvents(vm, navigator)
                    SettingsScreen(vm)
                }
            }
        }

        FloatingNavigationBar(
            pagerState = pagerState,
            visibleTabs = visibleTabs,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/** Floating pill-shaped bottom navigation bar that overlays the pager content. */
@Composable
private fun FloatingNavigationBar(
    pagerState: PagerState,
    visibleTabs: List<Tab>,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(28.dp)
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Row(
        modifier = modifier
            .padding(bottom = navBarInset + 12.dp, start = 24.dp, end = 24.dp)
            .shadow(elevation = 6.dp, shape = shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        visibleTabs.forEachIndexed { index, tab ->
            FloatingNavItem(
                icon = ImageVector.vectorResource(tab.iconRes),
                label = stringResource(tab.titleRes),
                selected = pagerState.currentPage == index,
                enabled = true,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** A single clickable icon+label item inside the floating navigation bar. */
@Composable
private fun FloatingNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "navItemColor"
    )

    Column(
        modifier = modifier
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = contentColor,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = contentColor,
        )
    }
}
