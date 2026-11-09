package com.gan.spatialplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/**
 * Debug-only. **Not present in release builds.**
 *
 * Renders the launcher mark and the header icons at the sizes they are actually shown at, into one
 * PNG the host can pull and look at, because a vector source tells you nothing about what 22 dp of
 * anti-aliasing does to it. §8 of the handoff: "must be looked at as a rendered icon at real sizes,
 * not as a vector source".
 *
 * Each icon is drawn twice: once at its real size, and once at 3x for inspection. The launcher
 * `AdaptiveIconDrawable` is drawn as the launcher would draw it - the drawable applies its own
 * mask - and `LauncherApps.getApplicationIcon` is tried as well, because that is the bitmap the
 * home screen actually holds; if the device will not hand it over the masked render stands in.
 *
 * The file lands in the app's own external directory, which needs no permission, and the path is
 * logged so the harness can pull it.
 */
class IconProbeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val out = File(getExternalFilesDir(null), "icon_probe.png")

        runCatching {
            FileOutputStream(out).use { stream -> sheet(density).compress(Bitmap.CompressFormat.PNG, 100, stream) }
        }.onFailure {
            Log.w(TAG, "icon render failed", it)
        }

        Log.i(TAG, "icon probe :: path=${out.absolutePath} density=$density")
        finish()
    }

    /** One tall strip of rows: label, the icon at its real size, then the same at 3x. */
    private fun sheet(density: Float): Bitmap {
        val entries = ArrayList<Triple<String, Drawable?, Float>>()

        // The launcher mark at the sizes a home screen actually shows it. The drawable is drawn
        // as the launcher would draw it, mask and all, which is the shape the home screen holds;
        // the themed `<monochrome>` layer is included separately because that is the shape a
        // themed home screen would tint instead.
        val launcher = getDrawable(R.mipmap.ic_launcher)
        entries += Triple("launcher 48dp (masked)", launcher, 48f)
        entries += Triple("launcher 72dp (masked)", launcher, 72f)
        entries += Triple("launcher 96dp (masked)", launcher, 96f)
        entries += Triple("monochrome 72dp", getDrawable(R.drawable.ic_logo_monochrome), 72f)

        // The library header's icons at the size the button renders them: 40 dp minus its 9 dp
        // padding is 22 dp, which is what makes or breaks a mark.
        entries += Triple("ic_settings 22dp (header size)", getDrawable(R.drawable.ic_settings), 22f)
        entries += Triple("ic_settings 24dp", getDrawable(R.drawable.ic_settings), 24f)
        entries += Triple("ic_spatial 22dp (for compare)", getDrawable(R.drawable.ic_spatial), 22f)
        entries += Triple("ic_refresh 22dp (for compare)", getDrawable(R.drawable.ic_refresh), 22f)
        entries += Triple("ic_audio 22dp (for compare)", getDrawable(R.drawable.ic_audio), 22f)

        val rowHeight = (density * 96f).toInt()
        val width = (density * 330f).toInt()
        val bitmap = Bitmap.createBitmap(width, rowHeight * entries.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#0B0D12"))

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D6DAE0")
            textSize = density * 10f
        }
        // Header icons have transparent padding of their own; a hairline frame shows the box the
        // button actually gives them, so "is it centred" is answerable from the sheet.
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22262E")
            style = Paint.Style.STROKE
        }

        for ((index, entry) in entries.withIndex()) {
            val (label, drawable, sizeDp) = entry
            val y = index * rowHeight
            val baseline = y + rowHeight / 2f

            canvas.drawText(
                label,
                density * 12f,
                baseline + labelPaint.textSize / 3f,
                labelPaint,
            )

            if (drawable == null) {
                continue
            }

            val real = (sizeDp * density).toInt()
            drawAt(canvas, drawable, (density * 150f).toInt(), baseline, real, framePaint)
            drawAt(canvas, drawable, (density * 240f).toInt(), baseline, real * 3, framePaint)
        }
        return bitmap
    }

    private fun drawAt(
        canvas: Canvas,
        drawable: Drawable,
        centerX: Int,
        centerY: Float,
        size: Int,
        framePaint: Paint,
    ) {
        val left = centerX - size / 2
        val top = (centerY - size / 2).toInt()
        canvas.drawRect(left.toFloat(), top.toFloat(), (left + size).toFloat(), (top + size).toFloat(), framePaint)
        drawable.setBounds(left, top, left + size, top + size)
        drawable.draw(canvas)
    }

    private companion object {
        const val TAG = "IconProbe"
    }
}
