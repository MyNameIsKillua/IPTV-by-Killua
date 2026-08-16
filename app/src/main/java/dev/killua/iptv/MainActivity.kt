package dev.killua.iptv

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.killua.iptv.core.player.PlayerPresentation
import dev.killua.iptv.ui.IptvApp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer
    private var isInPipMode by mutableStateOf(false)
    private var latestPresentation = PlayerPresentation()
    private var pipEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = (application as IptvApplication).container
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    container.playerPresentationState.state.collectLatest { presentation ->
                        latestPresentation = presentation
                        updatePictureInPictureParams()
                    }
                }
                launch {
                    container.preferences.pictureInPictureEnabled.collectLatest { enabled ->
                        pipEnabled = enabled
                        updatePictureInPictureParams()
                    }
                }
            }
        }
        setContent {
            IptvApp(
                container = container,
                isInPictureInPictureMode = isInPipMode,
            )
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && shouldEnterPictureInPicture()) {
            enterPictureInPictureMode(buildPictureInPictureParams(autoEnter = false))
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations && !isInPipMode && latestPresentation.isPlayerScreenVisible) {
            container.playerConnection.pause()
        }
        super.onStop()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
    }

    private fun updatePictureInPictureParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setPictureInPictureParams(
                buildPictureInPictureParams(autoEnter = shouldEnterPictureInPicture()),
            )
        } else if (latestPresentation.isPlayerScreenVisible) {
            setPictureInPictureParams(buildPictureInPictureParams(autoEnter = false))
        }
    }

    private fun shouldEnterPictureInPicture(): Boolean =
        pipEnabled &&
            latestPresentation.isPlayerScreenVisible &&
            latestPresentation.isVideoReady &&
            latestPresentation.isPlaying

    private fun buildPictureInPictureParams(autoEnter: Boolean): PictureInPictureParams {
        val width = latestPresentation.aspectWidth.coerceAtLeast(1)
        val height = latestPresentation.aspectHeight.coerceAtLeast(1)
        val ratio = width.toFloat() / height.toFloat()
        val aspectRatio = when {
            ratio > MAX_PIP_RATIO -> Rational(239, 100)
            ratio < MIN_PIP_RATIO -> Rational(100, 239)
            else -> Rational(width, height)
        }
        return PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .apply {
                Rect().also { sourceRect ->
                    if (window.decorView.getGlobalVisibleRect(sourceRect) && !sourceRect.isEmpty) {
                        setSourceRectHint(sourceRect)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(autoEnter)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    private companion object {
        const val MAX_PIP_RATIO = 2.39f
        const val MIN_PIP_RATIO = 1f / 2.39f
    }
}
