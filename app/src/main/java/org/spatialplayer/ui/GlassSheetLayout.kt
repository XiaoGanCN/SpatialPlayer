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
    private fun column()=LinearLayout(context).apply {orientation=VERTICAL;id=R.id.sheet_content;setPadding(dp(16),dp(4),dp(16),dp(20))}
    var content=column()
        private set
    private var published=content
    private var pending:View?=null
    private var transitionPending=false
    internal val publishedRows:Int get()=if(published.isShown && published.alpha>.001f)published.childCount else 0
    private val pages=object:FrameLayout(context) {
        override fun onInterceptTouchEvent(event:android.view.MotionEvent)=transitionPending || super.onInterceptTouchEvent(event)
    }.apply {addView(content,FrameLayout.LayoutParams(-1,-2))}
    val scroll=ScrollView(context).apply {
        overScrollMode=View.OVER_SCROLL_NEVER;isVerticalScrollBarEnabled=false;clipToPadding=true
        setPadding(0,dp(80),0,0)
        addView(pages,LayoutParams(-1,-2))
    }
    private var closeAction=close
    private var backAction=back
    private var backButton:ImageButton?=null
    private val header=LinearLayout(context).apply {orientation=VERTICAL}
    private val fade=TopFadeBlurView(context).apply {sourceView=pages;contentOnly=true}
    private var pageGeneration=0
    private val footer=FrameLayout(context).apply {visibility=GONE;setPadding(dp(16),0,dp(16),dp(16))}
    private val body=FrameLayout(context)
    var adaptToSpace:((width:Int,height:Int)->Boolean)?=null
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
        addView(body.apply {
            addView(scroll,FrameLayout.LayoutParams(-1,-1))
            addView(fade,FrameLayout.LayoutParams(-1,dp(96)))
            addView(header,FrameLayout.LayoutParams(-1,-2))
        },LayoutParams(-1,0,1f))
        addView(footer,LayoutParams(-1,-2))
    }
    fun setFooter(view:View) {
        footer.removeAllViews()
        footer.addView(view,FrameLayout.LayoutParams(-1,-2))
        footer.visibility=VISIBLE
    }
    fun setBodyVisible(show:Boolean) {
        body.visibility=if(show)VISIBLE else GONE
        footer.setPadding(dp(16),if(show)0 else dp(12),dp(16),if(show)dp(16) else dp(12))
    }
    /** Keep the visible page attached while replacement materials compose and record. */
    fun replaceContent(resetScroll:Boolean=false,build:(LinearLayout)->Unit) {
        if(!isAttachedToWindow || published.childCount==0) {content.removeAllViews();build(content);return}
        val generation=++pageGeneration
        pending?.let {pages.removeView(it)}
        val old=published
        transitionPending=true
        val next=column().apply {alpha=0f}
        content=next
        pending=next
        pages.addView(next,FrameLayout.LayoutParams(-1,-2))
        build(next)
        GlassInstaller.apply(next)
        val publish=object:Runnable {
            override fun run() {
                if(generation!=pageGeneration || !next.isAttachedToWindow)return
                if(!next.isLaidOut || next.width==0 || next.height==0) {next.postOnAnimation(this);return}
                org.spatialplayer.ui.backdrop.NativeBackdropRenderer.prepareTree(next)
                // Publish a complete material page atomically; avoid blending two frosted scenes.
                old.visibility=GONE;next.alpha=1f
                pages.removeView(old);published=next;pending=null;transitionPending=false
                if(resetScroll)scroll.scrollTo(0,0)
                fade.invalidateContent()
            }
        }
        next.post {next.postOnAnimation(publish)}
    }
    fun configureHeader(label:String,close:()->Unit,back:(()->Unit)?) {
        title.text=label;closeAction=close;backAction=back
        backButton?.visibility=if(back!=null)VISIBLE else GONE
    }
    fun preferredHeight(width:Int):Int {
        val side=if(width>dp(500))dp(24) else dp(16)
        if(content.paddingLeft!=side)content.setPadding(side,content.paddingTop,side,content.paddingBottom)
        header.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        val top=header.measuredHeight
        if(scroll.paddingTop!=top)scroll.setPadding(0,top,0,0)
        // Frost content only as it scrolls behind the header. Resting controls stay sharp.
        fade.solidHeightPx=(top-dp(28)).coerceAtLeast(0).toFloat()
        if(fade.layoutParams.height!=top)fade.layoutParams= (fade.layoutParams as FrameLayout.LayoutParams).apply {height=top}
        content.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        footer.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        return (if(body.visibility==VISIBLE)top+content.measuredHeight else 0)+
            (if(footer.visibility==VISIBLE)footer.measuredHeight else 0)
    }
    private fun dp(value:Int)=GlassTheme.dp(context,value.toFloat())
}
