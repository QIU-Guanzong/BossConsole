package ai.rever.boss.plugin.browser

import kotlin.math.ln

/**
 * Offers a macOS trackpad pinch to the page before BOSS zooms it.
 *
 * Chrome, Edge and Safari deliver a trackpad pinch to the page first, as a `wheel` event with
 * `ctrlKey: true`. Canvas apps (Excalidraw, Figma, Google Maps, tldraw) listen for exactly that
 * and call `preventDefault()`, so their canvas zooms and the page does not; the browser zooms
 * itself only when the page lets the event through.
 *
 * BOSS cannot rely on Chromium to do this, because the pinch never reaches it: macOS delivers
 * magnification to the Swing root pane, where `MacOSGestureHandler` picks it up (#1565). Before
 * this, every pinch went straight to page zoom, so a canvas app's whole UI scaled and its canvas
 * did not. This script rebuilds the event the page would have seen and reports whether the page
 * claimed it.
 *
 * The event is synthetic, so `isTrusted` is false. Pages that only act on trusted wheel events
 * decline it and fall back to page zoom, which is today's behaviour, so the change cannot make
 * those worse.
 */
internal object BrowserPinchScript {
    // Nesting levels of shadow roots and iframes the target search looks through. Real pages
    // nest two or three deep; the cap only exists so a pathological page cannot loop.
    private const val MAX_DESCENT = 16

    /**
     * The DOM `deltaY` Chromium puts on the wheel event it synthesizes from a pinch:
     * `-100 * ln(scale)`, where macOS reports `scale - 1` as the event's magnification. Negative
     * when pinching out, matching Ctrl+scroll-up, which every canvas app reads as zoom in.
     *
     * Magnification is clamped above -1 because `ln` is undefined at and below zero scale; no
     * real event gets near it, but a NaN or infinite literal would be a JS syntax error that
     * silently declines the pinch.
     */
    fun wheelDeltaY(magnification: Double): Double = -100.0 * ln(1.0 + magnification.coerceAtLeast(-0.99))

    /**
     * JavaScript that dispatches the pinch as a cancelable Ctrl+wheel event at the pointer and
     * evaluates to `true` when the page called `preventDefault()` on it.
     *
     * The pointer is given as a fraction of the view ([fractionX], [fractionY] in 0..1) rather
     * than in pixels, so the page can resolve it against its own viewport with no knowledge of
     * display density or page zoom on this side. Null when the pointer position is unknown, in
     * which case the event is aimed at the middle of the viewport.
     *
     * The target is the element the user actually sees under the pointer, so the script looks
     * through what `document.elementFromPoint` stops at. It descends into open shadow roots,
     * where a dispatch at the host never reaches a listener on the canvas inside. It also
     * descends into same-origin iframes, since an event dispatched at an iframe element never
     * enters the child document. A cross-origin iframe cannot be entered from here, so it gets
     * the event at the iframe element and in practice falls back to page zoom.
     */
    fun dispatch(
        magnification: Double,
        fractionX: Double?,
        fractionY: Double?,
    ): String {
        val fx = (fractionX ?: 0.5).coerceIn(0.0, 1.0)
        val fy = (fractionY ?: 0.5).coerceIn(0.0, 1.0)
        return """
            (function () {
              try {
                var win = window;
                var x = win.innerWidth * $fx;
                var y = win.innerHeight * $fy;
                var target = document.elementFromPoint(x, y) || document.body || document.documentElement;
                for (var depth = 0; target && depth < $MAX_DESCENT; depth++) {
                  var root = target.shadowRoot;
                  var inner = root && root.elementFromPoint ? root.elementFromPoint(x, y) : null;
                  if (inner && inner !== target) { target = inner; continue; }
                  if (target.tagName !== 'IFRAME' && target.tagName !== 'FRAME') break;
                  var childWin, childDoc;
                  try { childWin = target.contentWindow; childDoc = childWin && childWin.document; } catch (e) { break; }
                  if (!childDoc) break;
                  var box = target.getBoundingClientRect();
                  x -= box.left + target.clientLeft;
                  y -= box.top + target.clientTop;
                  inner = childDoc.elementFromPoint(x, y);
                  if (!inner) break;
                  win = childWin;
                  target = inner;
                }
                if (!target) return false;
                var event = new win.WheelEvent('wheel', {
                  bubbles: true,
                  cancelable: true,
                  composed: true,
                  view: win,
                  ctrlKey: true,
                  clientX: x,
                  clientY: y,
                  deltaX: 0,
                  deltaY: ${wheelDeltaY(magnification)},
                  deltaMode: 0
                });
                return !target.dispatchEvent(event);
              } catch (e) {
                return false;
              }
            })()
            """.trimIndent()
    }
}

/**
 * Where an AWT pointer falls within a Compose-measured rect, as a fraction of its width and
 * height, reconciling the two coordinate scales the way `pointerInsideBounds` does.
 *
 * A fraction rather than a point because the page resolves it against its own viewport, which
 * is in CSS pixels and scaled by page zoom; neither is known here, and a fraction needs neither.
 * Null for an empty rect, which has no inside to be a fraction of. Not clamped: the caller
 * clamps, and an out-of-range value in a test is a fact about the inputs worth seeing.
 */
internal fun pointerFractionInBounds(
    boundsPx: androidx.compose.ui.geometry.Rect,
    pointerLogical: androidx.compose.ui.geometry.Offset,
    density: Float,
): androidx.compose.ui.geometry.Offset? {
    if (boundsPx.width <= 0f || boundsPx.height <= 0f) return null
    val pointerPx = pointerLogical * density
    return androidx.compose.ui.geometry.Offset(
        (pointerPx.x - boundsPx.left) / boundsPx.width,
        (pointerPx.y - boundsPx.top) / boundsPx.height,
    )
}
