/**
 * Helper for circular reveal animations between a FAB and an expandable panel.
 *
 * Used by [pro.magisk.ui.log.LogFragment] to animate the Magisk log filter panel,
 * and by similar toggle panels elsewhere. Supports RTL layout direction for
 * FAB movement.
 */
package pro.magisk.utils

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import androidx.core.animation.addListener
import androidx.core.text.layoutDirection
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.marginBottom
import androidx.core.view.marginEnd
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.circularreveal.CircularRevealCompat
import com.google.android.material.circularreveal.CircularRevealWidget
import com.google.android.material.floatingactionbutton.FloatingActionButton
import pro.magisk.core.utils.LocaleSetting
import kotlin.math.hypot

/** Coordinated circular-reveal + FAB-move animations for expandable panels. */
object MotionRevealHelper {

    /** Reveals or hides [revealable] panel with a circular animation, moving [fab] synchronously. */
    fun <CV> withViews(
        revealable: CV,
        fab: FloatingActionButton,
        expanded: Boolean
    ) where CV : CircularRevealWidget, CV : View {
        revealable.revealInfo = revealable.createRevealInfo(!expanded)

        val revealInfo = revealable.createRevealInfo(expanded)
        val reveal_anim = revealable.createRevealAnim(revealInfo)
        val move_anim = fab.createMoveAnim(revealInfo)

        AnimatorSet().also {
            if (expanded) {
                it.play(reveal_anim).after(move_anim)
            } else {
                it.play(move_anim).after(reveal_anim)
            }
        }.start()
    }

    private fun <CV> CV.createRevealAnim(
        revealInfo: CircularRevealWidget.RevealInfo
    ): Animator where CV : CircularRevealWidget, CV : View =
        CircularRevealCompat.createCircularReveal(
            this,
            revealInfo.centerX,
            revealInfo.centerY,
            revealInfo.radius
        ).apply {
            addListener(onStart = {
                isVisible = true
            }, onEnd = {
                if (revealInfo.radius == 0f) {
                    isInvisible = true
                }
            })
        }

    private fun FloatingActionButton.createMoveAnim(
        revealInfo: CircularRevealWidget.RevealInfo
    ): Animator = AnimatorSet().also {
        it.interpolator = FastOutSlowInInterpolator()
        it.addListener(onStart = { show() }, onEnd = { if (revealInfo.radius != 0f) hide() })

        val rtl_mod =
            if (LocaleSetting.instance.current_locale.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                1f else -1f
        val max_x = revealInfo.centerX - marginEnd - measuredWidth / 2f
        val target_x = if (revealInfo.radius == 0f) 0f else max_x * rtl_mod
        val move_x = ObjectAnimator.ofFloat(this, View.TRANSLATION_X, target_x)

        val max_y = revealInfo.centerY - marginBottom - measuredHeight / 2f
        val target_y = if (revealInfo.radius == 0f) 0f else -max_y
        val move_y = ObjectAnimator.ofFloat(this, View.TRANSLATION_Y, target_y)

        it.playTogether(move_x, move_y)
    }

    private fun View.createRevealInfo(expanded: Boolean): CircularRevealWidget.RevealInfo {
        val c_x = measuredWidth / 2f
        val c_y = measuredHeight / 2f - paddingBottom
        return CircularRevealWidget.RevealInfo(c_x, c_y, if (expanded) hypot(c_x, c_y) else 0f)
    }

}
