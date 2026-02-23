package app.it.fast4x.rimusic.ui.components.themed

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.kreate.android.R
import app.it.fast4x.rimusic.models.Info
import app.it.fast4x.rimusic.utils.ExternalUris

@Composable
fun ListenOnDialog(
    mediaId: String,
    onDismiss: () -> Unit,
    onPlayOnUrl: (String) -> Unit
) {
    ValueSelectorDialog(
        title = stringResource(R.string.listen_on),
        onDismiss = onDismiss,
        selectedValue = Info("none", null),
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
        onValueSelected = {
            onPlayOnUrl(it.id)
        },
        valueText = { it.name ?: "" }
    )
}



