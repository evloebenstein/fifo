package com.fifo.voicepipeline.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fifo.voicepipeline.ui.theme.FifoColors

@Composable
fun BreathingExerciseDialog(
    onDismiss: () -> Unit
) {
    var phase by remember { mutableStateOf("Inhala profundamente...") }
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")

    val circleScale by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "circle_scale"
    )

    LaunchedEffect(circleScale) {
        phase = if (circleScale > 0.8f) "Sostén el aire..." else if (circleScale < 0.7f) "Inhala suavemente..." else "Exhala con calma..."
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = FifoColors.DarkBackground,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Baja el ritmo",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Box(
                    modifier = Modifier.size(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Círculo exterior expansible
                    Box(
                        modifier = Modifier
                            .size(180.dp * circleScale)
                            .background(FifoColors.BlueAccent.copy(alpha = 0.25f), CircleShape)
                    )
                    // Círculo central con Fifo
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .background(FifoColors.DarkInputBg, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        FifoFace(size = 70.dp, isDarkTheme = true)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = phase,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FifoColors.BlueAccent,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Tómate este momento para ti. Sigue el ritmo suave de Fifo.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FifoColors.DarkButtonPrimary,
                        contentColor = FifoColors.DarkButtonText
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Me siento mejor", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
