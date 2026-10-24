package com.lutbeta.app.ui.components

import android.text.format.DateUtils
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lutbeta.app.ui.theme.Lime
import com.lutbeta.app.ui.theme.Line
import com.lutbeta.app.ui.theme.Muted
import com.lutbeta.app.ui.theme.Panel
import com.lutbeta.app.ui.theme.Paper

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Muted
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = Muted.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    color: Color = Panel,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = color,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        content()
    }
}

@Composable
fun StatusDot(active: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = if (active) 0.45f else 0.25f,
        targetValue = if (active) 1f else 0.25f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "dotAlpha"
    )
    Box(
        modifier = modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(if (active) Lime.copy(alpha = alpha) else Muted.copy(alpha = 0.5f))
    )
}

@Composable
fun PageHeader(title: String, eyebrow: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Lime
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                color = Paper
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, Line, RoundedCornerShape(22.dp))
            .padding(horizontal = 22.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Lime.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Lime.copy(alpha = 0.7f))
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Paper)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    return DateUtils.getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
}

fun mono(value: String): String = value

val MonoFamily = FontFamily.Monospace
