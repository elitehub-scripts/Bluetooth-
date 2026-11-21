package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.core.models.FileTransferStatus
import com.example.core.models.MessageDeliveryStatus
import com.example.ui.theme.AmberRelay
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.EmeraldVerified
import com.example.ui.theme.JetBrainsMonoFamily
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MeshSectionCard(
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        content()
    }
}

@Composable
fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun MessageStatusPill(status: MessageDeliveryStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        MessageDeliveryStatus.CREATED -> MaterialTheme.colorScheme.onSurfaceVariant
        MessageDeliveryStatus.WAITING_FOR_RELAY -> AmberRelay
        MessageDeliveryStatus.RELAYED -> ElectricBlue
        MessageDeliveryStatus.RECEIVED_BY_DESTINATION,
        MessageDeliveryStatus.DELIVERED -> EmeraldVerified
        MessageDeliveryStatus.EXPIRED,
        MessageDeliveryStatus.FAILED -> CoralAlert
    }
    StatusPill(label = status.label, color = color, modifier = modifier)
}

@Composable
fun FileStatusPill(status: FileTransferStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        FileTransferStatus.QUEUED -> AmberRelay
        FileTransferStatus.TRANSFERRING -> MaterialTheme.colorScheme.primary
        FileTransferStatus.PAUSED -> ElectricBlue
        FileTransferStatus.COMPLETED -> EmeraldVerified
        FileTransferStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        FileTransferStatus.FAILED,
        FileTransferStatus.INTEGRITY_FAILED -> CoralAlert
    }
    StatusPill(label = status.label, color = color, modifier = modifier)
}

@Composable
fun RadarPulseIndicator(
    active: Boolean,
    color: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 44.dp
) {
    val transition = rememberInfiniteTransition(label = "radar")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )

    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val maxR = this.size.minDimension / 2f
            if (active) {
                drawCircle(
                    color = color.copy(alpha = (1f - progress) * 0.45f),
                    radius = maxR * (0.35f + 0.65f * progress),
                    center = center,
                    style = Stroke(width = 3f)
                )
            }
            drawCircle(
                color = if (active) color else color.copy(alpha = 0.35f),
                radius = maxR * 0.28f,
                center = center
            )
        }
    }
}

@Composable
fun EmptyMeshState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    MeshSectionCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

@Composable
fun QrMatrixView(
    payload: String,
    size: Dp = 200.dp,
    modifier: Modifier = Modifier
) {
    val matrix = remember(payload) {
        if (payload.isBlank()) null
        else {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 256, 256, hints)
        }
    }

    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            if (matrix != null) {
                val cellW = this.size.width / matrix.width.toFloat()
                val cellH = this.size.height / matrix.height.toFloat()
                for (y in 0 until matrix.height) {
                    for (x in 0 until matrix.width) {
                        if (matrix[x, y]) {
                            drawRect(
                                color = Color(0xFF071324),
                                topLeft = Offset(x * cellW, y * cellH),
                                size = Size(cellW + 0.5f, cellH + 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MonospaceBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

fun formatTimestamp(ts: Long): String {
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return sdf.format(Date(ts))
}

fun formatByteSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatDurationRemaining(expiresAt: Long, now: Long = System.currentTimeMillis()): String {
    val diff = expiresAt - now
    if (diff <= 0) return "Expired"
    val hours = diff / (1000 * 60 * 60)
    val mins = (diff / (1000 * 60)) % 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}
