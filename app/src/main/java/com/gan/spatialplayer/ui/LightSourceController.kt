package com.gan.spatialplayer.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The specular highlight follows the device, because the light is fixed in the world.
 *
 * Liquid Glass is lit by a light that does not move with the phone: tilt the glass and the bright
 * edges travel across it. Everything in this app used to be lit from a constant direction, which
 * reads as a printed gradient rather than as a surface - the highlight stays welded to the same
 * corner however you hold the device.
 *
 * Gravity gives the world's "up" in screen coordinates, and the light comes from up there, so the
 * screen-space light direction is that vector rotated into the view's frame. Three details matter
 * and each was measured in the reference implementation this is ported from:
 *
 * - **Screen rotation is not the same as device rotation.** The gravity vector is in device
 *   coordinates; a landscape phone reports gravity along a different axis than a portrait one, so
 *   the vector is remapped by `display.rotation` before it is used.
 * - **Lying flat says nothing.** Held face-up on a table the planar component collapses, the
 *   direction becomes noise, and the highlight jitters. Below a threshold the direction blends back
 *   to the default, weighted by how upright the device is, so the transition is continuous.
 * - **A redraw per sensor sample would be wasteful.** The direction is low-pass filtered and only
 *   the views that actually need it are invalidated, and only when the direction has moved enough
 *   to be visible.
 *
 * This is a nicety, not a function: with no gravity sensor, or in power-save, or with the
 * accessibility "remove animations" flag set, [lightFor] returns the caller's default and nothing is
 * registered at all.
 */
object LightSourceController : SensorEventListener {

    /**
     * The direction used when the device's attitude is unknown.
     *
     * Left-above, about 30 degrees off horizontal - the two bright edges of iOS 26 glass peak there.
     * Not a 45-degree diagonal, which is what most "glass" gradients assume and what this app used
     * before: at 45 degrees the two lobes land on the corners and the rim reads as a vignette.
     */
    const val DEFAULT_X = 0.866f
    const val DEFAULT_Y = 0.5f

    /** Low-pass coefficient at `SENSOR_DELAY_GAME` (~50 Hz). */
    private const val SMOOTHING = 0.14f

    /** Direction change below this is invisible on a rim light; do not repaint for it. */
    private const val NOTIFY_EPSILON = 0.004f

    /** Below this planar gravity the device is close enough to flat that the direction is noise. */
    private const val FLAT_THRESHOLD = 0.5f

    @Volatile
    private var lightX = DEFAULT_X

    @Volatile
    private var lightY = DEFAULT_Y

    private val listeners = LinkedHashSet<View>()
    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null
    private var registered = false
    private var lastNotifiedX = DEFAULT_X
    private var lastNotifiedY = DEFAULT_Y
    private var lastLoggedAt = 0L
    internal var planarGravity = 0f
        private set

    /** The current direction, or `null` when this device cannot track the light. */
    fun lightDirection(): Pair<Float, Float>? {
        if (!registered) return null
        return lightX to lightY
    }

    /**
     * The direction [view] should be lit from, falling back to [defaultX]/[defaultY].
     *
     * Callers pass their own default so a surface that wants a different fixed light keeps it.
     */
    fun lightFor(view: View, defaultX: Float, defaultY: Float): Pair<Float, Float> {
        register(view)
        val live = lightDirection() ?: return defaultX to defaultY
        return live
    }

    /** Registers [view] as needing a redraw when the light moves. Idempotent. */
    fun register(view: View) {
        if (useless(view.context)) return
        synchronized(listeners) {
            if (!listeners.add(view)) return
            if (registered) return
            val manager = view.context.applicationContext
                .getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (manager == null) {
                Log.w(TAG, "no SensorManager; the light will stay fixed")
                return
            }
            val found = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (found == null) {
                Log.w(TAG, "no gravity or accelerometer; the light will stay fixed")
                return
            }
            manager.registerListener(this, found, SensorManager.SENSOR_DELAY_GAME)
            sensorManager = manager
            sensor = found
            registered = true
            Log.i(TAG, "sensor light registered on ${found.name}")
        }
    }

