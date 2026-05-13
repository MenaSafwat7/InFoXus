package mina.infoxus.services

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.util.Log
import android.graphics.Color
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.widget.FrameLayout

/**
 * ShieldOverlayWindow v31: TARGETED OVERLAY CAPABILITY.
 * 
 * Supports both full-screen overlay (for app limits) and 
 * targeted node overlays (for blocking specific buttons like Uninstall/Force Stop).
 */
class ShieldOverlayWindow(private val serviceContext: Context) {
    private val windowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    // ── TARGETED OVERLAYS ─────────────────────────────────────────────
    private val activeOverlays = mutableMapOf<String, Pair<View, WindowManager.LayoutParams>>()

    // ── FULL SCREEN OVERLAY ───────────────────────────────────────────
    private var fullScreenView: BlockView? = null
    private var isFullShowing = false
    private var currentLayer = -1

    private val fullScreenParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
        alpha = 0.01f 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) fitInsetsTypes = 0 
    }

    private inner class BlockView(context: Context) : FrameLayout(context) {
        init {
            isClickable = true
            isFocusable = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            Log.d("SHIELD_DEBUG", "TOUCH BLOCKED BY OVERLAY")
            return true 
        }
        override fun dispatchTouchEvent(event: MotionEvent): Boolean = true
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showFull() {
        if (isFullShowing && fullScreenView != null) {
            try {
                windowManager.updateViewLayout(fullScreenView, fullScreenParams)
                return
            } catch (_: Exception) {
                isFullShowing = false
            }
        }
        
        val layersToTry = listOf(2010, 2038, 2003, 2032)
        for (layer in layersToTry) {
            try {
                fullScreenParams.type = layer
                if (fullScreenView == null) fullScreenView = BlockView(serviceContext)
                windowManager.addView(fullScreenView, fullScreenParams)
                isFullShowing = true
                currentLayer = layer
                Log.d("SHIELD_DEBUG", "FULL SHIELD UP: $layer")
                return
            } catch (e: Exception) {
                Log.w("SHIELD_DEBUG", "LAYER $layer FAIL: ${e.message}")
            }
        }
    }

    fun hideFull() {
        if (!isFullShowing || fullScreenView == null) return
        try {
            windowManager.removeView(fullScreenView)
            fullScreenView = null
            isFullShowing = false
            currentLayer = -1
            Log.d("SHIELD_DEBUG", "FULL SHIELD DOWN")
        } catch (_: Exception) {}
    }

    fun isShowingFull(): Boolean = isFullShowing

    // ── TARGETED METHODS ──────────────────────────────────────────────

    fun blockNode(id: String, node: AccessibilityNodeInfo) {
        if (activeOverlays.containsKey(id)) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return

        val params = targetedParams(rect)
        val view = BlockView(serviceContext)
        
        // Try to add the targeted overlay using the layer fallback chain
        val layersToTry = listOf(2032, 2003, 2038, 2010)
        for (layer in layersToTry) {
            try {
                params.type = layer
                windowManager.addView(view, params)
                activeOverlays[id] = Pair(view, params)
                Log.d("SHIELD_DEBUG", "NODE BLOCKED: $id at $rect (Layer $layer)")
                return
            } catch (e: Exception) {
                // Try next layer
            }
        }
    }

    fun unblockNode(id: String) {
        activeOverlays[id]?.let { (view, _) ->
            try { windowManager.removeView(view) } catch (e: Exception) {}
            activeOverlays.remove(id)
            Log.d("SHIELD_DEBUG", "NODE UNBLOCKED: $id")
        }
    }

    fun unblockAll() {
        activeOverlays.keys.toList().forEach { unblockNode(it) }
    }

    private fun targetedParams(rect: Rect) = WindowManager.LayoutParams(
        rect.width(),
        rect.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = rect.left
        y = rect.top
        alpha = 0.01f // Invisible but blocks touch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) fitInsetsTypes = 0
    }
    
    // For compatibility with previous ShieldManager code
    fun show() = showFull()
    fun hide() { hideFull(); unblockAll() }
}
