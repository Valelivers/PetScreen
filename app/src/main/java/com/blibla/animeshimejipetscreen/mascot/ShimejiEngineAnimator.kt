package com.blibla.animeshimejipetscreen.mascot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.WindowManager
import android.widget.ImageView
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * Phase 5: the XML-driven animator.
 *
 * Replaces the hand-written FSM ([ShimejiAnimator]) with the interpreter:
 * [BehaviorScheduler] chooses behaviors, [BehaviorRunner] executes them against a
 * [MascotState], and this class only renders the resulting pose + handles the
 * touch interactions (drag / throw) and the 16ms frame pacing.
 *
 * Public API mirrors [ShimejiAnimator] so the service can swap implementations.
 */
class ShimejiEngineAnimator(
    private val imageView: ImageView,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val extractedDir: File,
    private val spec: ShimejiSpec,
    behaviorEntries: List<BehaviorEntry>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tickMs = 16L
    private var loopJob: Job? = null

    @Volatile private var paused = false
    private var pausedAtMs = 0L

    private val scheduler = BehaviorScheduler(spec, behaviorEntries)

    private val state = MascotState(
        x = layoutParams.x.toFloat(),
        y = layoutParams.y.toFloat(),
        width = max(1, layoutParams.width.takeIf { it > 0 } ?: 128),
        height = max(1, layoutParams.height.takeIf { it > 0 } ?: 128),
        anchorY = 128f
    )

    private var runner: BehaviorRunner? = null

    @Volatile private var speedMultiplier = 1.0f
    @Volatile private var scaleMultiplier = 1.0f

    @Volatile private var grabbed = false
    @Volatile private var dragX: Int? = null
    @Volatile private var dragY: Int? = null

    // thrown ballistic physics (px/tick)
    private var thrown = false
    private var throwVx = 0f
    private var throwVy = 0f
    private val gravityPerTick = 0.95f
    private val maxFallSpeed = 48f
    private val airDrag = 0.985f

    private val bmpCache = LruCache<String, Bitmap>(48)

    // render dedup
    private var lastImage: String? = null
    private var lastScale = -1f
    private var lastAX = Int.MIN_VALUE
    private var lastAY = Int.MIN_VALUE
    private var lastAW = Int.MIN_VALUE
    private var lastAH = Int.MIN_VALUE

    // snapshot for the service
    @Volatile private var lastLayoutX = layoutParams.x
    @Volatile private var lastLayoutY = layoutParams.y
    @Volatile private var lastLayoutW = max(1, layoutParams.width.takeIf { it > 0 } ?: 1)
    @Volatile private var lastLayoutH = max(1, layoutParams.height.takeIf { it > 0 } ?: 1)

    private fun sw() = imageView.resources.displayMetrics.widthPixels
    private fun sh() = imageView.resources.displayMetrics.heightPixels

    private fun env(): MascotEnv {
        val w = sw()
        val h = sh()
        return MascotEnv(
            screenWidth = w, screenHeight = h,
            workLeft = 0, workTop = 0, workRight = w, workBottom = h,
            cursorX = w / 2, cursorY = h / 2, cursorDx = 0, cursorDy = 0
        )
    }

    // ===================== lifecycle =====================

    fun start(@Suppress("UNUSED_PARAMETER") actionName: String = "") {
        stop()
        state.x = layoutParams.x.toFloat()
        state.y = layoutParams.y.toFloat()
        grabbed = false
        thrown = false
        runner = null
        forceNextRender()
        if (spec.actions.isEmpty()) return // nothing to animate (fallback image only)
        loopJob = scope.launch { mainLoop() }
    }

    fun pause() {
        if (paused) return
        paused = true
        pausedAtMs = SystemClock.uptimeMillis()
        loopJob?.cancel()
        loopJob = null
    }

    fun resume() {
        if (!paused) return
        paused = false
        if (spec.actions.isEmpty()) return
        if (loopJob == null) {
            forceNextRender()
            loopJob = scope.launch { mainLoop() }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        paused = false
    }

    fun destroy() {
        stop()
        bmpCache.evictAll()
        scope.cancel()
    }

    // ===================== external controls =====================

    fun setScale(m: Float) { scaleMultiplier = m.coerceIn(0.5f, 5.0f) }
    fun setSpeed(m: Float) { speedMultiplier = m.coerceIn(0.5f, 3.0f) }

    fun setGrabbed(isGrabbed: Boolean) {
        grabbed = isGrabbed
        if (isGrabbed) {
            thrown = false
            runner = null
            dragX = state.x.toInt()
            dragY = state.y.toInt()
        } else {
            dragX = null
            dragY = null
            runner = null // scheduler will pick (Fall if airborne)
        }
    }

    fun setDragTarget(nx: Int, ny: Int) { dragX = nx; dragY = ny }

    fun dragTo(nx: Int, ny: Int) {
        val w = max(1, layoutParams.width.takeIf { it > 0 } ?: state.width)
        val h = max(1, layoutParams.height.takeIf { it > 0 } ?: state.height)
        val cx = nx.coerceIn(0, max(0, sw() - w))
        val cy = ny.coerceIn(0, max(0, sh() - h))
        dragX = cx; dragY = cy
        state.x = cx.toFloat(); state.y = cy.toFloat()
        layoutParams.x = cx; layoutParams.y = cy
        try {
            windowManager.updateViewLayout(imageView, layoutParams)
            lastAX = cx; lastAY = cy; lastAW = layoutParams.width; lastAH = layoutParams.height
        } catch (_: Throwable) {}
    }

    fun setPosition(nx: Int, ny: Int) {
        state.x = nx.toFloat(); state.y = ny.toFloat()
        layoutParams.x = nx; layoutParams.y = ny
    }

    fun getLastLayoutPosition(): Pair<Int, Int> = lastLayoutX to lastLayoutY
    fun getLastLayoutSize(): Pair<Int, Int> = lastLayoutW to lastLayoutH

    fun stickToNearestWall() {
        val w = max(1, layoutParams.width.takeIf { it > 0 } ?: state.width)
        val nearLeft = state.x <= (sw() - w) - state.x
        state.x = if (nearLeft) 0f else (sw() - w).toFloat()
        state.lookRight = !nearLeft
        thrown = false
        runner = scheduler.runnerFor("HoldOntoWall", state, ::env, { speedMultiplier }, { scaleMultiplier })
    }

    fun throwWithVelocity(vxPxPerSec: Float, vyPxPerSec: Float) {
        grabbed = false
        thrown = true
        runner = null
        val dt = 16f / 1000f
        // gentler throw: lower gain + tighter clamps so it doesn't whip across screen
        throwVx = (vxPxPerSec * dt * 0.55f).coerceIn(-26f, 26f)
        throwVy = (vyPxPerSec * dt * 0.55f).coerceIn(-34f, 34f)
        if (vxPxPerSec > 80f) state.lookRight = true
        else if (vxPxPerSec < -80f) state.lookRight = false
    }

    // ===================== main loop =====================

    private suspend fun mainLoop() {
        while (coroutineContext.isActive) {
            val frameStart = SystemClock.uptimeMillis()
            val w = sw()
            val h = sh()

            val pose: Pose? = when {
                grabbed -> {
                    dragX?.let { state.x = it.toFloat() }
                    dragY?.let { state.y = it.toFloat() }
                    grabbedPose()
                }
                thrown -> { stepThrown(h); fallingPose() }
                else -> stepBehavior()
            }

            if (pose == null) { pace(frameStart); continue }

            render(pose)

            // position -> layout
            layoutParams.x = state.x.toInt().coerceIn(0, max(0, w - layoutParams.width))
            layoutParams.y = state.y.toInt().coerceIn(0, max(0, h - layoutParams.height))
            state.x = layoutParams.x.toFloat()
            state.y = layoutParams.y.toFloat()

            if (layoutParams.x != lastAX || layoutParams.y != lastAY ||
                layoutParams.width != lastAW || layoutParams.height != lastAH
            ) {
                try {
                    windowManager.updateViewLayout(imageView, layoutParams)
                } catch (t: Throwable) {
                    Log.e("SHIMEJI", "updateViewLayout failed", t); break
                }
                lastAX = layoutParams.x; lastAY = layoutParams.y
                lastAW = layoutParams.width; lastAH = layoutParams.height
            }

            lastLayoutX = layoutParams.x
            lastLayoutY = layoutParams.y
            lastLayoutW = max(1, layoutParams.width)
            lastLayoutH = max(1, layoutParams.height)

            pace(frameStart)
        }
    }

    private fun stepBehavior(): Pose? {
        var r = runner
        if (r == null || r.isFinished) {
            r = scheduler.nextRunner(state, ::env, { speedMultiplier }, { scaleMultiplier })
            runner = r
        }
        if (r == null) return standPose()
        r.step()
        return r.currentPose ?: standPose()
    }

    private fun stepThrown(h: Int) {
        state.x += throwVx
        state.y += throwVy
        throwVx *= airDrag
        throwVy = (throwVy + gravityPerTick).coerceAtMost(maxFallSpeed)

        val floorY = (h - state.height).toFloat()
        if (state.y >= floorY) {
            state.y = floorY
            thrown = false
            runner = null
        }
    }

    private suspend fun pace(frameStart: Long) {
        val workMs = SystemClock.uptimeMillis() - frameStart
        delay((tickMs - workMs).coerceIn(1L, tickMs))
    }

    // ===================== rendering =====================

    private fun render(pose: Pose) {
        val bmp = loadBitmap(pose.image) ?: return
        if (pose.image != lastImage || scaleMultiplier != lastScale) {
            imageView.setImageBitmap(bmp)
            layoutParams.width = (bmp.width * scaleMultiplier).toInt().coerceAtLeast(1)
            layoutParams.height = (bmp.height * scaleMultiplier).toInt().coerceAtLeast(1)
            state.width = layoutParams.width
            state.height = layoutParams.height
            lastImage = pose.image
            lastScale = scaleMultiplier
        }
        // base sprites face left; flip when looking right
        imageView.scaleX = if (state.lookRight) -1f else 1f
        imageView.scaleY = 1f
    }

    private fun loadBitmap(name: String): Bitmap? {
        bmpCache.get(name)?.let { return it }
        val f = File(extractedDir, name)
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        bmpCache.put(name, bmp)
        return bmp
    }

    // ===================== pose helpers =====================

    private fun firstPoseOf(action: ActionDef?): Pose? =
        action?.clips?.firstOrNull { it.condition == null }?.poses?.firstOrNull()
            ?: action?.clips?.firstOrNull()?.poses?.firstOrNull()

    private fun standPose(): Pose? =
        firstPoseOf(spec.actions["Stand"])
            ?: firstPoseOf(spec.actions.values.firstOrNull())

    private fun fallingPose(): Pose? =
        firstPoseOf(spec.actions["Falling"]) ?: standPose()

    private fun grabbedPose(): Pose? {
        val pinched = spec.actions["Pinched"] ?: return standPose()
        // 7 conditional lean clips -> hold the middle (upright) one
        val clip = pinched.clips.getOrNull(pinched.clips.size / 2) ?: pinched.clips.firstOrNull()
        return clip?.poses?.firstOrNull() ?: standPose()
    }

    private fun forceNextRender() {
        lastImage = null
        lastScale = -1f
        lastAX = Int.MIN_VALUE
        lastAY = Int.MIN_VALUE
        lastAW = Int.MIN_VALUE
        lastAH = Int.MIN_VALUE
    }
}
