package org.spatialplayer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.shapes.RoundedRectangle
import org.spatialplayer.ui.components.LiquidButton
import org.spatialplayer.ui.components.LiquidSlider
import org.spatialplayer.ui.components.LiquidToggle
import org.spatialplayer.ui.GlassSlider
import org.spatialplayer.ui.GlassSwitch
import kotlin.math.roundToInt
import kotlin.math.sin

/** Deliberately isolated, with no persisted playback settings or changes to the HDR surface. */
class GlassPlaygroundActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply { setContent { MaterialTheme(colors = darkColors(), typography = androidx.compose.material.Typography(defaultFontFamily = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.inter)))) { Playground { finish() } } } })
    }
}

@Composable
private fun Playground(close: () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    var moving by rememberSaveable { mutableStateOf(android.animation.ValueAnimator.areAnimatorsEnabled()) }
    var dispersion by rememberSaveable { mutableStateOf(true) }
    var toggled by rememberSaveable { mutableStateOf(true) }
    var currentToggled by rememberSaveable { mutableStateOf(true) }
    var value by rememberSaveable { mutableFloatStateOf(.5f) }
    var currentValue by rememberSaveable { mutableFloatStateOf(.5f) }
    var frost by rememberSaveable { mutableFloatStateOf(4f) }
    var refraction by rememberSaveable { mutableFloatStateOf(20f) }
    val view = LocalView.current
    val phase = if (moving) {
        val animation = rememberInfiniteTransition(label = "backdrop motion")
        animation.animateFloat(0f, 6.283185f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "phase").value
    } else 0f

    Box(Modifier.fillMaxSize().background(Color(0xFF101522))) {
        Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            drawRect(Brush.linearGradient(listOf(Color(0xFF18264A), Color(0xFF3D183C), Color(0xFF101522))))
            val colors = listOf(Color(0xFF428FFF), Color(0xFFF476AF), Color(0xFF54D8D3), Color(0xFFFFBB7C))
            repeat(8) { i ->
                val x = size.width * (.15f + (i % 3) * .35f) + sin(phase + i) * size.width * .15f
                val y = size.height * ((i + .5f) / 8f) + sin(phase * .7f + i) * 55.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(colors[i % 4].copy(.8f), colors[i % 4].copy(0f)), Offset(x,y), 140.dp.toPx()), 140.dp.toPx(), Offset(x,y))
            }
            // Fine lines make refraction and dispersion visible without an HDR or video capture.
            repeat(20) { i ->
                val x = i * size.width / 12f + sin(phase) * 32.dp.toPx()
                drawLine(Color.White.copy(.32f), Offset(x,0f), Offset(x-size.height*.2f,size.height), 2.dp.toPx())
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Glass playground", Modifier.weight(1f), fontSize = 24.sp, color = Color.White)
                LiquidButton(close, backdrop) { Text("Done", color = Color.White) }
            }
            Text("Backdrop · Kyant0\nCompare the optics and press animation. These controls do not change your settings.", color = Color(0xFFDEE5F4), fontSize = 14.sp)
            Column(Modifier.fillMaxWidth().drawBackdrop(backdrop, shape = { RoundedRectangle(28.dp) }, effects = {
                blur(frost.dp.toPx()); lens(14.dp.toPx(), refraction.dp.toPx(), chromaticAberration = dispersion)
            }).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Light in motion", color = Color.White, fontSize = 23.sp)
                Text("Continuous corners · dispersion · refraction", color = Color.White, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Library switch", Modifier.weight(1f), color = Color.White)
                    LiquidToggle({ toggled }, { toggled = it }, backdrop,
                        Modifier.size(80.dp,48.dp).semantics { contentDescription = "Library switch"; toggleableState = if (toggled) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off })
                }
                Text("Library slider · ${(value*100).roundToInt()}%", color = Color.White)
                LiquidSlider({ value }, { value = it }, 0f..1f, .001f, backdrop,
                    Modifier.height(48.dp).semantics {
                        contentDescription = "Library slider"
                    })
                LiquidButton({ moving = !moving }, backdrop, Modifier.fillMaxWidth()) { Text(if (moving) "Pause backdrop" else "Animate backdrop", color = Color.White) }
            }
            Column(Modifier.fillMaxWidth().drawBackdrop(backdrop, shape = { RoundedRectangle(28.dp) }, effects = { blur(12.dp.toPx()) }).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Production controls", color = Color.White, fontSize = 18.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Our switch", Modifier.weight(1f), color = Color.White)
                    AndroidView(factory = { context -> GlassSwitch(context).apply { org.spatialplayer.ui.GlassInstaller.watch(this); isChecked = currentToggled; setOnCheckedChangeListener { _, checked -> currentToggled = checked } } }, update = { control -> control.invalidate() }, modifier = Modifier.width(76.dp).height(48.dp))
                }
                Text("Our slider · ${(currentValue*100).roundToInt()}%", color = Color.White)
                AndroidView(factory = { context -> GlassSlider(context).apply { org.spatialplayer.ui.GlassInstaller.watch(this); max = 100; progress = (currentValue*100).roundToInt(); setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, user: Boolean) { if (user) currentValue = progress/100f }
                    override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: android.widget.SeekBar?) = Unit
                }) } }, update = { control -> control.invalidate() }, modifier = Modifier.fillMaxWidth().height(48.dp))
                Text("Production controls use the same library with sensor-driven highlights.", color = Color(0xFFDEE5F4), fontSize = 13.sp)
            }
            Text("Optics", color = Color.White, fontSize = 18.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Dispersion", Modifier.weight(1f), color = Color.White)
                LiquidToggle({ dispersion }, { dispersion = it }, backdrop, Modifier.size(80.dp,48.dp))
            }
            Text("Blur · ${frost.roundToInt()} dp", color = Color.White)
            LiquidSlider({ frost }, { frost = it }, 0f..24f, .1f, backdrop, Modifier.height(48.dp).semantics { contentDescription = "Blur" })
            Text("Refraction · ${refraction.roundToInt()} dp", color = Color.White)
            LiquidSlider({ refraction }, { refraction = it }, 0f..40f, .1f, backdrop, Modifier.height(48.dp).semantics { contentDescription = "Refraction" })
            Text("Backdrop 1.0.6 · Apache 2.0\nProduction playback and its HDR surface use the current renderer.", color = Color(0xFFC4CCDE), fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }
    }
}
