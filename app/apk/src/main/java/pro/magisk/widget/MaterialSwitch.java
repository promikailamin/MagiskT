/*
 * Ported from the Material Components for Android MaterialSwitch component
 * (com.google.android.material.materialswitch.MaterialSwitch), adapted to be
 * self-contained within the Magisk app without relying on Material 3 themes,
 * theme overlays or styles.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package pro.magisk.widget;

import static androidx.core.graphics.ColorUtils.blendARGB;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import android.view.Gravity;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Px;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import java.util.Arrays;

import pro.magisk.R;

/**
 * A class that provides the Material 3 Switch design. It is a drop-in replacement for the obsolete
 * {@link com.google.android.material.switchmaterial.SwitchMaterial} class, ported to live directly
 * inside the app so that it does not depend on any Material 3 theme overlay or style.
 *
 * <p>When a thumb icon is present, an extra {@code state_with_icon} state is merged into the
 * drawable state so the thumb can morph to a different shape while animating.
 */
public class MaterialSwitch extends SwitchCompat {
  private static final int DEF_STYLE_RES = R.style.WidgetFoundation_MaterialSwitch;
  private static final int[] STATE_SET_WITH_ICON = { R.attr.state_with_icon };

  /** Indicates to use the intrinsic size of the drawable. */
  private static final int INTRINSIC_SIZE = -1;

  @Nullable private Drawable thumbDrawable;
  @Nullable private Drawable thumbIconDrawable;
  @Px private int thumbIconSize = INTRINSIC_SIZE;

  @Nullable private Drawable trackDrawable;
  @Nullable private Drawable trackDecorationDrawable;

  @Nullable private ColorStateList thumbTintList;
  @Nullable private ColorStateList thumbIconTintList;
  @NonNull private PorterDuff.Mode thumbIconTintMode;
  @Nullable private ColorStateList trackTintList;
  @Nullable private ColorStateList trackDecorationTintList;
  @NonNull private PorterDuff.Mode trackDecorationTintMode;

  private int[] currentStateUnchecked;
  private int[] currentStateChecked;

  public MaterialSwitch(@NonNull Context context) {
    this(context, null);
  }

