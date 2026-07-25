package pro.magisk.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import pro.magisk.core.utils.TextHolder

@Composable
fun textHolder(holder: TextHolder) = holder.getText(LocalResources.current)
