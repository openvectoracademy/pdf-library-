package com.pdflib

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import kotlinx.coroutines.delay

private val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val Settle = CubicBezierEasing(0.25f, 1.4f, 0.4f, 1f)
private val ShelfC = Color(0xFFAD3E2A)
private val Book1 = Color(0xFFA93C2B)
private val Book2 = Color(0xFFD5644A)
private val Book3 = Color(0xFF9A3F2E)

private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)

/** The logo, drawn in its own artwork coordinates (721 x 572) so each book can move on its own. */
private fun DrawScope.drawLogo(t: Float, cream: Color) {
    val s = size.width / 721f
    scale(s, s, Offset.Zero) {
        translate(-267f, -325f) {
            // shelf grows out from the middle
            val sh = Ease.transform(seg(t, 0f, 0.28f))
            scale(sh, 1f, Offset(627.5f, 857f)) {
                drawRoundRect(ShelfC, Offset(267f, 818f), Size(721f, 79f), CornerRadius(39.5f))
            }
            // book 1 rises
            val e1 = Ease.transform(seg(t, 0.18f, 0.48f))
            translate(0f, (1f - e1) * 90f) {
                drawRoundRect(Book1.copy(alpha = e1), Offset(377f, 325f), Size(140f, 478f), CornerRadius(16f))
                val k = cream.copy(alpha = e1)
                drawRect(k, Offset(377f, 383f), Size(140f, 19f))
                drawRect(k, Offset(377f, 424f), Size(140f, 19f))
                drawRect(k, Offset(377f, 725f), Size(140f, 19f))
                drawCircle(k, 35f, Offset(447f, 572f))
            }
            // book 2 rises
            val e2 = Ease.transform(seg(t, 0.30f, 0.60f))
            translate(0f, (1f - e2) * 90f) {
                drawRoundRect(Book2.copy(alpha = e2), Offset(528f, 376f), Size(133f, 427f), CornerRadius(16f))
                drawRoundRect(cream.copy(alpha = e2), Offset(564f, 460f), Size(61f, 217f), CornerRadius(10f))
            }
            // book 3 rises, then leans into place
            val e3 = Ease.transform(seg(t, 0.40f, 0.66f))
            val lean = -17.6f * Settle.transform(seg(t, 0.46f, 0.82f))
            translate(0f, (1f - e3) * 90f) {
                rotate(lean, Offset(843f, 782f)) {
                    drawRoundRect(Book3.copy(alpha = e3), Offset(780.5f, 385f), Size(125f, 397f), CornerRadius(16f))
                    val k = cream.copy(alpha = e3)
                    drawRect(k, Offset(780.5f, 443f), Size(125f, 19f))
                    drawRect(k, Offset(780.5f, 712f), Size(125f, 19f))
                }
            }
        }
    }
}

/** Short branded intro on a cold start. Ends as soon as the library is ready; a tap skips it. */
@Composable
fun Splash(ready: Boolean, onDone: () -> Unit) {
    val pal = LocalPal.current
    val t = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val readyNow by rememberUpdatedState(ready)
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(950, easing = LinearEasing))
        var waited = 0
        while (!readyNow && waited < 1500) { delay(50); waited += 50 }
        delay(220)
        fade.animateTo(0f, tween(240))
        onDone()
    }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(pal.bg)
            .pointerInput(Unit) { detectTapGestures { onDone() } },
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.offset(y = (-28).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.width(190.dp).aspectRatio(721f / 572f)) { drawLogo(t.value, pal.bg) }
            Spacer(Modifier.height(26.dp))
            Text(
                "Shelf", fontFamily = Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold,
                fontSize = 40.sp, color = pal.accent,
                modifier = Modifier.graphicsLayer {
                    val a = Ease.transform(seg(t.value, 0.62f, 0.9f))
                    alpha = a; translationY = (1f - a) * 14f
                }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Your PDFs, in order.", fontSize = 14.sp, color = pal.soft, letterSpacing = 0.4.sp,
                modifier = Modifier.graphicsLayer { alpha = Ease.transform(seg(t.value, 0.78f, 1f)) }
            )
        }
    }
}
