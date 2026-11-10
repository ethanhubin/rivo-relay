package dev.rivo.relay.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small shared Android toolbar glyphs, no additional icon runtime required. */
internal object FileIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
    }.build()
    val Picture = icon("Picture", "M3,3 L21,3 L21,21 L3,21 Z M5,5 L5,16 L10,10 L14,15 L17,12 L19,15 L19,5 Z M7,6 L10,6 L10,9 L7,9 Z")
    val Archive = icon("Archive", "M3,3 L21,3 L21,8 L20,8 L20,21 L4,21 L4,8 L3,8 Z M5,5 L5,6 L19,6 L19,5 Z M6,8 L6,19 L18,19 L18,8 Z M9,10 L15,10 L15,12 L9,12 Z")
    val Table = icon("Table", "M3,3 L21,3 L21,21 L3,21 Z M5,5 L5,9 L19,9 L19,5 Z M5,11 L5,14 L10,14 L10,11 Z M12,11 L12,14 L19,14 L19,11 Z M5,16 L5,19 L10,19 L10,16 Z M12,16 L12,19 L19,19 L19,16 Z")
    val Document = icon("Document", "M6,2 L14,2 L20,8 L20,22 L4,22 L4,2 Z M6,4 L6,20 L18,20 L18,9 L13,9 L13,4 Z M15,5 L15,7 L17,7 Z M8,12 L16,12 L16,14 L8,14 Z M8,16 L16,16 L16,18 L8,18 Z")
    val Close = icon("Close", "M19,6.41 L17.59,5 L12,10.59 L6.41,5 L5,6.41 L10.59,12 L5,17.59 L6.41,19 L12,13.41 L17.59,19 L19,17.59 L13.41,12 Z")
    val Share = icon("Share", "M18,16.08 C17.24,16.08 16.56,16.38 16.04,16.85 L8.91,12.7 C8.97,12.47 9,12.24 9,12 C9,11.76 8.97,11.53 8.91,11.3 L15.96,7.19 C16.5,7.69 17.21,8 18,8 C19.66,8 21,6.66 21,5 C21,3.34 19.66,2 18,2 C16.34,2 15,3.34 15,5 C15,5.24 15.03,5.47 15.09,5.7 L8.04,9.81 C7.5,9.31 6.79,9 6,9 C4.34,9 3,10.34 3,12 C3,13.66 4.34,15 6,15 C6.79,15 7.5,14.69 8.04,14.19 L15.16,18.35 C15.11,18.56 15.08,18.78 15.08,19 C15.08,20.61 16.39,21.92 18,21.92 C19.61,21.92 20.92,20.61 20.92,19 C20.92,17.39 19.61,16.08 18,16.08 Z")
    val SaveAlt = icon("Save", "M19,12 L19,19 L5,19 L5,12 L3,12 L3,19 C3,20.1 3.9,21 5,21 L19,21 C20.1,21 21,20.1 21,19 L21,12 Z M17,10 L15.59,8.59 L13,11.17 L13,3 L11,3 L11,11.17 L8.41,8.59 L7,10 L12,15 Z")
    val MusicNote = icon("Music", "M12,3 L12,12.55 C11.41,12.21 10.73,12 10,12 C7.79,12 6,13.79 6,16 C6,18.21 7.79,20 10,20 C12.21,20 14,18.21 14,16 L14,7 L18,7 L18,3 Z")
    val PlayArrow = icon("Play", "M8,5 L8,19 L19,12 Z")
    val Pause = icon("Pause", "M6,19 L10,19 L10,5 L6,5 Z M14,5 L14,19 L18,19 L18,5 Z")
}
