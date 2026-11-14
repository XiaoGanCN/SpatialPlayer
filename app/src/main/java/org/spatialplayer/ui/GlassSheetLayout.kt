package org.spatialplayer.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.spatialplayer.R

/** Adaptive clipped Backdrop shell. Content scrolls under a progressive header instead of a slab. */
internal class GlassSheetLayout(context:Context,label:String,close:()->Unit,back:(()->Unit)?=null):LinearLayout(context) {
    val title=TextView(context).apply {text=label;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;textSize=18f;typeface=UiTypography.body(context);setTextColor(Color.WHITE)}
    val content=LinearLayout(context).apply {orientation=VERTICAL;id=R.id.sheet_content;setPadding(dp(16),dp(4),dp(16),dp(20))}
    val scroll=ScrollView(context).apply {
        overScrollMode=View.OVER_SCROLL_NEVER;isVerticalScrollBarEnabled=false;clipToPadding=false
        setPadding(0,dp(80),0,0)
        addView(content,LayoutParams(-1,-2))
    }
    private var closeAction=close
    private var backAction=back
    private var backButton:ImageButton?=null
    private val header=LinearLayout(context).apply {orientation=VERTICAL}
    private val fade=TopFadeBlurView(context).apply {sourceView=scroll}
    init {
        orientation=VERTICAL;id=R.id.sheet_panel
        setTag(R.id.glass_role,R.id.glass_role_sheet)
        setBackgroundResource(R.drawable.bg_glass_panel)
        outlineProvider=object:ViewOutlineProvider(){override fun getOutline(view:View,outline:Outline){outline.setPath(SmoothCorners.path(RectF(0f,0f,width.toFloat(),height.toFloat()),dp(30).toFloat()))}}
        clipToOutline=true
        header.addView(View(context).apply {background=GlassTheme.surface(context,3f,true)},LayoutParams(dp(36),dp(4)).apply {gravity=Gravity.CENTER_HORIZONTAL;topMargin=dp(10);bottomMargin=dp(6)})
        val row=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(4),dp(16),dp(12))}
        fun button(icon:Int,tagName:String,description:String,action:()->Unit)=ImageButton(context).apply {
            tag=tagName;setTag(R.id.glass_role,R.id.glass_role_button);background=GlassTheme.control(context)
            setImageResource(icon);setColorFilter(Color.WHITE);contentDescription=description
            setPadding(dp(12),dp(12),dp(12),dp(12));setOnClickListener {action()}
        }
        backButton=button(R.drawable.ic_chevron_left,"sheet_back","Back to playback controls") {backAction?.invoke()}.apply {visibility=if(back!=null)VISIBLE else GONE}
        row.addView(backButton,LayoutParams(dp(44),dp(44)).apply {marginEnd=dp(12)})
        row.addView(title,LayoutParams(0,-2,1f))
        row.addView(button(R.drawable.ic_close,"sheet_close","Close") {closeAction()},LayoutParams(dp(44),dp(44)).apply {marginStart=dp(12)})
        header.addView(row,LayoutParams(-1,-2))
        addView(FrameLayout(context).apply {
            addView(scroll,FrameLayout.LayoutParams(-1,-1))
            addView(fade,FrameLayout.LayoutParams(-1,dp(96)))
            addView(header,FrameLayout.LayoutParams(-1,-2))
        },LayoutParams(-1,-1))
    }
    fun configureHeader(label:String,close:()->Unit,back:(()->Unit)?) {
        title.text=label;closeAction=close;backAction=back
        backButton?.visibility=if(back!=null)VISIBLE else GONE
        scroll.scrollTo(0,0)
    }
    fun preferredHeight(width:Int):Int {
        val side=if(width>dp(500))dp(24) else dp(16)
        if(content.paddingLeft!=side)content.setPadding(side,content.paddingTop,side,content.paddingBottom)
        header.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        val top=header.measuredHeight
        if(scroll.paddingTop!=top)scroll.setPadding(0,top,0,0)
        fade.solidHeightPx=top.toFloat()
        if(fade.layoutParams.height!=top+dp(32))fade.layoutParams= (fade.layoutParams as FrameLayout.LayoutParams).apply {height=top+dp(32)}
        content.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        return top+content.measuredHeight
    }
    private fun dp(value:Int)=GlassTheme.dp(context,value.toFloat())
}
