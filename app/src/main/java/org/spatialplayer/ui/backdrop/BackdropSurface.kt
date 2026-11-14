package org.spatialplayer.ui.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
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
import org.spatialplayer.ui.components.InteractiveHighlight
import kotlin.math.*

@Composable
internal fun BackdropSurface(backdrop:Backdrop,style:GlassStyle,pressed:Boolean,density:Float,touch:Offset,interactive:Boolean) {
    val shape:Shape=if(style.cornerRadiusPx>500*density)Capsule() else RoundedRectangle((style.cornerRadiusPx/density).dp)
    val light=LocalGlassAngle.current
    val scope=rememberCoroutineScope()
    val interaction=remember(scope) {InteractiveHighlight(scope)}
    LaunchedEffect(pressed) {if(interactive && pressed)interaction.pressAt(touch) else interaction.release()}
    LaunchedEffect(touch) {if(interactive && pressed)interaction.moveTo(touch)}
    // Transform the whole clipped material, including the touch highlight. Putting a surface
    // fill outside the clip produces a rectangular flash during a held press.
    val transform=if(interactive)Modifier.graphicsLayer {
        val scale=1f+4.dp.toPx()/size.height*interaction.pressProgress
        val offset=interaction.offset
        val distance=size.minDimension
        translationX=distance*tanh(.05f*offset.x/distance)
        translationY=distance*tanh(.05f*offset.y/distance)
        val angle=atan2(offset.y,offset.x)
        val stretch=4.dp.toPx()/size.height
        scaleX=scale+stretch*abs(cos(angle)*offset.x/size.maxDimension)*(size.width/size.height).coerceAtMost(1f)
        scaleY=scale+stretch*abs(sin(angle)*offset.y/size.maxDimension)*(size.height/size.width).coerceAtMost(1f)
    } else Modifier
    Box(Modifier.fillMaxSize().then(transform).clip(shape).drawBackdrop(
        backdrop=backdrop,
        shape={shape},
        effects={
            vibrancy()
            blur((style.blurRadiusPx/density).coerceIn(2f,10f).dp.toPx())
            lens(12.dp.toPx(),(if(interactive)24 else 20).dp.toPx(),chromaticAberration=true)
        },
        highlight={Highlight.Default.copy(style=HighlightStyle.Default(angle=light()),alpha=.8f)},
        shadow=null,
        onDrawSurface={
            drawRect(Color(style.tint))
            if(style.body!=0)drawRect(Color(style.body))
            if(style.dimAmount>0f)drawRect(Color.Black.copy(alpha=style.dimAmount))
        }
    ).then(if(interactive)interaction.modifier else Modifier))
}
