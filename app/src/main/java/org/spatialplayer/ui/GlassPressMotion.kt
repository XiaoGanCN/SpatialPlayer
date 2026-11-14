package org.spatialplayer.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.Interpolator
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Scale the complete native control around its center; backdrop coordinates stay local. */
internal class GlassPressMotion(private val view:View) {
    private var animator:ValueAnimator?=null
    private var progress=0f
    init {
        view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View)=Unit
            override fun onViewDetachedFromWindow(v:View) {
                animator?.cancel();animator=null;progress=0f
                view.scaleX=1f;view.scaleY=1f
            }
        })
    }
    fun setPressed(pressed:Boolean) {
        if(!view.isAttachedToWindow || view.height<=0)return
        animator?.cancel()
        view.pivotX=view.width*.5f;view.pivotY=view.height*.5f
        animator=ValueAnimator.ofFloat(progress,if(pressed)1f else 0f).apply {
            duration=if(pressed)220L else 280L
            interpolator=Interpolator {t ->
                if(t>=1f)1f else (1-exp(-8f*t)*(cos(12f*t)+(2f/3f)*sin(12f*t)))
            }
            addUpdateListener {
                progress=it.animatedValue as Float
                val scale=1f+4f*view.resources.displayMetrics.density/view.height.coerceAtLeast(1)*progress
                view.scaleX=scale;view.scaleY=scale
                GlassBackdrop.materialChanged(view)
            }
            start()
        }
    }
}