    /** One line for a log: whether the light is live, and how many panes are listening. */
    fun status(): String = synchronized(listeners) {
        if (!registered) {
            "off (${listeners.size} pane(s) waiting)"
        } else {
            "live on ${sensor?.name} for ${listeners.size} pane(s)"
        }
    }

    /** Drops [view]; the sensor is released with the last one, so an idle screen costs nothing. */
    fun unregister(view: View) {
        synchronized(listeners) {
            listeners.remove(view)
            if (listeners.isEmpty() && registered) {
                sensorManager?.unregisterListener(this)
                sensorManager = null
                sensor = null
                registered = false
                // Back to the fixed light, or the last tilted direction would freeze into the UI.
                lightX = DEFAULT_X
                lightY = DEFAULT_Y
                lastNotifiedX = DEFAULT_X
                lastNotifiedY = DEFAULT_Y
            }
        }
    }

    /** True when tracking the light would cost more than it is worth. */
    private fun useless(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isPowerSaveMode == true) return true
        // "Remove animations" (developer option / accessibility) is the flag that means "do not move
        // things on their own". A highlight that slides as the phone is turned is exactly that, and it
        // is decoration only - so it is also the flag the press spring is skipped on.
        return animationsDisabled(context)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 2) return
        val gx = event.values[0]
        val gy = event.values[1]

        // Device axes -> screen axes. A landscape phone reports world-up on a different axis.
        val rotation = synchronized(listeners) {
            listeners.firstOrNull()?.display?.rotation ?: Surface.ROTATION_0
        }
        val ux: Float
        val uy: Float
        when (rotation) {
            Surface.ROTATION_90 -> {
                ux = -gy
                uy = gx
            }
            Surface.ROTATION_180 -> {
                ux = -gx
                uy = -gy
            }
            Surface.ROTATION_270 -> {
                ux = gy
                uy = -gx
            }
            else -> {
                ux = gx
                uy = gy
            }
        }

        val planar = sqrt(ux * ux + uy * uy)
        planarGravity = planar
        val tilt = (planar / SensorManager.GRAVITY_EARTH).coerceIn(0f, 1f)

        var tx = DEFAULT_X
        var ty = DEFAULT_Y
        if (planar > FLAT_THRESHOLD) {
            val inv = 1f / planar
            // Light arrives from world-up: in view coordinates (y down) that is (-ux, uy).
            val sx = -ux * inv
            val sy = uy * inv
            tx = sx * tilt + DEFAULT_X * (1f - tilt)
            ty = sy * tilt + DEFAULT_Y * (1f - tilt)
        }
        val len = sqrt(tx * tx + ty * ty)
        if (len > 1e-4f) {
            tx /= len
            ty /= len
        }

        var nx = lightX + (tx - lightX) * SMOOTHING
        var ny = lightY + (ty - lightY) * SMOOTHING
        val normalised = sqrt(nx * nx + ny * ny)
        if (normalised > 1e-4f) {
            nx /= normalised
            ny /= normalised
        }
        lightX = nx
        lightY = ny

        if (abs(nx - lastNotifiedX) <= NOTIFY_EPSILON && abs(ny - lastNotifiedY) <= NOTIFY_EPSILON) {
            return
        }
        lastNotifiedX = nx
        lastNotifiedY = ny
        // Logged, throttled, because the direction is the one thing about this feature that cannot be
        // photographed on a desk: proving the light tracks the device needs the device to be moved, and
        // the number is what says whether the sensor actually reached the shader.
        val now = SystemClock.uptimeMillis()
        if (now - lastLoggedAt > 1_000L) {
            lastLoggedAt = now
            Log.i(TAG, "light dir ${"%.3f".format(nx)}, ${"%.3f".format(ny)} tilt=${"%.2f".format(tilt)}")
        }
        synchronized(listeners) { listeners.toList() }.forEach { it.postInvalidateOnAnimation() }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private const val TAG = "LightSource"

    /**
     * Whether the system is set to remove animations.
     *
     * Read from `Settings.Global` rather than from `ValueAnimator.getDurationScale()`, because the
     * scale is a hidden static and reading it reflectively on every press would be worse than asking
     * the settings provider once per registration.
     */
    fun animationsDisabled(context: Context): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)
}
