package org.spatialplayer.ui.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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

@Composable
internal fun BackdropSurface(backdrop:Backdrop,style:GlassStyle,pressed:Boolean,density:Float,touch:Offset,interactive:Boolean) {
    val shape:Shape=if(style.cornerRadiusPx>500*density)Capsule() else RoundedRectangle((style.cornerRadiusPx/density).dp)
    val light=LocalGlassAngle.current
    val scope=rememberCoroutineScope()
    val interaction=remember(scope) {InteractiveHighlight(scope)}
    LaunchedEffect(pressed) {if(interactive && pressed)interaction.pressAt(touch) else interaction.release()}
    LaunchedEffect(touch) {if(interactive && pressed)interaction.moveTo(touch)}
    // The native owner supplies centered scaling for both material and foreground. Transforming
    // only this Compose layer also shifts positionInRoot(), moving the sampled image on DOWN.
    Box(Modifier.fillMaxSize().clip(shape).drawBackdrop(
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
