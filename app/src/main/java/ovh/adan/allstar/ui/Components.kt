package ovh.adan.allstar.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ovh.adan.allstar.R
import ovh.adan.allstar.data.NodeInfo

/** Segmented level meter, -60..0 dBFS. */
@Composable
fun LevelMeter(db: Float, color: Color, modifier: Modifier = Modifier, segments: Int = 24) {
    val target = ((db + 60f) / 60f).coerceIn(0f, 1f)
    val level by animateFloatAsState(target, tween(90), label = "level")
    val off = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (segments - 1)) / segments
        for (i in 0 until segments) {
            val on = (i + 0.5f) / segments <= level
            val c = when {
                !on -> off
                i >= segments - 3 -> Color(0xFFFF4D5E)
                i >= segments - 7 -> Color(0xFFFFB020)
                else -> color
            }
            drawRoundRect(c, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(2.dp.toPx()))
        }
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(color.copy(alpha = 0.16f), label = "pill")
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(color)
        Text(text, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

/** Node number "badge" used in lists. */
@Composable
fun NodeBadge(number: String, highlight: Boolean = false, modifier: Modifier = Modifier) {
    val rc = LocalRadioColors.current
    val el = number.length == 7 && number.startsWith("3")
    val bg = when {
        highlight -> rc.rx.copy(alpha = 0.18f)
        el -> rc.star.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    }
    val fg = when {
        highlight -> rc.rx
        el -> rc.star
        else -> MaterialTheme.colorScheme.primary
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(if (el) "EL " + number.drop(1) else number, style = MonoNumber, color = fg)
    }
}

@Composable
fun NodeTitle(info: NodeInfo?, number: String) {
    val name = info?.callsign?.takeIf { it.isNotBlank() } ?: if (ovh.adan.allstar.data.isEchoLinkNumber(number)) "EchoLink" else number
    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}
