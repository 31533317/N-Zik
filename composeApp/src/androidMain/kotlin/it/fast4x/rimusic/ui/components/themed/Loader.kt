package it.fast4x.rimusic.ui.components.themed

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.fast4x.rimusic.colorPalette

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Loader(
    size: Dp = 100.dp,
    modifier: Modifier = Modifier.fillMaxWidth()
) = Box(
    modifier = modifier,
) {
    LoadingIndicator(
        color = colorPalette().accent,
        modifier = Modifier
            .align(Alignment.Center)
            .size(size)
    )
}