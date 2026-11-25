package dev.photohub.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import javafx.application.Platform as FxPlatform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import java.io.File

/**
 * JavaFX media embedded in the desktop Compose window via [SwingPanel].
 *
 * JavaFX ships its own decoders, so this needs nothing installed on the
 * machine — unlike shelling out to VLC. [JFXPanel]'s constructor starts the
 * JavaFX toolkit on first use; everything touching the [MediaPlayer] after
 * that must run on the JavaFX application thread via [FxPlatform.runLater].
 *
 * [model] is the item's absolute file path (local items) or a cached
 * original's path (remote items); the fullscreen viewer never passes a
 * remote URL here directly.
 */
@Composable
actual fun VideoPlayer(model: Any, modifier: Modifier) {
    val path = remember(model) {
        when (model) {
            is File -> model.path
            else -> model.toString()
        }
    }
    val playerHolder = remember(path) { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(path) {
        onDispose {
            playerHolder.value?.let { player ->
                FxPlatform.runLater {
                    player.stop()
                    player.dispose()
                }
            }
        }
    }

    SwingPanel(
        modifier = modifier,
        factory = {
            JFXPanel().also { panel ->
                FxPlatform.runLater {
                    val media = Media(File(path).toURI().toString())
                    val player = MediaPlayer(media).apply { isAutoPlay = true }
                    playerHolder.value = player

                    val view = MediaView(player)
                    view.isPreserveRatio = true
                    val root = StackPane(view)
                    view.fitWidthProperty().bind(root.widthProperty())
                    view.fitHeightProperty().bind(root.heightProperty())

                    panel.scene = Scene(root)
                }
            }
        },
    )
}
