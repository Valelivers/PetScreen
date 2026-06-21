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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Clean, deterministic-ish Shimeji animator:
 * - Drag: smooth follow (service only sets drag target)
 * - AIR gravity + friction + wall-slide + optional wall-grab
 * - THROWN physics + quick land + optional wall grab on impact
 * - Landing: bounce / trip feel (short) then recover
 * - Clamp: bounds for normal, anchor clamp only for WALL/CEILING
 * - Bitmap cache (avoid decode every frame)
 */
class ShimejiAnimator(
    private val imageView: ImageView,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val extractedDir: File,
    private val actions: Map<String, ActionAnim>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val tickMs = 16L
    private val actionFrame = mutableMapOf<String, Int>()
    private var loopJob: Job? = null

    @Volatile private var paused = false
    private var pausedAtMs: Long = 0L

    private var currentAction: ActionAnim? = null
    private var currentActionName: String = ""

    private enum class State { IDLE, MOVE, AIR, WALL, CEILING, RECOVER, GRABBED, THROWN }
    private var state: State = State.IDLE
    private var nextDecisionAtMs: Long = 0L

    // position int + float (subpixel)
    private var x = layoutParams.x
    private var y = layoutParams.y
    private var xf = x.toFloat()
    private var yf = y.toFloat()

    @Volatile private var speedMultiplier: Float = 1.0f
    @Volatile private var scaleMultiplier: Float = 1.0f

    // physics
    private var vyExtra = 0f
    private var vxExtra = 0f
    private var facingRight = true

    // thrown physics (px per tick)
    private var throwVx = 0f
    private var throwVy = 0f

    private var prevOnFloor = true
    @Volatile private var grabbed = false

    // ===== wall =====
    private var wallTargetY: Int = 0
    private var wallPauseUntilMs: Long = 0L
    private var wallDirUp: Boolean = true
    private var wallClimbing = false
    private var wallHandOffsetPx = 0f

    private var wallRegrabCooldownUntilMs: Long = 0L

    private var tickCounter: Long = 0

    // drag target (service sets this)
    @Volatile private var dragTargetX: Int? = null
    @Volatile private var dragTargetY: Int? = null

    // snapshot for service near-wall detection
    @Volatile private var lastLayoutX: Int = layoutParams.x
    @Volatile private var lastLayoutY: Int = layoutParams.y
    @Volatile private var lastLayoutW: Int = max(1, layoutParams.width.takeIf { it > 0 } ?: 1)
    @Volatile private var lastLayoutH: Int = max(1, layoutParams.height.takeIf { it > 0 } ?: 1)

    // ---- feel tuning ----
    private val gravityPerTick = 0.95f
    private val maxFallSpeed = 48f
    private val airDrag = 0.985f
    private val groundFriction = 0.82f
    private val wallSlideMax = 6.5f
    private val wallGrabChanceFromAir = 18 // %
    private val wallGrabMinSpeed = 7.5f
    private val bounceFactor = 0.38f
    private val minBounceSpeed = 14f

    // ---- bitmap cache ----
    private val bmpCache = LruCache<String, Bitmap>(32)

    private fun screenW(): Int = imageView.resources.displayMetrics.widthPixels
    private fun screenH(): Int = imageView.resources.displayMetrics.heightPixels

    private fun viewW(): Int =
        imageView.width.takeIf { it > 0 }
            ?: imageView.measuredWidth.takeIf { it > 0 }
            ?: layoutParams.width.takeIf { it > 0 }
            ?: 64

    private fun viewH(): Int =
        imageView.height.takeIf { it > 0 }
            ?: imageView.measuredHeight.takeIf { it > 0 }
            ?: layoutParams.height.takeIf { it > 0 }
            ?: 64

    private fun poseAnchorX(pose: Pose): Float = pose.anchorX * scaleMultiplier
    private fun poseAnchorY(pose: Pose): Float = pose.anchorY * scaleMultiplier

    private fun clampByBounds(sw: Int, sh: Int) {
        val wNow = max(1, layoutParams.width.takeIf { it > 0 } ?: viewW())
        val hNow = max(1, layoutParams.height.takeIf { it > 0 } ?: viewH())

        x = x.coerceIn(0, max(0, sw - wNow))
        y = y.coerceIn(0, max(0, sh - hNow))
        xf = x.toFloat()
        yf = y.toFloat()
    }

    /** Anchor clamping: keep anchor point inside screen. Best for WALL/CEILING. */
    private fun clampByAnchor(sw: Int, sh: Int, pose: Pose) {
        val ax = poseAnchorX(pose)
        val ay = poseAnchorY(pose)

        val wNow = max(1, layoutParams.width.takeIf { it > 0 } ?: viewW())
        val hNow = max(1, layoutParams.height.takeIf { it > 0 } ?: viewH())

        val anchorX = (xf + ax).coerceIn(0f, sw.toFloat())
        val anchorY = (yf + ay).coerceIn(0f, sh.toFloat())

        xf = anchorX - ax
        yf = anchorY - ay

        // safety
        xf = xf.coerceIn(-wNow.toFloat(), sw.toFloat())
        yf = yf.coerceIn(-hNow.toFloat(), sh.toFloat())

        x = xf.toInt()
        y = yf.toInt()
    }

    fun start(actionName: String) {
        stop()
        playAction(actionName, forceRestart = true)

        nextDecisionAtMs = 0L
        vyExtra = 0f
        vxExtra = 0f
        throwVx = 0f
        throwVy = 0f

        grabbed = false
        state = State.IDLE
        prevOnFloor = true

        x = layoutParams.x
        y = layoutParams.y
        xf = x.toFloat()
        yf = y.toFloat()

        tickCounter = 0
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

        val now = SystemClock.uptimeMillis()
        val delta = (now - pausedAtMs).coerceAtLeast(0L)

        // geser deadline agar FSM tidak "kaget" setelah pause
        if (nextDecisionAtMs > 0L) nextDecisionAtMs += delta
        if (wallPauseUntilMs > 0L) wallPauseUntilMs += delta
        if (wallRegrabCooldownUntilMs > 0L) wallRegrabCooldownUntilMs += delta

        // start loop lagi tanpa reset state/physics
        if (loopJob == null) {
            loopJob = scope.launch { mainLoop() }
        }
    }

    fun setScale(multiplier: Float) {
        scaleMultiplier = multiplier.coerceIn(0.5f, 5.0f)
    }

    fun setSpeed(multiplier: Float) {
        speedMultiplier = multiplier.coerceIn(0.5f, 3.0f)
    }

    fun setGrabbed(isGrabbed: Boolean) {
        grabbed = isGrabbed
        if (grabbed) {
            state = State.GRABBED
            dragTargetX = x
            dragTargetY = y

            vyExtra = 0f
            vxExtra = 0f
            throwVx = 0f
            throwVy = 0f

            if (actions.containsKey("Pinched")) playAction("Pinched", forceRestart = true)
            nextDecisionAtMs = SystemClock.uptimeMillis() + 999_999
        } else {
            dragTargetX = null
            dragTargetY = null

            val sh = screenH()
            val hNow = max(1, layoutParams.height.takeIf { it > 0 } ?: viewH())
            val onFloorNow = (y + hNow >= sh)
            val onTopNow = (y <= 0)

            if (!onFloorNow && !onTopNow) {
                state = State.AIR
                vyExtra = 0f
                nextDecisionAtMs = 0L
                if (actions.containsKey("Falling")) playAction("Falling", forceRestart = true)
            } else {
                state = State.IDLE
                nextDecisionAtMs = 0L
            }
        }
    }

    fun setDragTarget(nx: Int, ny: Int) {
        dragTargetX = nx
        dragTargetY = ny
    }

    fun setPosition(nx: Int, ny: Int) {
        x = nx
        y = ny
        xf = nx.toFloat()
        yf = ny.toFloat()
        layoutParams.x = nx
        layoutParams.y = ny
    }

    fun getLastLayoutPosition(): Pair<Int, Int> = lastLayoutX to lastLayoutY
    fun getLastLayoutSize(): Pair<Int, Int> = lastLayoutW to lastLayoutH

    /** Snap ke wall terdekat dan masuk WALL mode. */
    fun stickToNearestWall() {
        if (!actions.containsKey("GrabWall") && !actions.containsKey("ClimbWall")) return

        val sw = screenW()
        val wNow = max(1, layoutParams.width.takeIf { it > 0 } ?: viewW())

        val distLeft = x
        val distRight = (sw - (x + wNow)).coerceAtLeast(0)

        if (distLeft <= distRight) {
            x = 0
            facingRight = true
        } else {
            x = sw - wNow
            facingRight = false
        }
        xf = x.toFloat()
        enterWallMode(fromThrown = false)
    }

    /** Throw beneran (velocity finger px/s) */
    fun throwWithVelocity(vxPxPerSec: Float, vyPxPerSec: Float) {
        grabbed = false
        state = State.THROWN
        nextDecisionAtMs = SystemClock.uptimeMillis() + rand(120, 220)

        if (vxPxPerSec > 80f) facingRight = true
        else if (vxPxPerSec < -80f) facingRight = false

        val dt = 16f / 1000f
        var vx = vxPxPerSec * dt
        var vy = vyPxPerSec * dt

        vx = (vx * 1.15f).coerceIn(-50f, 50f)
        vy = (vy * 1.05f).coerceIn(-70f, 70f)

        throwVx = vx
        throwVy = vy
        vyExtra = 0f
        vxExtra = 0f

        if (actions.containsKey("Falling")) playAction("Falling", forceRestart = true)
    }

    private fun enterWallMode(fromThrown: Boolean) {
        state = State.WALL
        val now = SystemClock.uptimeMillis()

        wallClimbing = false
        wallPauseUntilMs = now + if (fromThrown) rand(30, 70) else rand(120, 220)
        pickNewWallTarget()

        if (actions.containsKey("GrabWall")) playAction("GrabWall", forceRestart = true)
        nextDecisionAtMs = wallPauseUntilMs
    }

    private fun pickNewWallTarget() {
        val sh = screenH()
        wallTargetY = (sh * (0.12f + Random.nextFloat() * 0.62f)).toInt()
            .coerceIn(0, max(0, sh - viewH()))
        wallDirUp = wallTargetY < y
    }

    private fun setMoveSpeedFor(actionName: String) {
        vxExtra = when (actionName) {
            "Walk" -> 1.4f
            "Run" -> 2.4f
            "Dash" -> 3.8f
            "Creep" -> 0.8f
            else -> 1.2f
        }
    }

    private fun rand(min: Int, max: Int): Long =
        (min + Random.nextInt((max - min + 1).coerceAtLeast(1))).toLong()

    private fun chance(percent: Int): Boolean = Random.nextInt(100) < percent

    private fun weightedIdle(): String {
        val pool = listOf(
            "Stand" to 45,
            "Sit" to 20,
            "Sprawl" to 10,
            "Look" to 10,
            "SitAndLookAtMouse" to 10,
            "SitAndSpinHeadAction" to 5
        ).filter { actions.containsKey(it.first) }
        return weightedPick(pool) ?: (actions.keys.firstOrNull() ?: "Stand")
    }

    private fun weightedMove(): String {
        val pool = listOf(
            "Walk" to 55,
            "Run" to 20,
            "Dash" to 10,
            "Creep" to 15
        ).filter { actions.containsKey(it.first) }
        return weightedPick(pool) ?: weightedIdle()
    }

    private fun weightedPick(pool: List<Pair<String, Int>>): String? {
        if (pool.isEmpty()) return null
        val total = pool.sumOf { it.second }
        var r = Random.nextInt(total)
        for ((name, w) in pool) {
            r -= w
            if (r < 0) return name
        }
        return pool.last().first
    }

    private fun playAction(actionName: String, forceRestart: Boolean = false) {
        val action = actions[actionName] ?: return
        if (!forceRestart && currentActionName == actionName && currentAction != null) return

        currentAction = action
        currentActionName = actionName

        if (forceRestart) actionFrame[actionName] = 0
        if (actionFrame[actionName] == null) actionFrame[actionName] = 0
    }

    private suspend fun loadBitmapCached(imageName: String): Bitmap? {
        bmpCache.get(imageName)?.let { return it }
        val file = File(extractedDir, imageName)
        if (!file.exists()) return null
        val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
        if (bmp != null) bmpCache.put(imageName, bmp)
        return bmp
    }

    private fun doLanding(now: Long) {
        val vImpact = if (state == State.THROWN) abs(throwVy) else abs(vyExtra)

        state = State.RECOVER
        nextDecisionAtMs = now + rand(180, 420)

        if (vImpact >= minBounceSpeed && actions.containsKey("Bouncing")) {
            playAction("Bouncing", forceRestart = true)
        } else if (actions.containsKey("Tripping") && chance(35)) {
            playAction("Tripping", forceRestart = true)
        } else if (actions.containsKey("Stand")) {
            playAction("Stand", forceRestart = true)
        } else {
            playAction(actions.keys.first(), forceRestart = true)
        }
    }


    private fun mainLoopClampAndApply(sw: Int, sh: Int, pose: Pose) {
        // final clamp after we know size (and after pose chosen)
        when (state) {
            State.WALL, State.CEILING -> clampByAnchor(sw, sh, pose)
            else -> {
                x = xf.toInt()
                y = yf.toInt()
                clampByBounds(sw, sh)
            }
        }
        layoutParams.x = x
        layoutParams.y = y
    }

    private suspend fun mainLoop() {
        while (coroutineContext.isActive) {

            // ===== sanity: current action & pose =====
            val a0 = currentAction ?: break
            if (a0.poses.isEmpty()) {
                Log.e("SHIMEJI", "Empty poses in action=$currentActionName")
                playAction(
                    if (actions.containsKey("Stand")) "Stand" else actions.keys.first(),
                    forceRestart = true
                )
                delay(200)
                tickCounter++
                continue
            }

            val sw = screenW()
            val sh = screenH()

            // base pose (may be overridden)
            val idx0 = actionFrame[currentActionName] ?: 0
            var actForPose = currentAction ?: a0
            var pose = actForPose.poses[idx0 % actForPose.poses.size]

            // ===== dragged follow =====
            if (grabbed) {
                val tx = (dragTargetX ?: x).toFloat()
                val ty = (dragTargetY ?: y).toFloat()

                xf += (tx - xf) * 0.72f
                yf += (ty - yf) * 0.72f

                vyExtra = 0f
                vxExtra = 0f
                throwVx = 0f
                throwVy = 0f
            }

            // ===== physics step =====
            if (!grabbed) {
                when (state) {
                    State.THROWN -> {
                        xf += throwVx
                        yf += throwVy

                        throwVx *= airDrag
                        throwVy = (throwVy + gravityPerTick * 1.25f).coerceAtMost(maxFallSpeed + 12f)
                        if (abs(throwVx) < 0.2f) throwVx = 0f
                    }

                    State.AIR -> {
                        xf += pose.vx
                        yf += pose.vy

                        vyExtra = (vyExtra + gravityPerTick).coerceAtMost(maxFallSpeed)
                        yf += vyExtra

                        vxExtra *= 0.985f
                        xf += vxExtra
                    }

                    State.WALL -> {
                        val now = SystemClock.uptimeMillis()

                        val offset = (wallHandOffsetPx * scaleMultiplier).coerceIn(-30f, 30f)
                        val axTmp = poseAnchorX(pose)
                        xf = if (facingRight) -axTmp + offset else sw - axTmp - offset

                        if (!wallClimbing && now < wallPauseUntilMs) {
                            if (actions.containsKey("GrabWall")) playAction("GrabWall")
                            nextDecisionAtMs = wallPauseUntilMs
                        } else {
                            if (!wallClimbing) {
                                wallClimbing = true
                                if (actions.containsKey("ClimbWall")) playAction("ClimbWall", forceRestart = true)
                            }

                            val dy = wallTargetY - y
                            if (abs(dy) < 18) {
                                when {
                                    chance(50) -> { // 50% lompat ke arah sebaliknya
                                        wallClimbing = false
                                        jumpFromWall(now)
                                    }
                                    chance(40) -> { // lanjut climbing normal
                                        pickNewWallTarget()
                                        wallClimbing = true
                                        if (actions.containsKey("ClimbWall")) playAction("ClimbWall")
                                        nextDecisionAtMs = now + 999_999
                                    }
                                    else -> { // lepas jatuh biasa
                                        wallClimbing = false
                                        state = State.AIR
                                        vyExtra = 0f
                                        nextDecisionAtMs = now + rand(120, 260)
                                        if (actions.containsKey("Falling")) playAction("Falling", forceRestart = true)
                                    }
                                }
                            }
                            else {
                                wallDirUp = dy < 0
                                if (actions.containsKey("ClimbWall") && currentActionName != "ClimbWall") {
                                    playAction("ClimbWall", forceRestart = true)
                                }

                                val a2 = currentAction
                                if (a2 != null && currentActionName == "ClimbWall" && a2.poses.isNotEmpty()) {
                                    val base = if (wallDirUp) 0 else 8
                                    val local = (actionFrame["ClimbWall"] ?: 0) % 8
                                    val safeIdx = (base + local).coerceIn(0, a2.poses.size - 1)
                                    pose = a2.poses[safeIdx]
                                    actForPose = a2

                                    val ax = poseAnchorX(pose)
                                    xf = if (facingRight) -ax + offset else sw - ax - offset

                                    val vyAbs = max(2.0f, abs(pose.vy))
                                    yf += if (wallDirUp) -vyAbs else vyAbs

                                    if (chance(6)) yf += min(2.5f, wallSlideMax)

                                    nextDecisionAtMs = now + 999_999
                                } else {
                                    wallClimbing = false
                                    state = State.AIR
                                    vyExtra = 0f
                                    nextDecisionAtMs = now + rand(120, 260)
                                    if (actions.containsKey("Falling")) playAction("Falling", forceRestart = true)
                                }
                            }
                        }
                    }

                    State.CEILING -> {
                        yf = 0f
                        xf += pose.vx
                    }

                    State.MOVE, State.IDLE, State.RECOVER -> {
                        xf += pose.vx
                        yf += pose.vy

                        if (state == State.MOVE) {
                            val dir = if (facingRight) 1f else -1f
                            if (abs(pose.vx) < 0.01f) xf += (vxExtra * dir)
                        } else {
                            vxExtra *= groundFriction
                            if (abs(vxExtra) < 0.05f) vxExtra = 0f
                        }
                        vyExtra = 0f
                    }

                    State.GRABBED -> Unit
                }
            }

            // ===== rough wall touch test (pre-size) =====
            x = xf.toInt()
            y = yf.toInt()

            val wApprox = max(1, layoutParams.width.takeIf { it > 0 } ?: viewW())
            val touchLeft = x <= 0
            val touchRight = (x + wApprox) >= sw

            if (!grabbed) {
                if (touchLeft) facingRight = true
                if (touchRight) facingRight = false
            }

            // ===== render =====
            val bmp = loadBitmapCached(pose.image)
            if (bmp != null) {
                imageView.setImageBitmap(bmp)
                layoutParams.width = (bmp.width * scaleMultiplier).toInt().coerceAtLeast(1)
                layoutParams.height = (bmp.height * scaleMultiplier).toInt().coerceAtLeast(1)
                imageView.scaleX = if (facingRight) 1f else -1f
                imageView.scaleY = 1f
            }

            // ===== clamp after size known =====
            mainLoopClampAndApply(sw, sh, pose)

            try {
                windowManager.updateViewLayout(imageView, layoutParams)
            } catch (t: Throwable) {
                Log.e("SHIMEJI", "updateViewLayout failed", t)
                break
            }

            // snapshot for service
            lastLayoutX = x
            lastLayoutY = y
            lastLayoutW = max(1, layoutParams.width)
            lastLayoutH = max(1, layoutParams.height)

            // ===== sensors using real size =====
            val onFloor = (y + lastLayoutH >= sh)
            val onTop = (y <= 0)
            val inAir = !onFloor && !onTop
            val nowC = SystemClock.uptimeMillis()

            // recompute wall with REAL width for sensor decisions (lebih akurat)
            val hitWallNow = (x <= 0) || ((x + lastLayoutW) >= sw)

            // =========================================================
            // HARD RESOLVE IMPACT (CRITICAL) — stop Falling immediately
            // =========================================================

            // 1) THROWN hits WALL (bukan lantai/atap)
            if (!grabbed && state == State.THROWN && hitWallNow && inAir) {

                // snap to wall (real width)
                if (x <= 0) {
                    x = 0
                    xf = 0f
                    facingRight = true
                } else {
                    x = (sw - lastLayoutW).coerceAtLeast(0)
                    xf = x.toFloat()
                    facingRight = false
                }

                // damp velocity
                throwVx = 0f
                throwVy *= 0.35f
                vyExtra = 0f

                // try grab wall; if fail -> recover (NO Falling)
                if ((actions.containsKey("GrabWall") || actions.containsKey("ClimbWall")) && chance(55)) {
                    enterWallMode(fromThrown = true)
                } else {
                    stopFallingImmediately(nowC)
                }

                layoutParams.x = x
                layoutParams.y = y
                try { windowManager.updateViewLayout(imageView, layoutParams) } catch (_: Throwable) {}
                continue
            }

            // 2) LANTAI: kalau sudah nempel lantai dan masih AIR/THROWN atau action masih Falling => paksa landing
            if (!grabbed && onFloor && (state == State.AIR || state == State.THROWN || currentActionName == "Falling")) {
                forceLandingNow(nowC)
                prevOnFloor = true

                layoutParams.x = x
                layoutParams.y = y
                try { windowManager.updateViewLayout(imageView, layoutParams) } catch (_: Throwable) {}
                continue
            }

            // 3) landed transition (normal)
            val landedNow = !prevOnFloor && onFloor
            prevOnFloor = onFloor
            if (!grabbed && landedNow) {
                doLanding(nowC)
            }

            // 4) ensure AIR state if truly airborne (avoid stuck states)
            if (!grabbed && inAir && state != State.AIR && state != State.THROWN && state != State.WALL && state != State.CEILING) {
                state = State.AIR
                vyExtra = 0f
            }

            if (!grabbed && inAir && state == State.AIR && currentActionName == "Jumping") {
                if (nowC >= nextDecisionAtMs) {
                    if (actions.containsKey("Falling")) playAction("Falling", forceRestart = true)
                }
            }

            // 5) Falling ONLY when free-fall (inAir + AIR + NOT touching wall)
            if (!grabbed && inAir && state == State.AIR && !hitWallNow) {
                if (actions.containsKey("Falling") && currentActionName != "Falling") {
                    playAction("Falling")
                }
            }

            // ===== AIR wall slide + optional grab (for AIR only) =====
            if (!grabbed && inAir && state == State.AIR && hitWallNow) {
                vyExtra = min(vyExtra, wallSlideMax)

                val canRegrab = nowC >= wallRegrabCooldownUntilMs
                if (canRegrab &&
                    (actions.containsKey("GrabWall") || actions.containsKey("ClimbWall")) &&
                    chance(wallGrabChanceFromAir)
                ) {
                    if (x <= 0) {
                        x = 0
                        xf = 0f
                        facingRight = true
                    } else {
                        x = (sw - lastLayoutW).coerceAtLeast(0)
                        xf = x.toFloat()
                        facingRight = false
                    }
                    vyExtra = 0f
                    enterWallMode(fromThrown = false)
                    continue
                }
            }

            // ===== wall -> ceiling at top =====
            if (!grabbed && state == State.WALL && onTop) {
                val now = SystemClock.uptimeMillis()
                if (actions.containsKey("GrabCeiling") && chance(65)) {
                    state = State.CEILING
                    nextDecisionAtMs = now + rand(650, 1500)
                    playAction("GrabCeiling", forceRestart = true)
                } else {
                    state = State.AIR
                    vyExtra = 0f
                    nextDecisionAtMs = now + rand(180, 420)
                    if (actions.containsKey("Falling")) playAction("Falling", forceRestart = true)
                }
            }

            // ===== ceiling behavior =====
            if (!grabbed && state == State.CEILING && onTop) {
                val now = SystemClock.uptimeMillis()
                if (now >= nextDecisionAtMs) {
                    val r = Random.nextInt(100)
                    when {
                        r < 60 && actions.containsKey("ClimbCeiling") -> {
                            playAction("ClimbCeiling")
                            nextDecisionAtMs = now + rand(800, 2100)
                        }
                        r < 84 -> {
                            if (actions.containsKey("GrabCeiling")) playAction("GrabCeiling")
                            nextDecisionAtMs = now + rand(650, 1500)
                        }
                        else -> {
                            state = State.AIR
                            vyExtra = 0f
                            nextDecisionAtMs = now + rand(180, 420)
                            if (actions.containsKey("Falling")) playAction("Falling", forceRestart = true)
                        }
                    }
                }
            }

            // ===== FSM decisions =====
            val now = SystemClock.uptimeMillis()
            if (grabbed) {
                state = State.GRABBED
                nextDecisionAtMs = now + 999_999
            } else if (now >= nextDecisionAtMs) {
                decideNextAction(now, onFloor, onTop, hitWallNow)
            }

            // ===== delay =====
            val baseDelayMs = ((pose.durationTicks.coerceAtLeast(1) * tickMs) / speedMultiplier)
                .toLong()
                .coerceAtLeast(8L)

            val delayMs = when {
                state == State.WALL && currentActionName == "ClimbWall" -> baseDelayMs.coerceIn(16L, 60L)
                state == State.AIR || state == State.THROWN || currentActionName == "Falling" -> 16L
                else -> baseDelayMs
            }

            delay(delayMs)

            // advance frame
            actionFrame[currentActionName] = (actionFrame[currentActionName] ?: 0) + 1
            tickCounter++
        }
    }

    private fun decideNextAction(now: Long, onFloor: Boolean, onTop: Boolean, hitWall: Boolean) {
        // THROWN: allow wall catch quickly
        if (state == State.THROWN) {
            if (!onFloor && !onTop && hitWall && (actions.containsKey("GrabWall") || actions.containsKey("ClimbWall"))) {
                enterWallMode(fromThrown = true)
            } else {
                nextDecisionAtMs = now + rand(120, 220)
            }
            return
        }

        // mid air
        if (!onFloor && !onTop) {
            state = State.AIR
            nextDecisionAtMs = now + rand(180, 420)
            if (actions.containsKey("Falling")) playAction("Falling")
            return
        }

        // ceiling attach
        if (onTop && actions.containsKey("GrabCeiling")) {
            state = State.CEILING
            nextDecisionAtMs = now + rand(650, 1500)
            playAction("GrabCeiling", forceRestart = true)
            return
        }

        // wall logic
        if (hitWall && (actions.containsKey("GrabWall") || actions.containsKey("ClimbWall")) && chance(28)) {
            enterWallMode(fromThrown = false)
            return
        }

        // floor: pick idle/move
        state = if (chance(55)) State.IDLE else State.MOVE
        nextDecisionAtMs = now + rand(900, 2400)

        if (state == State.MOVE) {
            val act = weightedMove()
            playAction(act)
            setMoveSpeedFor(act)
        } else {
            playAction(weightedIdle())
        }
    }

    private fun forceLandingNow(now: Long) {
        // snap to floor and kill vertical motion
        snapToFloor(screenH())
        vyExtra = 0f

        if (state == State.THROWN) {
            throwVx = 0f
            throwVy = 0f
        }

        // hard-cut falling immediately (no waiting)
        state = State.RECOVER
        nextDecisionAtMs = now + rand(80, 140) // ~0.08-0.14s

        when {
            actions.containsKey("Bouncing") && chance(55) -> playAction("Bouncing", forceRestart = true)
            actions.containsKey("Tripping") && chance(25) -> playAction("Tripping", forceRestart = true)
            actions.containsKey("Stand") -> playAction("Stand", forceRestart = true)
            else -> playAction(actions.keys.first(), forceRestart = true)
        }
    }

    private fun stopFallingImmediately(now: Long) {
        state = State.RECOVER
        nextDecisionAtMs = now + rand(80, 140)

        when {
            actions.containsKey("Bouncing") && chance(40) -> playAction("Bouncing", forceRestart = true)
            actions.containsKey("Tripping") && chance(20) -> playAction("Tripping", forceRestart = true)
            actions.containsKey("Stand") -> playAction("Stand", forceRestart = true)
            else -> playAction(actions.keys.first(), forceRestart = true)
        }
    }

    private fun jumpFromWall(now: Long) {
        state = State.AIR

        val dir = if (facingRight) 1f else -1f

        // impulse lebih besar biar gak cuma "hop"
        vxExtra = 40f * dir
        vyExtra = -6.5f

        // 1) DETACH: geser posisi menjauh dari wall supaya hitWallNow langsung false
        val sw = screenW()
        val w = lastLayoutW.coerceAtLeast(viewW())
        val detach = (12f * scaleMultiplier).coerceIn(8f, 24f)

        xf = if (facingRight) {
            // dari wall kiri (x==0), masuk ke dalam layar
            detach
        } else {
            // dari wall kanan (x==sw-w), masuk ke dalam layar
            (sw - w).toFloat() - detach
        }
        x = xf.toInt()

        // 2) COOLDOWN: jangan boleh grab wall lagi beberapa ratus ms
        wallRegrabCooldownUntilMs = now + 250L

        if (actions.containsKey("Jumping")) {
            playAction("Jumping", forceRestart = true)
            nextDecisionAtMs = now + rand(180, 260)
        } else {
            if (actions.containsKey("Falling")) playAction("Falling", forceRestart = true)
            nextDecisionAtMs = now + rand(180, 260)
        }
    }

    private fun slipFromWallOrCeiling(now: Long) {
        // detach state
        state = State.AIR
        nextDecisionAtMs = now + rand(120, 220)

        // supaya langsung mulai jatuh (nggak nempel lagi)
        wallClimbing = false
        vyExtra = 0f

        // sedikit dorongan horizontal biar kerasa "kepleset"
        val dir = if (facingRight) 1f else -1f
        vxExtra = (10f + Random.nextFloat() * 16f) * dir

        // push menjauh dari dinding biar hitWallNow langsung false
        val sw = screenW()
        val w = lastLayoutW.coerceAtLeast(viewW())
        val detach = (14f * scaleMultiplier).coerceIn(10f, 28f)

        xf = if (facingRight) {
            // dari wall kiri -> masuk layar
            detach
        } else {
            // dari wall kanan -> masuk layar
            (sw - w).toFloat() - detach
        }
        x = xf.toInt()

        // cooldown biar gak re-grab wall lagi saat baru slip
        wallRegrabCooldownUntilMs = now + 350L

        // anim: kalau punya "Tripping" boleh dipakai sebagai slip pose,
        // tapi biar jatuh tetap jelas: langsung Falling juga oke.
        when {
            actions.containsKey("Tripping") -> playAction("Tripping", forceRestart = true)
            actions.containsKey("Falling") -> playAction("Falling", forceRestart = true)
            else -> playAction(actions.keys.first(), forceRestart = true)
        }
    }

    private fun snapToFloor(sh: Int) {
        val hNow = max(1, layoutParams.height.takeIf { it > 0 } ?: viewH())
        y = sh - hNow
        yf = y.toFloat()
    }

    private fun hypot2(x: Float, y: Float): Float = kotlin.math.sqrt(x * x + y * y)

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
}
