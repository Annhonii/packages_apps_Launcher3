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
 * Implements the enlarged states of a workspace {@link FolderIcon}: "big" (2x2 cells, 3x3 icon
 * grid) and "wide" (3x1 cells, a single row of 3 icons).
 *
 * <p>In these states the folder renders a grid of app icons inside a rounded container. The
 * description below uses the 3x3 grid; the wide folder works the same with 3 slots, of which the
 * last one is the overflow preview when the folder has more than 3 apps:
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

    /** The big (2x2) container holds a GRID x GRID mini grid; the wide one GRID x 1. */
    static final int GRID = 3;
    private static final int MAX_SLOT_COUNT = GRID * GRID;
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

    private final FolderIcon mIcon;

    private boolean mActive;

    private final ArrayList<BubbleTextView> mSlotViews = new ArrayList<>();
    private final ArrayList<FastBitmapDrawable> mOverflowIcons = new ArrayList<>();

    private final RectF mContainer = new RectF();
    private final RectF[] mSlotRects = new RectF[MAX_SLOT_COUNT];
    private final RectF mTmpRect = new RectF();
    private int mLabelTop;
    private int mCols = GRID;
    private int mRows = GRID;
    private boolean mHasOverflowPreview;

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOverflowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private SpringAnimation mSpringX;
    private SpringAnimation mSpringY;
    private int mMorphToken;

    BigFolderController(FolderIcon icon) {
        mIcon = icon;
        for (int i = 0; i < MAX_SLOT_COUNT; i++) {
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

    private int slotCount() {
        return mCols * mRows;
    }

    /** Picks the grid for the folder's current spans: 3x1 for the wide folder, else 3x3. */
    private void updateGrid() {
        mCols = GRID;
        mRows = mIcon.mInfo.isWideFolder() ? 1 : GRID;
    }

    /** Enters or leaves the big state, or switches between big and wide, without animating. */
    void setActive(boolean active) {
        cancelMorph();
        mActive = active;
        if (active) {
            updateGrid();
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
        final int slotCount = slotCount();
        final int lastSlot = slotCount - 1;
        // Apps that fit all launch directly. Beyond that the last slot becomes the overflow preview.
        final int directCount = total <= slotCount ? total : lastSlot;

        for (int i = 0; i < directCount; i++) {
            mSlotViews.add(createSlotView(context, apps.get(i)));
        }
        mHasOverflowPreview = total > slotCount;
        if (mHasOverflowPreview) {
            final int end = Math.min(total, lastSlot + OVERFLOW_PREVIEW_COUNT);
            for (int i = lastSlot; i < end; i++) {
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

        final boolean wide = mRows == 1;
        // The title sits below the container, and the pair is centered vertically. The big
        // container is a square; the wide one fills the whole width of the 3x1 footprint.
        final float availH = Math.max(0, height - labelBlock - 2 * inset);
        final float availW = Math.max(0, width - 2 * inset);
        final float w = wide ? availW : Math.min(availW, availH);
        final float h = wide ? availH : w;
        final float left = (width - w) / 2f;
        final float top = (height - (h + labelBlock)) / 2f;
        mContainer.set(left, top, left + w, top + h);
        mLabelTop = Math.round(top + h + gap);

        final boolean rtl = Utilities.isRtl(mIcon.getResources());
        final float pad = Math.min(w, h) * INNER_PADDING_FRACTION;
        final float stepX = (w - 2 * pad) / mCols;
        final float stepY = (h - 2 * pad) / mRows;
        // Slots are squares centered in their grid cell.
        final float slot = Math.min(stepX, stepY);
        final int count = slotCount();
        for (int i = 0; i < count; i++) {
            int row = i / mCols;
            int col = i % mCols;
            if (rtl) {
                col = mCols - 1 - col;
            }
            final float cx = left + pad + (col + 0.5f) * stepX;
            final float cy = top + pad + (row + 0.5f) * stepY;
            mSlotRects[i].set(cx - slot / 2f, cy - slot / 2f, cx + slot / 2f, cy + slot / 2f);
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
        // The background is square: for the wide folder use the container's height, centered.
        final float size = Math.min(mContainer.width(), mContainer.height());
        background.previewSize = Math.round(size);
        background.basePreviewOffsetX = Math.round(mContainer.centerX() - size / 2f);
        background.basePreviewOffsetY = Math.round(mContainer.centerY() - size / 2f);
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
        final float radius = Math.min(
                CORNER_RADIUS_DP * mIcon.getResources().getDisplayMetrics().density,
                Math.min(mContainer.width(), mContainer.height()) / 2f);
        canvas.drawRoundRect(mContainer, radius, radius, mFillPaint);
        canvas.drawRoundRect(mContainer, radius, radius, mStrokePaint);
    }

    /** Draws the overflow preview (2x2 mini grid) in the last slot, on top of the slot views. */
    void drawOverflowPreview(Canvas canvas) {
        if (!mHasOverflowPreview || mOverflowIcons.isEmpty()) {
            return;
        }
        final RectF slot = mSlotRects[slotCount() - 1];
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
     * Plays the shared-bounds morph from the previous footprint to the current one with a spring.
     * The view has already been given its new size; we scale it from the previous footprint (per
     * axis) to its final size.
     *
     * @param prevSpanX column span before the change
     * @param prevSpanY row span before the change
     * @param shiftCellsX how many cells the folder's left edge moved to the left
     * @param shiftCellsY how many cells the folder's top edge moved up
     */
    void playMorph(int prevSpanX, int prevSpanY, int shiftCellsX, int shiftCellsY) {
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
                    startMorph(prevSpanX, prevSpanY, shiftCellsX, shiftCellsY);
                }
                return true;
            }
        });
        mIcon.invalidate();
    }

    /** Returns the pivot that maps the new bounds' edge onto the old footprint's edge. */
    private static float morphPivot(float startScale, float offsetPx) {
        return Math.abs(1f - startScale) < 0.001f ? 0f : offsetPx / (1f - startScale);
    }

    private void startMorph(int prevSpanX, int prevSpanY, int shiftCellsX, int shiftCellsY) {
        final int width = mIcon.getWidth();
        final int height = mIcon.getHeight();
        final int spanX = Math.max(1, mIcon.mInfo.spanX);
        final int spanY = Math.max(1, mIcon.mInfo.spanY);
        if (width == 0 || height == 0) {
            return;
        }
        final float startScaleX = (float) prevSpanX / spanX;
        final float startScaleY = (float) prevSpanY / spanY;
        // Start with the view covering exactly the previous footprint: the old footprint sits at
        // an offset (the shift) inside the new bounds, so scale around the matching pivot.
        mIcon.setPivotX(morphPivot(startScaleX, shiftCellsX * (float) width / spanX));
        mIcon.setPivotY(morphPivot(startScaleY, shiftCellsY * (float) height / spanY));
        mIcon.setScaleX(startScaleX);
        mIcon.setScaleY(startScaleY);

        mSpringX = createMorphSpring(DynamicAnimation.SCALE_X);
        mSpringY = createMorphSpring(DynamicAnimation.SCALE_Y);
        mSpringX.setStartValue(startScaleX);
        mSpringY.setStartValue(startScaleY);
        final DynamicAnimation.OnAnimationEndListener endListener =
                (animation, canceled, value, velocity) -> {
                    if ((mSpringX != null && mSpringX.isRunning())
                            || (mSpringY != null && mSpringY.isRunning())) {
                        return;
                    }
                    mIcon.setScaleX(1f);
                    mIcon.setScaleY(1f);
                    mIcon.resetPivot();
                };
        mSpringX.addEndListener(endListener);
        mSpringY.addEndListener(endListener);
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
