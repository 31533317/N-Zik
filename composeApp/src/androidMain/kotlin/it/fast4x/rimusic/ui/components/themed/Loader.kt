package it.fast4x.rimusic.ui.components.themed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.fast4x.rimusic.colorPalette
import it.fast4x.rimusic.enums.NavigationBarPosition
import it.fast4x.rimusic.ui.styling.Dimensions

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Loader(
    size: Dp = 100.dp,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val bottomPadding = if (NavigationBarPosition.BottomFloating.isCurrent()) {
        Dimensions.bottomSpacer
    } else {
        0.dp
    }

    Box(
        modifier = modifier.padding(bottom = bottomPadding),
    ) {
        LoadingIndicator(
            color = colorPalette().accent,
            modifier = Modifier
                .align(Alignment.Center)
                .size(size)
        )
    }
}