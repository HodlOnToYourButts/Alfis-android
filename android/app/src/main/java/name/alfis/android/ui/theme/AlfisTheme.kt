package name.alfis.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF1976D2),
    secondary = androidx.compose.ui.graphics.Color(0xFF03DAC5),
    tertiary = androidx.compose.ui.graphics.Color(0xFF3700B3)
)

@Composable
fun AlfisTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}