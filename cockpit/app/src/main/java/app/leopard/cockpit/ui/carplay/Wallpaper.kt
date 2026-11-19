package app.leopard.cockpit.ui.carplay

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import app.leopard.cockpit.R
import app.leopard.cockpit.ui.theme.LocalTokens

/**
 * CarPlay mode's wallpaper: folds of dark blue in dark mode, of gold and cream in light
 * mode. Behind Home, behind the apps' windows (and the app picker beside one), and
 * behind Settings' window.
 */
@Composable
internal fun CarPlayWallpaper(modifier: Modifier = Modifier) {
    val dark = LocalTokens.current.dark
    Image(
        painterResource(if (dark) R.drawable.carplay_wall_dark else R.drawable.carplay_wall_light),
        contentDescription = null,
        modifier = modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
    )
}
