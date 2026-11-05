/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.launcher3.folder;

import static com.android.launcher3.icons.BitmapInfo.FLAG_THEMED;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.launcher3.BubbleTextView;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.icons.FastBitmapDrawable;
import com.android.launcher3.model.data.WorkspaceItemInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements the enlarged ("big") 2x2 state of a workspace {@link FolderIcon}.
 *
 * <p>In this state the folder occupies a 2x2 block of workspace cells and renders a 3x3 grid of
 * app icons inside a rounded container:
 * <ul>
 *   <li>Grid slots 1-8 are real {@link BubbleTextView}s. Tapping one launches the app directly
 *       through the regular item click handler, so launch animations, work-profile handling and
 *       disabled-app handling all behave like any other workspace icon.</li>
 *   <li>Slot 9 shows the 9th app (and launches it directly) when the folder has at most 9 apps.
 *       With more apps it shows a 2x2 mini preview of the remaining apps instead. That preview is
 *       not a view: tapping it, or the container's empty background, falls through to the
 *       FolderIcon's own click, which opens the full expanded folder.</li>
 * </ul>
 *
 * <p>The controller also owns the spring morph that plays when the folder switches between its
 * 1x1 and 2x2 states. The morph uses the same spring constants as the folder open/close
 * animation ({@code FolderSpringAnimatorSet}).
 */
class BigFolderController {

    /** The container holds a GRID x GRID mini grid. */
    static final int GRID = 3;
    private static final int SLOT_COUNT = GRID * GRID;
    /** Slots with an index below this always show an app that launches directly. */
    private static final int DIRECT_LAUNCH_SLOTS = SLOT_COUNT - 1;
    /** Number of apps previewed in the 9th slot when the folder overflows. */
    private static final int OVERFLOW_PREVIEW_COUNT = 4;

    // Visual specs.
    private static final float CORNER_RADIUS_DP = 26f;
    private static final float CONTAINER_INSET_DP = 6f;
    private static final float LABEL_GAP_DP = 4f;
    private static final float INNER_PADDING_FRACTION = 0.07f;
    private static final float SLOT_ICON_FRACTION = 0.80f;
    private static final float OVERFLOW_ICON_FRACTION = 0.42f;
    private static final int OVERFLOW_BG_ALPHA = 0x30;

    // Morph spring, shared with the folder open/close spring.
    private static final float MORPH_STIFFNESS = 380f;
    private static final float MORPH_DAMPING_RATIO = 0.8f;
    private static final float MORPH_ENLARGE_START_SCALE = 0.5f;
    private static final float MORPH_SHRINK_START_SCALE = 2f;

    private final FolderIcon mIcon;

    private boolean mActive;

    private final ArrayList<BubbleTextView> mSlotViews = new ArrayList<>();
    private final ArrayList<FastBitmapDrawable> mOverflowIcons = new ArrayList<>();

    private final RectF mContainer = new RectF();
    private final RectF[] mSlotRects = new RectF[SLOT_COUNT];
    private final RectF mTmpRect = new RectF();
    private int mLabelTop;
    private boolean mHasOverflowPreview;

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOverflowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private SpringAnimation mSpringX;
    private SpringAnimation mSpringY;
    private int mMorphToken;

    BigFolderController(FolderIcon icon) {
        mIcon = icon;
        for (int i = 0; i < SLOT_COUNT; i++) {
            mSlotRects[i] = new RectF();
        }
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(mIcon.getResources().getDisplayMetrics().density);
        mFillPaint.setStyle(Paint.Style.FILL);
        mOverflowPaint.setStyle(Paint.Style.FILL);
    }

    boolean isActive() {
        return mActive;
    }

    // ---------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------

    /** Enters or leaves the big state without animating. */
    void setActive(boolean active) {
        cancelMorph();
        mActive = active;
        if (active) {
            rebuildSlots();
        } else {
            clearSlots();
            restoreLabelMargin();
        }
        mIcon.requestLayout();
        mIcon.invalidate();
    }

