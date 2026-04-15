package com.example.xirolite

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlin.math.min

data class StickPosition(
    val x: Float,
    val y: Float
)

data class RemoteStickEstimate(
    val left: StickPosition,
    val right: StickPosition,
    val label: String
)

@Composable
fun RemoteOverlay(
    estimate: RemoteStickEstimate,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.4f),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.drone_remote),
                    contentDescription = "XIRO remote",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                Canvas(modifier = Modifier.fillMaxSize()) {

                    val w = size.width
                    val h = size.height

                    val leftCenter = Offset(w * 0.265f, h * 0.36f)
                    val rightCenter = Offset(w * 0.735f, h * 0.36f)

                    val radius = min(w, h) * 0.055f
                    val dotRadius = min(w, h) * 0.02f

                    val leftDot = Offset(
                        leftCenter.x + estimate.left.x * radius,
                        leftCenter.y + estimate.left.y * radius
                    )

                    val rightDot = Offset(
                        rightCenter.x + estimate.right.x * radius,
                        rightCenter.y + estimate.right.y * radius
                    )

                    drawCircle(
                        color = Color.Red,
                        radius = dotRadius,
                        center = leftDot
                    )

                    drawCircle(
                        color = Color.Blue,
                        radius = dotRadius,
                        center = rightDot
                    )
                }
            }

            Text(
                text = "Remote: ${estimate.label}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}