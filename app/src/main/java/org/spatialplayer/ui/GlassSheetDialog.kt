package org.spatialplayer.ui

import android.app.Dialog
import android.content.Context
import android.widget.FrameLayout
import android.view.Gravity
import org.spatialplayer.R

/** One stable window and glass shell survive player drawer navigation. */
internal class GlassSheetDialog(context:Context):Dialog(context,R.style.Theme_SpatialPlayer_Glass) {
    var navigateBack:(()->Unit)?=null
    init {window?.setWindowAnimations(0)}
    @Deprecated("Platform callback") override fun onBackPressed() {navigateBack?.invoke() ?: cancel()}
}
internal data class SheetWindow(val dialog:GlassSheetDialog,val panel:GlassSheetLayout)
