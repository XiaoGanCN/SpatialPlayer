package org.spatialplayer.ui.backdrop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import org.spatialplayer.ui.GlassStyle

@Composable
internal fun BackdropSurface(backdrop: Backdrop, style: GlassStyle, pressed: Boolean, density: Float, touch: androidx.compose.ui.geometry.Offset) {
    val engagement by animateFloatAsState(if(pressed)1f else 0f,spring(.8f,650f),label="glass press")
    val light=LocalGlassAngle.current
    Box(Modifier.fillMaxSize().drawBackdrop(backdrop,
        shape={ if(style.cornerRadiusPx>500*density) Capsule() else RoundedRectangle((style.cornerRadiusPx/density).dp) },
        effects={
            vibrancy()
            blur((style.blurRadiusPx/density).coerceIn(2f,10f).dp.toPx())
            lens(12.dp.toPx(),(20+engagement*6).dp.toPx(),chromaticAberration=true)
        },
        highlight={Highlight.Default.copy(style=HighlightStyle.Default(angle=light()),alpha=.8f)},
        shadow=null,
        onDrawSurface={
            drawRect(Color(style.tint))
            if(engagement>.001f)drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(Color.White.copy(alpha=.10f*engagement),Color.Transparent),touch,size.minDimension*1.5f),size.minDimension*1.5f,touch)
            if(style.body!=0)drawRect(Color(style.body))
            if(style.dimAmount>0f)drawRect(Color.Black.copy(alpha=style.dimAmount))
        }))
}