    /** Re-reads the folder contents, e.g. after apps were added, removed or their icon changed. */
    void onContentsChanged() {
        if (mActive) {
            rebuildSlots();
            mIcon.requestLayout();
            mIcon.invalidate();
        }
    }

    /** Called when the folder's icon is hidden/shown, e.g. while the folder opens or is dragged. */
    void onIconVisibilityChanged(boolean visible) {
        for (BubbleTextView slot : mSlotViews) {
            slot.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void clearSlots() {
        for (BubbleTextView slot : mSlotViews) {
            mIcon.removeView(slot);
        }
        mSlotViews.clear();
        mOverflowIcons.clear();
        mHasOverflowPreview = false;
    }

    private void rebuildSlots() {
        clearSlots();
        final Context context = mIcon.getContext();
        final List<WorkspaceItemInfo> apps = mIcon.mInfo.getAppContents();
        final int total = apps.size();
        // Up to 9 apps all launch directly. Beyond that the 9th slot becomes the overflow preview.
        final int directCount = total <= SLOT_COUNT ? total : DIRECT_LAUNCH_SLOTS;

        for (int i = 0; i < directCount; i++) {
            mSlotViews.add(createSlotView(context, apps.get(i)));
        }
        mHasOverflowPreview = total > SLOT_COUNT;
        if (mHasOverflowPreview) {
            final int end = Math.min(total, DIRECT_LAUNCH_SLOTS + OVERFLOW_PREVIEW_COUNT);
            for (int i = DIRECT_LAUNCH_SLOTS; i < end; i++) {
                mOverflowIcons.add(apps.get(i).newIcon(context, FLAG_THEMED));
            }
        }
    }

    private BubbleTextView createSlotView(Context context, WorkspaceItemInfo info) {
        BubbleTextView slot = (BubbleTextView) LayoutInflater.from(context)
                .inflate(R.layout.app_icon, mIcon, false);
        slot.applyFromWorkspaceItem(info);
        // Only the icon is shown: the label of every app would not fit in a 3x3 grid.
        slot.setContainerTextVisibility(false);
        slot.setHapticFeedbackEnabled(false);
        // Direct launch: the regular click handler handles the WorkspaceItemInfo tag.
        slot.setOnClickListener(mIcon.mActivity.getItemOnClickListener());
        // A long press on a slot is a long press on the folder (menu and drag).
        slot.setOnLongClickListener(v -> mIcon.performLongClick());
        slot.setVisibility(mIcon.getIconVisible() ? View.VISIBLE : View.INVISIBLE);
        final int iconSize = slot.getIconSize();
        mIcon.addView(slot, new FrameLayout.LayoutParams(iconSize, iconSize));
        return slot;
    }

    // ---------------------------------------------------------------------------------------
    // Measure / layout
    // ---------------------------------------------------------------------------------------

    /**
     * Computes the geometry for a view of the given size and applies the label position. Must be
     * called before the FolderIcon measures its children.
     */
    void onBeforeMeasure(int width, int height) {
        computeGeometry(width, height);
        FrameLayout.LayoutParams labelLp =
                (FrameLayout.LayoutParams) mIcon.getFolderName().getLayoutParams();
        labelLp.topMargin = mLabelTop;
    }

    private void computeGeometry(int width, int height) {
        final float density = mIcon.getResources().getDisplayMetrics().density;
        final float inset = CONTAINER_INSET_DP * density;
        final float gap = LABEL_GAP_DP * density;

        final BubbleTextView label = mIcon.getFolderName();
        float labelHeight = 0;
        if (label.shouldShowLabel() && label.getVisibility() == View.VISIBLE) {
            Paint.FontMetrics fm = label.getPaint().getFontMetrics();
            labelHeight = (float) Math.ceil(fm.bottom - fm.top);
        }
        final float labelBlock = labelHeight > 0 ? labelHeight + gap : 0;

        // The container is a square; the title sits below it, and the pair is centered vertically.
        final float side = Math.max(0, Math.min(width - 2 * inset,
                height - labelBlock - 2 * inset));
        final float left = (width - side) / 2f;
        final float top = (height - (side + labelBlock)) / 2f;
        mContainer.set(left, top, left + side, top + side);
        mLabelTop = Math.round(top + side + gap);

        final boolean rtl = Utilities.isRtl(mIcon.getResources());
        final float pad = side * INNER_PADDING_FRACTION;
        final float slot = (side - 2 * pad) / GRID;
        for (int i = 0; i < SLOT_COUNT; i++) {
            int row = i / GRID;
            int col = i % GRID;
            if (rtl) {
                col = GRID - 1 - col;
            }
            final float x = left + pad + col * slot;
            final float y = top + pad + row * slot;
            mSlotRects[i].set(x, y, x + slot, y + slot);
        }
    }

    /** Positions the slot views. Called after the FolderIcon's own layout. */
    void onLayoutSlots() {
        for (int i = 0; i < mSlotViews.size(); i++) {
            final BubbleTextView slot = mSlotViews.get(i);
            final RectF rect = mSlotRects[i];
            final int size = slot.getMeasuredWidth();
            final float half = size / 2f;
            final int left = Math.round(rect.centerX() - half);
            final int top = Math.round(rect.centerY() - half);
            slot.layout(left, top, left + size, top + size);

            final float scale = size > 0 ? rect.width() * SLOT_ICON_FRACTION / size : 1f;
            slot.setPivotX(half);
            slot.setPivotY(half);
            slot.setScaleX(scale);
            slot.setScaleY(scale);
        }
    }

    private void restoreLabelMargin() {
        final BubbleTextView label = mIcon.getFolderName();
        if (label == null) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) label.getLayoutParams();
        if (lp != null && mIcon.mActivity != null) {
            // Same formula as FolderIcon#inflateIcon.
            lp.topMargin = mIcon.mActivity.getDeviceProfile().getWorkspaceProfile().getIconSizePx()
                    + mIcon.mActivity.getDeviceProfile().getWorkspaceProfile()
                            .getIconDrawablePaddingPx();
        }
    }

    /**
     * Copies the big container geometry into the folder's preview background, so that everything
     * that is derived from it (drag visuals, launch/open animations, accepting-a-drop highlight)
     * uses the 2x2 container instead of the 1x1 icon.
     */
    void applyToBackground(PreviewBackground background) {
        if (mContainer.isEmpty()) {
            computeGeometry(mIcon.getMeasuredWidth(), mIcon.getMeasuredHeight());
        }
        background.previewSize = Math.round(mContainer.width());
        background.basePreviewOffsetX = Math.round(mContainer.left);
        background.basePreviewOffsetY = Math.round(mContainer.top);
    }

    /** Fills {@code out} with the container bounds in the FolderIcon's coordinates. */
    void getContainerBounds(Rect out) {
        mContainer.roundOut(out);
    }

    // ---------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------

    /** Draws the container behind the slot views. */
    void drawBackground(Canvas canvas) {
        refreshColors();
        final float radius = CORNER_RADIUS_DP * mIcon.getResources().getDisplayMetrics().density;
        canvas.drawRoundRect(mContainer, radius, radius, mFillPaint);
        canvas.drawRoundRect(mContainer, radius, radius, mStrokePaint);
    }

    /** Draws the overflow preview (2x2 mini grid) in the 9th slot, on top of the slot views. */
    void drawOverflowPreview(Canvas canvas) {
        if (!mHasOverflowPreview || mOverflowIcons.isEmpty()) {
            return;
        }
        final RectF slot = mSlotRects[SLOT_COUNT - 1];
        final float cornerRadius = slot.width() * 0.22f;
        canvas.drawRoundRect(slot, cornerRadius, cornerRadius, mOverflowPaint);

        final float icon = slot.width() * OVERFLOW_ICON_FRACTION;
        final float margin = (slot.width() - 2 * icon) / 3f;
        final boolean rtl = Utilities.isRtl(mIcon.getResources());
        for (int i = 0; i < mOverflowIcons.size(); i++) {
            int row = i / 2;
            int col = i % 2;
            if (rtl) {
                col = 1 - col;
            }
            final float x = slot.left + margin + col * (icon + margin);
            final float y = slot.top + margin + row * (icon + margin);
            mTmpRect.set(x, y, x + icon, y + icon);
            FastBitmapDrawable drawable = mOverflowIcons.get(i);
            drawable.setBounds(Math.round(mTmpRect.left), Math.round(mTmpRect.top),
                    Math.round(mTmpRect.right), Math.round(mTmpRect.bottom));
            drawable.draw(canvas);
        }
    }

    private void refreshColors() {
        TypedArray ta = mIcon.getContext().getTheme()
                .obtainStyledAttributes(R.styleable.FolderIconPreview);
        final int fill = ta.getColor(R.styleable.FolderIconPreview_folderPreviewColor, 0);
        final int stroke = ta.getColor(R.styleable.FolderIconPreview_folderIconBorderColor, 0);
        ta.recycle();
        mFillPaint.setColor(fill);
        mStrokePaint.setColor(stroke);
        mOverflowPaint.setColor((stroke & 0x00FFFFFF) | (OVERFLOW_BG_ALPHA << 24));
    }

    // ---------------------------------------------------------------------------------------
    // Morph
    // ---------------------------------------------------------------------------------------

    /**
     * Plays the shared-bounds morph between the 1x1 and 2x2 footprints with a spring. The view has
     * already been given its new size; we scale it from the previous footprint to its final size.
     *
     * @param toBig true when going 1x1 -> 2x2
     * @param shiftCellsX how many cells the folder's left edge moved to the left (0 or 1)
     * @param shiftCellsY how many cells the folder's top edge moved up (0 or 1)
     */
    void playMorph(boolean toBig, int shiftCellsX, int shiftCellsY) {
        cancelMorph();
        final int token = ++mMorphToken;
        mIcon.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                ViewTreeObserver current = mIcon.getViewTreeObserver();
                if (current.isAlive()) {
                    current.removeOnPreDrawListener(this);
                }
                // Skip if the state changed again before this first frame.
                if (token == mMorphToken) {
                    startMorph(toBig, shiftCellsX, shiftCellsY);
                }
                return true;
            }
        });
        mIcon.invalidate();
    }

    private void startMorph(boolean toBig, int shiftCellsX, int shiftCellsY) {
        final int width = mIcon.getWidth();
        final int height = mIcon.getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        final float startScale = toBig ? MORPH_ENLARGE_START_SCALE : MORPH_SHRINK_START_SCALE;
        // Growing: the old 1x1 footprint sits at an offset inside the new 2x2 bounds, so scale
        // around its corner. Shrinking: the new 1x1 bounds are the old footprint's top-left cell.
        mIcon.setPivotX(toBig ? shiftCellsX * width / 2f : 0f);
        mIcon.setPivotY(toBig ? shiftCellsY * height / 2f : 0f);
        mIcon.setScaleX(startScale);
        mIcon.setScaleY(startScale);

        mSpringX = createMorphSpring(DynamicAnimation.SCALE_X);
        mSpringY = createMorphSpring(DynamicAnimation.SCALE_Y);
        mSpringX.setStartValue(startScale);
        mSpringY.setStartValue(startScale);
        mSpringX.addEndListener((animation, canceled, value, velocity) -> {
            mIcon.setScaleX(1f);
            mIcon.setScaleY(1f);
            mIcon.resetPivot();
        });
        mSpringX.start();
        mSpringY.start();
    }

    private SpringAnimation createMorphSpring(DynamicAnimation.ViewProperty property) {
        SpringAnimation animation = new SpringAnimation(mIcon, property);
        animation.setSpring(new SpringForce(1f)
                .setStiffness(MORPH_STIFFNESS)
                .setDampingRatio(MORPH_DAMPING_RATIO));
        return animation;
    }

    private void cancelMorph() {
        mMorphToken++;
        if (mSpringX != null && mSpringX.isRunning()) {
            mSpringX.cancel();
        }
        if (mSpringY != null && mSpringY.isRunning()) {
            mSpringY.cancel();
        }
        mSpringX = null;
        mSpringY = null;
    }
}