  public MaterialSwitch(@NonNull Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, R.attr.materialSwitchStyle);
  }

  public MaterialSwitch(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    thumbDrawable = super.getThumbDrawable();
    thumbTintList = super.getThumbTintList();
    super.setThumbTintList(null); // Always use our custom tinting logic

    trackDrawable = super.getTrackDrawable();
    trackTintList = super.getTrackTintList();
    super.setTrackTintList(null); // Always use our custom tinting logic

    TypedArray attributes =
        context.obtainStyledAttributes(attrs, R.styleable.MaterialSwitch, defStyleAttr, DEF_STYLE_RES);

    thumbIconDrawable = attributes.getDrawable(R.styleable.MaterialSwitch_thumbIcon);
    thumbIconSize = attributes.getDimensionPixelSize(
        R.styleable.MaterialSwitch_thumbIconSize, INTRINSIC_SIZE);

    thumbIconTintList = attributes.getColorStateList(R.styleable.MaterialSwitch_thumbIconTint);
    thumbIconTintMode =
        parseTintMode(
            attributes.getInt(R.styleable.MaterialSwitch_thumbIconTintMode, -1), Mode.SRC_IN);

    trackDecorationDrawable = attributes.getDrawable(R.styleable.MaterialSwitch_trackDecoration);
    trackDecorationTintList =
        attributes.getColorStateList(R.styleable.MaterialSwitch_trackDecorationTint);
    trackDecorationTintMode =
        parseTintMode(
            attributes.getInt(R.styleable.MaterialSwitch_trackDecorationTintMode, -1), Mode.SRC_IN);

    attributes.recycle();

    setEnforceSwitchWidth(false);

    refreshThumbDrawable();
    refreshTrackDrawable();
  }

  @Override
  public void invalidate() {
    updateDrawableTints();
    super.invalidate();
  }

  @Override
  protected int[] onCreateDrawableState(int extraSpace) {
    int[] drawableState = super.onCreateDrawableState(extraSpace + 1);

    if (thumbIconDrawable != null) {
      mergeDrawableStates(drawableState, STATE_SET_WITH_ICON);
    }

    currentStateUnchecked = getUncheckedState(drawableState);
    currentStateChecked = getCheckedState(drawableState);

    return drawableState;
  }

  @Override
  public void setThumbDrawable(@Nullable Drawable drawable) {
    thumbDrawable = drawable;
    refreshThumbDrawable();
  }

  @Override
  @Nullable
  public Drawable getThumbDrawable() {
    return thumbDrawable;
  }

  @Override
  public void setThumbTintList(@Nullable ColorStateList tintList) {
    thumbTintList = tintList;
    refreshThumbDrawable();
  }

  @Override
  @Nullable
  public ColorStateList getThumbTintList() {
    return thumbTintList;
  }

  @Override
  public void setThumbTintMode(@Nullable PorterDuff.Mode tintMode) {
    super.setThumbTintMode(tintMode);
    refreshThumbDrawable();
  }

  /**
   * Sets the drawable used for the thumb icon that will be drawn upon the thumb.
   *
   * @param resId Resource ID of a thumb icon drawable
   */
  public void setThumbIconResource(@DrawableRes int resId) {
    setThumbIconDrawable(AppCompatResources.getDrawable(getContext(), resId));
  }

  /**
   * Sets the drawable used for the thumb icon that will be drawn upon the thumb.
   *
   * @param icon Thumb icon drawable
   */
  public void setThumbIconDrawable(@Nullable Drawable icon) {
    thumbIconDrawable = icon;
    refreshThumbDrawable();
  }

  /**
   * Gets the drawable used for the thumb icon that will be drawn upon the thumb.
   */
  @Nullable
  public Drawable getThumbIconDrawable() {
    return thumbIconDrawable;
  }

  /**
   * Sets the size of the thumb icon.
   */
  public void setThumbIconSize(@Px final int size) {
    if (thumbIconSize != size) {
      thumbIconSize = size;
      refreshThumbDrawable();
    }
  }

  /**
   * Returns the size of the thumb icon.
   */
  @Px
  public int getThumbIconSize() {
    return thumbIconSize;
  }

  /**
   * Applies a tint to the thumb icon drawable. Does not modify the current tint mode, which is
   * {@link PorterDuff.Mode#SRC_IN} by default.
   *
   * @param tintList the tint to apply, may be {@code null} to clear tint
   */
  public void setThumbIconTintList(@Nullable ColorStateList tintList) {
    thumbIconTintList = tintList;
    refreshThumbDrawable();
  }

  /**
   * Returns the tint applied to the thumb icon drawable.
   */
  @Nullable
  public ColorStateList getThumbIconTintList() {
    return thumbIconTintList;
  }

  /**
   * Specifies the blending mode used to apply the tint specified by
   * {@link #setThumbIconTintList(ColorStateList)} to the thumb icon drawable. The default mode is
   * {@link PorterDuff.Mode#SRC_IN}.
   *
   * @param tintMode the blending mode used to apply the tint
   */
  public void setThumbIconTintMode(@NonNull PorterDuff.Mode tintMode) {
    thumbIconTintMode = tintMode;
    refreshThumbDrawable();
  }

  /**
   * Returns the blending mode used to apply the tint to the thumb icon drawable.
   */
  @NonNull
  public PorterDuff.Mode getThumbIconTintMode() {
    return thumbIconTintMode;
  }

  @Override
  public void setTrackDrawable(@Nullable Drawable track) {
    trackDrawable = track;
    refreshTrackDrawable();
  }

  @Override
  @Nullable
  public Drawable getTrackDrawable() {
    return trackDrawable;
  }

  @Override
  public void setTrackTintList(@Nullable ColorStateList tintList) {
    trackTintList = tintList;
    refreshTrackDrawable();
  }

  @Override
  @Nullable
  public ColorStateList getTrackTintList() {
    return trackTintList;
  }

  @Override
  public void setTrackTintMode(@Nullable PorterDuff.Mode tintMode) {
    super.setTrackTintMode(tintMode);
    refreshTrackDrawable();
  }

  /**
   * Set the drawable used for the track decoration that will be drawn upon the track.
   *
   * @param resId Resource ID of a track decoration drawable
   */
  public void setTrackDecorationResource(@DrawableRes int resId) {
    setTrackDecorationDrawable(AppCompatResources.getDrawable(getContext(), resId));
  }

  /**
   * Set the drawable used for the track decoration that will be drawn upon the track.
   *
   * @param trackDecoration Track decoration drawable
   */
  public void setTrackDecorationDrawable(@Nullable Drawable trackDecoration) {
    trackDecorationDrawable = trackDecoration;
    refreshTrackDrawable();
  }

  /**
   * Get the drawable used for the track decoration that will be drawn upon the track.
   */
  @Nullable
  public Drawable getTrackDecorationDrawable() {
    return trackDecorationDrawable;
  }

  /**
   * Applies a tint to the track decoration drawable. Does not modify the current tint mode, which
   * is {@link PorterDuff.Mode#SRC_IN} by default.
   *
   * @param tintList the tint to apply, may be {@code null} to clear tint
   */
  public void setTrackDecorationTintList(@Nullable ColorStateList tintList) {
    trackDecorationTintList = tintList;
    refreshTrackDrawable();
  }

  /**
   * Returns the tint applied to the track decoration drawable.
   */
  @Nullable
  public ColorStateList getTrackDecorationTintList() {
    return trackDecorationTintList;
  }

  /**
   * Specifies the blending mode used to apply the tint specified by
   * {@link #setTrackDecorationTintList(ColorStateList)} to the track decoration drawable. The
   * default mode is {@link PorterDuff.Mode#SRC_IN}.
   *
   * @param tintMode the blending mode used to apply the tint
   */
  public void setTrackDecorationTintMode(@NonNull PorterDuff.Mode tintMode) {
    trackDecorationTintMode = tintMode;
    refreshTrackDrawable();
  }

  /**
   * Returns the blending mode used to apply the tint to the track decoration drawable.
   */
  @NonNull
  public PorterDuff.Mode getTrackDecorationTintMode() {
    return trackDecorationTintMode;
  }

  private void refreshThumbDrawable() {
    thumbDrawable =
        createTintableDrawableIfNeeded(
            thumbDrawable, thumbTintList, getThumbTintMode());
    thumbIconDrawable =
        createTintableDrawableIfNeeded(
            thumbIconDrawable, thumbIconTintList, thumbIconTintMode);

    updateDrawableTints();

    super.setThumbDrawable(compositeTwoLayeredDrawable(
        thumbDrawable, thumbIconDrawable, thumbIconSize, thumbIconSize));

    refreshDrawableState();
  }

  private void refreshTrackDrawable() {
    trackDrawable =
        createTintableDrawableIfNeeded(
            trackDrawable, trackTintList, getTrackTintMode());
    trackDecorationDrawable =
        createTintableDrawableIfNeeded(
            trackDecorationDrawable, trackDecorationTintList, trackDecorationTintMode);

    updateDrawableTints();

    Drawable finalTrackDrawable;
    if (trackDrawable != null && trackDecorationDrawable != null) {
      finalTrackDrawable =
          new LayerDrawable(new Drawable[]{ trackDrawable, trackDecorationDrawable});
    } else if (trackDrawable != null) {
      finalTrackDrawable = trackDrawable;
    } else {
      finalTrackDrawable = trackDecorationDrawable;
    }
    if (finalTrackDrawable != null) {
      setSwitchMinWidth(finalTrackDrawable.getIntrinsicWidth());
    }
    super.setTrackDrawable(finalTrackDrawable);
  }

  private void updateDrawableTints() {
    if (thumbTintList == null
        && thumbIconTintList == null
        && trackTintList == null
        && trackDecorationTintList == null) {
      // Early return to avoid heavy operation.
      return;
    }

    float thumbPosition = getThumbPosition();

    if (thumbTintList != null) {
      setInterpolatedDrawableTintIfPossible(
          thumbDrawable, thumbTintList, currentStateUnchecked, currentStateChecked, thumbPosition);
    }

    if (thumbIconTintList != null) {
      setInterpolatedDrawableTintIfPossible(
          thumbIconDrawable,
          thumbIconTintList,
          currentStateUnchecked,
          currentStateChecked,
          thumbPosition);
    }

    if (trackTintList != null) {
      setInterpolatedDrawableTintIfPossible(
          trackDrawable, trackTintList, currentStateUnchecked, currentStateChecked, thumbPosition);
    }

    if (trackDecorationTintList != null) {
      setInterpolatedDrawableTintIfPossible(
          trackDecorationDrawable,
          trackDecorationTintList,
          currentStateUnchecked,
          currentStateChecked,
          thumbPosition);
    }
  }

  /**
   * Tints the given drawable with the interpolated color according to the provided thumb position
   * between unchecked and checked states. The reference color in unchecked and checked states will
   * be retrieved from the given {@link ColorStateList} according to the provided states.
   */
  private static void setInterpolatedDrawableTintIfPossible(
      @Nullable Drawable drawable,
      @Nullable ColorStateList tint,
      @NonNull int[] stateUnchecked,
      @NonNull int[] stateChecked,
      float thumbPosition) {
    if (drawable == null || tint == null) {
      return;
    }

    drawable.setTint(blendARGB(
        tint.getColorForState(stateUnchecked, 0),
        tint.getColorForState(stateChecked, 0),
        thumbPosition));
  }

  /**
   * Wraps and mutates the passed in drawable so that it may be used for tinting if a tintList is
   * present. Also applies the tintMode if present.
   */
  @Nullable
  private static Drawable createTintableDrawableIfNeeded(
      @Nullable Drawable drawable, @Nullable ColorStateList tintList, @Nullable Mode tintMode) {
    if (drawable == null) {
      return null;
    }
    if (tintList != null) {
      drawable = DrawableCompat.wrap(drawable).mutate();
      if (tintMode != null) {
        drawable.setTintMode(tintMode);
      }
    }
    return drawable;
  }

  /**
   * Composites two drawables, returning a drawable instance of {@link LayerDrawable}, with the top
   * layer centered to the bottom layer. The top layer will be scaled according to the provided
   * desired width/height and the size of the bottom layer so the top layer can fit in the bottom
   * layer and preserve its desired aspect ratio.
   *
   * <p>If any of the drawables is null, this method will return the other.
   */
  @Nullable
  private static Drawable compositeTwoLayeredDrawable(
      @Nullable Drawable bottomLayerDrawable,
      @Nullable Drawable topLayerDrawable,
      @Px int topLayerDesiredWidth,
      @Px int topLayerDesiredHeight) {
    if (bottomLayerDrawable == null) {
      return topLayerDrawable;
    }
    if (topLayerDrawable == null) {
      return bottomLayerDrawable;
    }

    if (topLayerDesiredWidth == INTRINSIC_SIZE) {
      int topLayerIntrinsicWidth = topLayerDrawable.getIntrinsicWidth();
      topLayerDesiredWidth =
          topLayerIntrinsicWidth != -1
              ? topLayerIntrinsicWidth : bottomLayerDrawable.getIntrinsicWidth();
    }
    if (topLayerDesiredHeight == INTRINSIC_SIZE) {
      int topLayerIntrinsicHeight = topLayerDrawable.getIntrinsicHeight();
      topLayerDesiredHeight =
          topLayerIntrinsicHeight != -1
              ? topLayerIntrinsicHeight : bottomLayerDrawable.getIntrinsicHeight();
    }

    final int topLayerNewWidth;
    final int topLayerNewHeight;
    if (topLayerDesiredWidth <= bottomLayerDrawable.getIntrinsicWidth()
        && topLayerDesiredHeight <= bottomLayerDrawable.getIntrinsicHeight()) {
      // If the top layer's desired size is smaller than the bottom layer's size in both its width
      // and height, keep top layer's desired size.
      topLayerNewWidth = topLayerDesiredWidth;
      topLayerNewHeight = topLayerDesiredHeight;
    } else {
      float topLayerRatio = (float) topLayerDesiredWidth / topLayerDesiredHeight;
      float bottomLayerRatio =
          (float) bottomLayerDrawable.getIntrinsicWidth()
              / bottomLayerDrawable.getIntrinsicHeight();
      if (topLayerRatio >= bottomLayerRatio) {
        // If the top layer is wider in ratio than the bottom layer, shrink it according to its
        // width.
        topLayerNewWidth = bottomLayerDrawable.getIntrinsicWidth();
        topLayerNewHeight = (int) (topLayerNewWidth / topLayerRatio);
      } else {
        // If the top layer is taller in ratio than the bottom layer, shrink it according to its
        // height.
        topLayerNewHeight = bottomLayerDrawable.getIntrinsicHeight();
        topLayerNewWidth = (int) (topLayerRatio * topLayerNewHeight);
      }
    }

    LayerDrawable drawable = new LayerDrawable(new Drawable[] {bottomLayerDrawable, topLayerDrawable});
    drawable.setLayerSize(1, topLayerNewWidth, topLayerNewHeight);
    drawable.setLayerGravity(1, Gravity.CENTER);

    return drawable;
  }

  /** Returns a new state that adds the checked state to the input state. */
  @NonNull
  private static int[] getCheckedState(@NonNull int[] state) {
    for (int i = 0; i < state.length; i++) {
      if (state[i] == android.R.attr.state_checked) {
        return state;
      } else if (state[i] == 0) {
        int[] newState = state.clone();
        newState[i] = android.R.attr.state_checked;
        return newState;
      }
    }
    int[] newState = Arrays.copyOf(state, state.length + 1);
    newState[state.length] = android.R.attr.state_checked;
    return newState;
  }

  /** Returns a new state that removes the checked state from the input state. */
  @NonNull
  private static int[] getUncheckedState(@NonNull int[] state) {
    int[] newState = new int[state.length];
    int i = 0;
    for (int subState : state) {
      if (subState != android.R.attr.state_checked) {
        newState[i++] = subState;
      }
    }
    return newState;
  }

  /** Parses a tint mode enum value into a {@link PorterDuff.Mode}. */
  private static Mode parseTintMode(int value, Mode defaultMode) {
    switch (value) {
      case 3:
        return Mode.SRC_OVER;
      case 5:
        return Mode.SRC_IN;
      case 9:
        return Mode.SRC_ATOP;
      case 14:
        return Mode.MULTIPLY;
      case 15:
        return Mode.SCREEN;
      case 16:
        return Mode.ADD;
      default:
        return defaultMode;
    }
  }
}
