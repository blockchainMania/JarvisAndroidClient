package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.CornerRadius
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.ImageSize
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The in-lens display on Ray-Ban Display glasses.
 *
 * A singleton for the same reason VisualMemoryFrameStore is one: the glasses resource is owned by
 * StreamViewModel (which holds the DeviceSession) but is used from GeminiSessionViewModel and the
 * tool layer, and threading a reference through both would mean touching every call site.
 *
 * **Every entry point is a no-op when no display is attached.** Most Meta frames (Ray-Ban Meta,
 * Oakley Meta) have no lens at all, and attaching is gated on the device reporting the capability
 * rather than on the brand -- so on those frames this object silently does nothing and the spoken
 * flow is completely unaffected. Nothing here may ever be load-bearing for a non-display user.
 */
object GlassesDisplay {
    private const val TAG = "GlassesDisplay"

    /** The lens is 600x600. Sending a full-resolution capture would waste the link for pixels the
     * display cannot show, so images are downscaled to fit before they are sent. */
    private const val MAX_IMAGE_EDGE = 600

    /** The lens has no scrolling and no partial update, so anything past roughly this much text
     * is simply lost off the bottom. Truncating visibly is better than silently clipping: a
     * meeting summary spoken aloud can easily run several hundred characters. */
    private const val MAX_HEADING_CHARS = 110
    private const val MAX_LINE_CHARS = 70

    @Volatile private var display: Display? = null
    @Volatile private var state: DisplayState? = null
    private var stateJob: Job? = null

    /**
     * What the lens is doing, in plain Korean, for the Settings screen.
     *
     * Attaching happens deep inside a session-state collector, and failing there is by design on
     * frames with no lens -- so the only evidence was a debug log, which is worth nothing to
     * anyone without a laptop and adb attached. That made "the display does nothing" and "the
     * display is not supported here" and "the glasses-side DAT was never installed" completely
     * indistinguishable from the phone. The SDK's own failure text is surfaced verbatim so the
     * cause can be read off rather than guessed at.
     */
    private val _status = MutableStateFlow(Status("아직 글래스에 연결하지 않았습니다", null))
    val status: StateFlow<Status> = _status.asStateFlow()

    data class Status(val summary: String, val detail: String?)

    /** True once the lens is attached and ready to accept content. */
    val isReady: Boolean
        get() = display != null && state == DisplayState.STARTED

    /**
     * Attaches the lens to an already-STARTED session. Failure is expected and normal on glasses
     * without a display, so it is logged at debug level and otherwise ignored.
     */
    fun attach(session: DeviceSession, scope: CoroutineScope) {
        if (display != null) return
        _status.value = Status("렌즈 연결을 시도하는 중…", null)
        session.addDisplay()
            .onSuccess { attached ->
                display = attached
                stateJob?.cancel()
                stateJob = scope.launch {
                    attached.state.collect { newState ->
                        state = newState
                        Log.d(TAG, "Display state: $newState")
                        _status.value = if (newState == DisplayState.STARTED) {
                            Status("렌즈 사용 가능 — 화면 표시가 켜져 있습니다", null)
                        } else {
                            Status("렌즈 준비 중", "현재 상태: $newState")
                        }
                    }
                }
                Log.d(TAG, "Lens attached")
            }
            .onFailure { error, _ ->
                // Expected on frames with no lens, but from the phone this is indistinguishable
                // from a setup mistake (glasses-side DAT never installed, app or firmware too
                // old), so the reason is surfaced rather than swallowed.
                Log.d(TAG, "No lens on this device (${error.description})")
                _status.value = Status(
                    "렌즈를 사용할 수 없습니다",
                    "사유: ${error.description}",
                )
            }
    }

    fun detach(session: DeviceSession?) {
        stateJob?.cancel()
        stateJob = null
        if (display != null) {
            session?.removeDisplay()
            display = null
            state = null
            Log.d(TAG, "Lens detached")
        }
        _status.value = Status("아직 글래스에 연결하지 않았습니다", null)
    }

    /** Scales a capture down to something the 600x600 lens can actually show. */
    fun forLens(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_IMAGE_EDGE) return bitmap
        val scale = MAX_IMAGE_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * A captured photo with a heading, a few detail lines and up to two actions.
     *
     * This is the shape every current lens use has in common -- confirming a business card before
     * saving it, confirming a face was enrolled, showing who was just recognised -- so it is one
     * function rather than three near-identical ones.
     *
     * Content is sent as a whole tree every time: the display has no partial update, so there is
     * no way to swap just the photo or just a line.
     */
    suspend fun showPhotoCard(
        photo: Bitmap?,
        heading: String,
        lines: List<String> = emptyList(),
        primary: Action? = null,
        secondary: Action? = null,
    ) {
        val target = display ?: return
        if (state != DisplayState.STARTED) return
        val scaled = photo?.let { forLens(it) }
        val shownHeading = heading.ellipsize(MAX_HEADING_CHARS)
        val shownLines = lines.take(3).map { it.ellipsize(MAX_LINE_CHARS) }
        target.sendContent {
            flexBox(direction = Direction.COLUMN, gap = 10, padding = 20, background = FlexBoxBackground.CARD) {
                if (scaled != null) {
                    image(bitmap = scaled, sizePreset = ImageSize.FILL, cornerRadius = CornerRadius.MEDIUM)
                }
                text(shownHeading, style = TextStyle.HEADING)
                shownLines.forEach { line -> text(line, style = TextStyle.BODY, color = TextColor.SECONDARY) }
                if (primary != null || secondary != null) {
                    buttonGroup {
                        primary?.let { button(it.label, style = ButtonStyle.PRIMARY, onClick = it.onClick) }
                        secondary?.let { button(it.label, onClick = it.onClick) }
                    }
                }
            }
        }.onFailure { error, _ -> Log.e(TAG, "sendContent failed: ${error.description}") }
    }

    /**
     * Decodes a captured frame straight to lens size.
     *
     * Uses inSampleSize so the full-resolution bitmap is never allocated -- a glasses capture is
     * far larger than the 600px lens, and decoding it whole just to throw the pixels away would
     * be the largest allocation in this flow.
     */
    fun decodeForLens(base64: String): Bitmap? = try {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    } catch (e: Exception) {
        Log.e(TAG, "Could not decode frame for the lens: ${e.message}")
        null
    }

    private fun String.ellipsize(max: Int): String =
        if (length <= max) this else take(max - 1).trimEnd() + "…"

    /** A lens button. Callbacks are delivered from the SDK, so they must stay fast and hand off
     * to a ViewModel rather than doing work inline. */
    data class Action(val label: String, val onClick: () -> Unit)

    suspend fun clear() {
        display?.clearDisplay()
    }
}
