package org.spatialplayer.playground

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import org.spatialplayer.ui.Haptics

/** Observe without consuming: the upstream gesture spring keeps ownership of the pointer. */
@Composable
internal fun playgroundHaptics(): Modifier {
    val view = LocalView.current
    return Modifier.pointerInput(view) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            Haptics.touch(view)
            do { val event = awaitPointerEvent(PointerEventPass.Initial) }
            while (event.changes.any { it.pressed })
            Haptics.release(view)
        }
    }
}
