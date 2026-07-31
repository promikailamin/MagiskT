/**
 * Utility for checking system animation settings.
 *
 * Used to determine whether navigation transitions should use custom animation options
 * or not, since disabling all animations makes the default navigate() call act
 * unexpectedly differently from navigate(..., navOptions {}).
 */
package pro.magisk.utils

import android.content.ContentResolver
import android.provider.Settings

/** Helper to detect whether the user has disabled system animations. */
class AccessibilityUtils {
    companion object {
        /** Returns true if any of the three animation scales (animator, transition, window) is > 0. */
        fun is_animation_enabled(cr: ContentResolver): Boolean {
            return !(Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0.0f
                && Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1.0f) == 0.0f
                && Settings.Global.getFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1.0f) == 0.0f)
        }
    }
}
