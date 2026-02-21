package it.fast4x.rimusic.ui.components.themed

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.kreate.android.R
import it.fast4x.rimusic.ui.components.themed.SelectorDialog
import it.fast4x.rimusic.models.Info
import it.fast4x.rimusic.utils.ExternalUris

@Composable
fun ListenOnDialog(
    mediaId: String,
    onDismiss: () -> Unit,
    onPlayOnUrl: (String) -> Unit
) {
    SelectorDialog(
        title = stringResource(R.string.listen_on),
        onDismiss = onDismiss,
        values = listOf(
            Info(
                ExternalUris.youtube(mediaId),
                stringResource(R.string.listen_on_youtube)
            ),
            Info(
                ExternalUris.youtubeMusic(mediaId),
                stringResource(R.string.listen_on_youtube_music)
            ),
            Info(
                ExternalUris.piped(mediaId),
                stringResource(R.string.listen_on_piped)
            ),
            Info(
                ExternalUris.invidious(mediaId),
                stringResource(R.string.listen_on_invidious)
            )
        ),
        onValueSelected = onPlayOnUrl
    )
}
