package app.kerkida.ui.intro

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import app.kerkida.R
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

internal enum class Scarf(
    /** The end that arrives first, then the end that trails behind and flutters. */
    val leadX: Float,
    val leadY: Float,
    val tailX: Float,
    val tailY: Float,
    val fromX: Float,
    val fromY: Float,
    val startsAt: Float,
) {
    UPRIGHT(39f, 82f, 37f, 25f, -0.10f, -1f, 0f),
    DIAGONAL(41f, 65f, 72f, 28f, 0.78f, -0.62f, 0.10f),
    LOWER(50f, 52f, 72f, 82f, 0.68f, 0.73f, 0.20f),
}

internal class LogoPart(val scarf: Scarf, fill: Long?, stroke: Long?, val strokeWidth: Float, data: String) {
    val fill = fill?.let(::Color)
    val stroke = stroke?.let(::Color)
    val commands: List<Pair<Char, FloatArray>> = Command.findAll(data).map { found ->
        found.groupValues[1][0] to Number.findAll(found.groupValues[2]).map { it.value.toFloat() }.toList().toFloatArray()
    }.toList()

    private companion object {
        val Command = Regex("([MLQZ])([^MLQZ]*)")
        val Number = Regex("-?\\d+(?:\\.\\d+)?")
    }
}

/** How each point of a scarf moves. At [LogoMotion.ARRIVED] every point is exactly where the logo has it. */
internal object LogoMotion {
    const val ARRIVED = 0.70f
    private const val SCARF_SPAN = ARRIVED - 0.20f

    private fun easeOut(value: Float) = 1f - (1f - value).pow(3)

    private fun smooth(from: Float, to: Float, value: Float): Float {
        val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun move(scarf: Scarf, x: Float, y: Float, time: Float, travel: Float): Offset {
        val raw = ((time - scarf.startsAt) / SCARF_SPAN).coerceIn(0f, 1f)
        if (raw >= 1f) return Offset(x, y)
        val axisX = scarf.tailX - scarf.leadX
        val axisY = scarf.tailY - scarf.leadY
        val length = hypot(axisX, axisY)
        val along = (((x - scarf.leadX) * axisX + (y - scarf.leadY) * axisY) / (length * length)).coerceIn(0f, 1f)
        // The tail follows a moment after the lead, so the cloth looks drawn along rather than slid.
        val pulled = easeOut(((raw - along * 0.16f) / 0.84f).coerceIn(0f, 1f))
        val away = (1f - pulled) * travel
        val flutter = (1f - smooth(0.62f, 1f, raw)) * (0.18f + 0.82f * along) * 3.4f *
            sin(2f * PI.toFloat() * (1.25f * along - 2.4f * raw))
        return Offset(
            x + scarf.fromX * away - axisY / length * flutter,
            y + scarf.fromY * away + axisX / length * flutter,
        )
    }
}

private const val INTRO_MS = 2_350
private const val FADE_FROM = 0.87f
private const val MAX_STEP_MS = 34L

/**
 * The opening: three scarves are drawn in and flutter until they settle into the logo.
 * [onSettled] fires once the logo stands still, which is when the app behind it starts drawing.
 */
@Composable
fun LogoIntro(isReady: Boolean, onSettled: () -> Unit, onDone: () -> Unit) {
    val settled by rememberUpdatedState(onSettled)
    val done by rememberUpdatedState(onDone)
    val ready by rememberUpdatedState(isReady)
    var clock by remember { mutableFloatStateOf(0f) }
    val navy = colorResource(R.color.kerkida_navy)
    val path = remember { Path() }
    var skipped by remember { mutableStateOf(false) }
    val resolver = LocalContext.current.contentResolver
    val motionOff = remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }

    /** A busy frame counts as one short step, so a stutter slows the scarves instead of skipping their flight. */
    suspend fun playTo(limit: Float) {
        var last = withFrameMillis { it }
        while (clock < limit) {
            val now = withFrameMillis { it }
            clock = minOf(limit, clock + minOf(now - last, MAX_STEP_MS) / INTRO_MS.toFloat())
            last = now
        }
    }
    LaunchedEffect(skipped) {
        if (skipped || motionOff) clock = maxOf(clock, LogoMotion.ARRIVED) else playTo(LogoMotion.ARRIVED)
        settled()
        // On a slow phone the finished logo simply waits here until the app is ready behind it.
        snapshotFlow { ready }.first { it }
        // The app's first draw lands on these frames, behind the still logo. The fade starts right after it.
        repeat(2) { withFrameMillis { } }
        clock = maxOf(clock, FADE_FROM)
        playTo(1f)
        done()
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val leaving = ((clock - FADE_FROM) / (1f - FADE_FROM)).coerceIn(0f, 1f)
                alpha = 1f - leaving * leaving
                val grow = 1f + 0.05f * leaving
                scaleX = grow
                scaleY = grow
            }
            .background(navy)
            .pointerInput(Unit) {
                // A tap skips the opening.
                awaitEachGesture {
                    awaitFirstDown().consume()
                    skipped = true
                }
            },
    ) {
        val time = clock
        val unit = 196.dp.toPx() / 108f
        val travel = size.maxDimension / unit
        translate(size.width / 2f - 54f * unit, size.height / 2f - 54f * unit) {
            scale(unit, pivot = Offset.Zero) {
                // The same scale the logo's own vector applies around its centre.
                scale(0.82f, pivot = Offset(54f, 54f)) {
                    LogoParts.forEach { part ->
                        path.rewind()
                        part.commands.forEach { (command, values) ->
                            fun point(index: Int) = LogoMotion.move(part.scarf, values[index], values[index + 1], time, travel)
                            when (command) {
                                'M' -> point(0).let { path.moveTo(it.x, it.y) }
                                'L' -> for (index in values.indices step 2) point(index).let { path.lineTo(it.x, it.y) }
                                'Q' -> for (index in values.indices step 4) {
                                    val control = point(index)
                                    val end = point(index + 2)
                                    path.quadraticTo(control.x, control.y, end.x, end.y)
                                }
                                'Z' -> path.close()
                            }
                        }
                        part.fill?.let { drawPath(path, it) }
                        part.stroke?.let {
                            drawPath(path, it, style = Stroke(part.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            }
        }
    }
}
