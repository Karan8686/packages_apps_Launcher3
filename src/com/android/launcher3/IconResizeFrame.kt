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
package com.android.launcher3

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import com.android.launcher3.DropTarget.DragObject
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP
import com.android.launcher3.dragndrop.DragController
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.popup.PopupContainer
import com.android.launcher3.views.BaseDragLayer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** A corner handle for changing one desktop icon's drawable size without changing its grid cell. */
class IconResizeFrame private constructor(private val icon: BubbleTextView) :
    AbstractFloatingView(icon.context, null), DragController.DragListener {
    private val launcher = Launcher.getLauncher(context)
    private val dragLayer = launcher.dragLayer
    private val item = icon.tag as WorkspaceItemInfo
    private val density = resources.displayMetrics.density
    private val padding = resources.getDimension(R.dimen.folder_resize_handle_touch_size) / 2f
    private val outline = RectF()
    private val handle = Path()
    private val handleTouchBounds = RectF()
    private val iconBounds = Rect()
    private val position = FloatArray(2)
    private val outlinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.getDimension(R.dimen.folder_resize_outline_stroke_width)
            color = context.getColor(R.color.materialColorPrimary)
        }
    private val handlePaint =
        Paint(outlinePaint).apply {
            strokeWidth = resources.getDimension(R.dimen.folder_resize_handle_stroke_width)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private var ignoreCurrentTouch = launcher.isTouchInProgress
    private var resizing = false
    private var moved = false
    private var startX = 0f
    private var startY = 0f
    private var startSizePx = 0
    private var startSizeDp = 0
    private var targetScale = 1f
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (!icon.isAttachedToWindow || icon.tag !== item || item.container != CONTAINER_DESKTOP) {
            close(false)
        } else {
            updateFrame()
            if (ignoreCurrentTouch && !launcher.isTouchInProgress) {
                ignoreCurrentTouch = false
                animate().alpha(1f).setDuration(150).start()
            }
        }
        true
    }

    init {
        setWillNotDraw(false)
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.icon_frame_name, item.title)
        alpha = if (ignoreCurrentTouch) 0f else 1f
    }

    private fun updateFrame() {
        icon.getIconBounds(iconBounds)
        position[0] = iconBounds.left.toFloat()
        position[1] = iconBounds.top.toFloat()
        targetScale = dragLayer.getDescendantCoordRelativeToSelf(icon, position)
        val size = icon.iconSize * targetScale
        val lp = layoutParams as BaseDragLayer.LayoutParams
        val x = (position[0] - padding).roundToInt()
        val y = (position[1] - padding).roundToInt()
        val side = (size + 2 * padding).roundToInt()
        if (lp.x == x && lp.y == y && lp.width == side && lp.height == side &&
            outline.width() == size) return
        lp.x = x
        lp.y = y
        lp.width = side
        lp.height = side
        requestLayout()
        outline.set(padding, padding, padding + size, padding + size)
        val radius = min(12 * density, size / 4)
        val cornerInset = radius * (1f - 0.70710678f)
        val cx = outline.right - cornerInset
        val cy = outline.bottom - cornerInset
        handleTouchBounds.set(cx - padding, cy - padding, cx + padding, cy + padding)
        systemGestureExclusionRects = listOf(Rect().also { handleTouchBounds.roundOut(it) })
        handle.reset()
        handle.arcTo(
            RectF(outline.right - 2 * radius, outline.bottom - 2 * radius,
                outline.right, outline.bottom),
            0f,
            90f,
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = min(12 * density, outline.width() / 4)
        canvas.drawRoundRect(outline, radius, radius, outlinePaint)
        canvas.drawPath(handle, handlePaint)
    }

    private fun beginResize(event: MotionEvent): Boolean {
        if (!handleTouchBounds.contains(event.x - left, event.y - top)) return false
        startX = event.x
        startY = event.y
        startSizePx = icon.iconSize
        startSizeDp = item.iconSizeDp
        moved = false
        resizing = true
        requestFocus()
        return true
    }

    override fun onControllerInterceptTouchEvent(event: MotionEvent): Boolean {
        if (ignoreCurrentTouch) {
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
            ignoreCurrentTouch = false
            alpha = 1f
        }
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
        if (beginResize(event)) return true
        if (launcher.dragController.isDragging) return false
        val popup = PopupContainer.getOpen(launcher)
        if (popup != null && !dragLayer.isEventOverView(popup, event)) popup.close(true)
        close(false)
        return false
    }

    override fun onControllerTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!resizing) return beginResize(event)
            MotionEvent.ACTION_MOVE -> previewResize(event)
            MotionEvent.ACTION_UP -> {
                previewResize(event)
                val changed = resizing && moved && item.iconSizeDp != startSizeDp
                resizing = false
                if (changed) saveSize()
            }
            MotionEvent.ACTION_CANCEL -> cancelResize()
        }
        return true
    }

    private fun previewResize(event: MotionEvent) {
        if (!resizing) return
        val dx = event.x - startX
        val dy = event.y - startY
        if (!moved && abs(dx) + abs(dy) < ViewConfiguration.get(context).scaledTouchSlop) return
        moved = true
        PopupContainer.getOpen(launcher)?.close(true)
        // The icon grows about its center, so a diagonal movement changes both half-edges.
        applySizePx((startSizePx + (dx + dy) / targetScale.coerceAtLeast(0.01f)).roundToInt())
    }

    private fun applySizePx(size: Int) {
        val maxSize = icon.maxCustomIconSizePx
        val minSize = min((24 * density).roundToInt(), maxSize)
        item.iconSizeDp = (size.coerceIn(minSize, maxSize) / density).roundToInt().coerceAtLeast(1)
        icon.applyWorkspaceIconSize()
    }

    private fun saveSize() {
        launcher.modelWriter.updateItemInDatabase(item)
        announceForAccessibility(context.getString(R.string.icon_resized))
    }

    private fun cancelResize() {
        if (!resizing) return
        item.iconSizeDp = startSizeDp
        icon.applyWorkspaceIconSize()
        resizing = false
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                context.getString(R.string.increase_icon_size),
            )
        )
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                context.getString(R.string.decrease_icon_size),
            )
        )
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                R.string.reset_icon_size, context.getString(R.string.reset_icon_size)
            )
        )
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ->
                applySizePx(icon.iconSize + (4 * density).roundToInt())
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ->
                applySizePx(icon.iconSize - (4 * density).roundToInt())
            R.string.reset_icon_size -> {
                item.iconSizeDp = 0
                icon.applyWorkspaceIconSize()
            }
            else -> return super.performAccessibilityAction(action, arguments)
        }
        PopupContainer.getOpen(launcher)?.close(true)
        saveSize()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP ->
            performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN ->
            performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ESCAPE -> {
            close(false)
            icon.requestFocus()
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun handleClose(animate: Boolean) {
        cancelResize()
        dragLayer.removeView(this)
    }

    override fun onDetachedFromWindow() {
        cancelResize()
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        launcher.dragController.removeDragListener(this)
        super.onDetachedFromWindow()
    }

    override fun isOfType(type: Int): Boolean = (type and TYPE_ICON_RESIZE_FRAME) != 0

    override fun onDragStart(dragObject: DragObject, options: DragOptions) = close(false)

    override fun onDragEnd() {
        // Releasing the initial long press ends pre-drag without starting an item move.
        if (mIsOpen) {
            ignoreCurrentTouch = false
            animate().alpha(1f).setDuration(150).start()
        }
    }

    companion object {
        @JvmStatic
        fun showForIcon(icon: BubbleTextView) {
            val item = icon.tag as? WorkspaceItemInfo ?: return
            if (item.container != CONTAINER_DESKTOP || !icon.isAttachedToWindow) return
            val launcher = Launcher.getLauncher(icon.context)
            if (launcher.workspace.getParentCellLayoutForView(icon) == null) return
            closeAllOpenViewsExcept(launcher, TYPE_ACTION_POPUP)
            val frame = IconResizeFrame(icon)
            frame.layoutParams = BaseDragLayer.LayoutParams(1, 1).apply { customPosition = true }
            launcher.dragLayer.addView(frame)
            frame.mIsOpen = true
            launcher.dragController.addDragListener(frame)
            frame.viewTreeObserver.addOnPreDrawListener(frame.preDrawListener)
            frame.updateFrame()
        }
    }
}
