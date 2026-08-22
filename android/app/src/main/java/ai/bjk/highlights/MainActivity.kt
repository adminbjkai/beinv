package ai.bjk.highlights

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    /** True while the activity is shown as a picture-in-picture window. */
    val inPip = mutableStateOf(false)
    /** Set by PlayerScreen while a video is on screen; Home then enters PiP. */
    var pipAllowed = false

    private val supportsPip by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HighlightsTheme {
                var playlist by remember { mutableStateOf<Playlist?>(null) }
                val pl = playlist
                if (pl == null) {
                    BrowseScreen(onPlay = { playlist = it })
                } else {
                    BackHandler { playlist = null }
                    PlayerScreen(playlist = pl, inPip = inPip.value, onBack = { playlist = null })
                }
            }
        }
    }

    fun enterPip(): Boolean {
        if (!pipAllowed || !supportsPip) return false
        return runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }.getOrDefault(false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }
}
