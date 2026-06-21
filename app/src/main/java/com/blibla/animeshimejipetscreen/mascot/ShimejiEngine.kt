package com.blibla.animeshimejipetscreen.mascot

import kotlin.math.abs

/**
 * Phase 3 of the Shimeji interpreter: the action runtime.
 *
 * Given a [ShimejiSpec] (Phase 1) and the expression evaluator (Phase 2), this
 * executes actions the way the Group-Finity desktop engine does, driven one
 * 16ms tick at a time so it can plug into the existing animator loop later
 * (Phase 5).
 *
 * Implemented action kinds (Android profile):
 *  - STAY      : hold on a border for a Duration (looping its animation)
 *  - MOVE      : walk/climb toward TargetX (floor/ceiling) or TargetY (wall)
 *  - ANIMATE   : play the animation once
 *  - EMBEDDED  : Look (face a direction), Offset (nudge), Fall (gravity),
 *                Jump (ballistic toward a target)
 *  - SEQUENCE  : run children in order
 *  - SELECT    : run the first child whose condition holds
 *
 * Conditions/params (`${..}` / `#{..}`) are resolved through [MascotExprContext]
 * so per-animation clips (ClimbWall up/down, the 7 Pinched lean poses, ...) and
 * call-site params (TargetX, Duration, InitialVX, ...) finally work.
 *
 * NOTE: not yet wired into ShimejiAnimator/Service. Behavior *selection* (the
 * frequency table) is Phase 4; replacing the FSM is Phase 5.
 */

/** Screen / work-area geometry, in pixels. workArea may exclude system insets. */
data class MascotEnv(
    val screenWidth: Int,
    val screenHeight: Int,
    val workLeft: Int,
    val workTop: Int,
    val workRight: Int,
    val workBottom: Int,
    val cursorX: Int,
    val cursorY: Int,
    val cursorDx: Int,
    val cursorDy: Int
) {
    val workWidth get() = (workRight - workLeft).coerceAtLeast(0)
    val workHeight get() = (workBottom - workTop).coerceAtLeast(0)
}

/** Mutable runtime state of one mascot. Position is top-left of the sprite. */
class MascotState(
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Int = 1,
    var height: Int = 1,
    var anchorX: Float = 0f,
    var anchorY: Float = 0f,
    var lookRight: Boolean = false,
    var vx: Float = 0f,
    var vy: Float = 0f
) {
    /** Absolute anchor point (the mascot's "foot"/contact point). */
    val anchorAbsX get() = x + anchorX
    val anchorAbsY get() = y + anchorY
}

enum class ActionStatus { RUNNING, DONE }

/** Plays one [AnimationClip], advancing by real ticks (decoupled frame timing). */
class ClipPlayer(private val clip: AnimationClip, private val speed: Float = 1f) {
    private var index = 0
    private var accumMs = 0f
    private val tickMs = 16f

    val currentPose: Pose get() = clip.poses[index.coerceIn(0, clip.poses.lastIndex)]

    /** @return true when the animation has played through at least once. */
    fun step(): Boolean {
        if (clip.poses.isEmpty()) return true
        val durMs = (currentPose.durationTicks.coerceAtLeast(1) * tickMs) / speed.coerceAtLeast(0.1f)
        accumMs += tickMs
        var looped = false
        while (accumMs >= durMs) {
            accumMs -= durMs
            index++
            if (index >= clip.poses.size) {
                index = 0
                looped = true
            }
        }
        return looped
    }

    fun reset() {
        index = 0
        accumMs = 0f
    }
}

/**
 * ExprContext bound to a mascot + environment, plus the call-site params of the
 * action currently being set up (TargetX, Gap, ...). Resolves the variables that
 * appear in the stock actions.xml / behaviors.xml.
 */
class MascotExprContext(
    private val state: MascotState,
    private val env: MascotEnv,
    private val params: Map<String, String> = emptyMap()
) : ExprContext {

    override fun resolveVariable(name: String): Double? = when (name) {
        "mascot.anchor.x" -> state.anchorAbsX.toDouble()
        "mascot.anchor.y" -> state.anchorAbsY.toDouble()
        "mascot.lookRight" -> if (state.lookRight) 1.0 else 0.0
        "FootX" -> state.anchorAbsX.toDouble()
        "FootY" -> state.anchorAbsY.toDouble()
        "TargetX" -> param("TargetX")
        "TargetY" -> param("TargetY")

        "mascot.environment.screen.width" -> env.screenWidth.toDouble()
        "mascot.environment.screen.height" -> env.screenHeight.toDouble()

        "mascot.environment.workArea.left" -> env.workLeft.toDouble()
        "mascot.environment.workArea.top" -> env.workTop.toDouble()
        "mascot.environment.workArea.right" -> env.workRight.toDouble()
        "mascot.environment.workArea.bottom" -> env.workBottom.toDouble()
        "mascot.environment.workArea.width" -> env.workWidth.toDouble()
        "mascot.environment.workArea.height" -> env.workHeight.toDouble()

        "mascot.environment.cursor.x" -> env.cursorX.toDouble()
        "mascot.environment.cursor.y" -> env.cursorY.toDouble()
        "mascot.environment.cursor.dx" -> env.cursorDx.toDouble()
        "mascot.environment.cursor.dy" -> env.cursorDy.toDouble()

        else -> params[name]?.let { ShimejiExpr.eval(it, this) }
    }

    private fun param(key: String): Double? =
        params[key]?.let { ShimejiExpr.eval(it, this) }

    /** Border predicates (isOn) are resolved structurally by the runner, so here
     *  they degrade to "false"; IE-related calls are never present in the Android
     *  profile spec. */
    override fun resolveCall(name: String, args: List<Double>): Double? = 0.0
}

/**
 * Runs a single primitive [ActionDef] (STAY / MOVE / ANIMATE / EMBEDDED) against a
 * [MascotState], one tick at a time.
 */
class PrimitiveActionRunner(
    private val def: ActionDef,
    private val params: Map<String, String>,
    private val state: MascotState,
    private val env: MascotEnv,
    private val speed: Float = 1f,
    private val scale: Float = 1f
) {
    private val ctx = MascotExprContext(state, env, params)
    private var player: ClipPlayer = ClipPlayer(pickClip(), speed)

    private var elapsedTicks = 0
    private val durationTicks: Int =
        ShimejiExpr.evalInt(params["Duration"], ctx, def.attrs["Duration"]?.toFloatOrNull()?.toInt() ?: 0)

    // resolved navigation targets (absolute px)
    private val targetX: Float? = params["TargetX"]?.let { ShimejiExpr.eval(it, ctx).toFloat() }
    private val targetY: Float? = params["TargetY"]?.let { ShimejiExpr.eval(it, ctx).toFloat() }

    // gravity / resistance for Fall
    private val gravity = (def.attrs["Gravity"]?.toFloatOrNull() ?: 2f)
    private val resistX = (def.attrs["RegistanceX"] ?: def.attrs["ResistanceX"])?.toFloatOrNull() ?: 0.05f
    private val resistY = (def.attrs["RegistanceY"] ?: def.attrs["ResistanceY"])?.toFloatOrNull() ?: 0.1f

    init {
        // Move actions decide facing from where the target is.
        if (def.type == ActionType.MOVE && targetX != null && def.borderType != BorderType.WALL) {
            state.lookRight = targetX > state.anchorAbsX
        }
        // Embedded one-shots that change facing/position immediately.
        when (def.embeddedClass?.substringAfterLast('.')) {
            "Look" -> applyLook()
            "Offset" -> applyOffset()
            "Jump" -> setupJump()
            "Fall" -> { state.vy = 0f }
        }
    }

    val currentPose: Pose get() = player.currentPose

    fun step(): ActionStatus {
        val looped = player.step()
        state.anchorX = currentPose.anchorX.toFloat() * scale
        state.anchorY = currentPose.anchorY.toFloat() * scale

        return when (def.type) {
            ActionType.STAY -> stepStay()
            ActionType.ANIMATE -> if (looped) ActionStatus.DONE else ActionStatus.RUNNING
            ActionType.MOVE -> stepMove()
            ActionType.EMBEDDED -> stepEmbedded(looped)
            else -> ActionStatus.DONE
        }
    }

    // ---- per-type steppers ----

    private fun stepStay(): ActionStatus {
        elapsedTicks++
        keepOnBorder()
        return if (durationTicks > 0 && elapsedTicks >= durationTicks) ActionStatus.DONE
        else ActionStatus.RUNNING
    }

    private fun stepMove(): ActionStatus {
        val pose = currentPose
        return when (def.borderType) {
            BorderType.WALL -> {
                val dir = if ((targetY ?: state.anchorAbsY) < state.anchorAbsY) -1f else 1f
                state.y += abs(pose.vy) * scale * dir
                stickToWall()
                if (targetY != null && abs(state.anchorAbsY - targetY) < 4f) ActionStatus.DONE
                else ActionStatus.RUNNING
            }
            BorderType.CEILING -> {
                val dir = if (state.lookRight) 1f else -1f
                state.x += abs(pose.vx) * scale * dir
                state.y = (env.workTop).toFloat()
                if (targetX != null && abs(state.anchorAbsX - targetX) < 4f) ActionStatus.DONE
                else ActionStatus.RUNNING
            }
            else -> { // FLOOR
                val dir = if (state.lookRight) 1f else -1f
                state.x += abs(pose.vx) * scale * dir
                state.y = (env.workBottom - state.height).toFloat()
                if (targetX != null && abs(state.anchorAbsX - targetX) < 4f) ActionStatus.DONE
                else ActionStatus.RUNNING
            }
        }
    }

    private fun stepEmbedded(looped: Boolean): ActionStatus =
        when (def.embeddedClass?.substringAfterLast('.')) {
            "Fall" -> stepFall()
            "Jump" -> stepJump()
            "Look", "Offset" -> ActionStatus.DONE   // instantaneous, applied in init
            else -> if (looped) ActionStatus.DONE else ActionStatus.RUNNING
        }

    // ---- embedded physics ----

    private fun stepFall(): ActionStatus {
        state.vy = (state.vy + gravity) * (1f - resistY).coerceIn(0f, 1f)
        state.vx *= (1f - resistX).coerceIn(0f, 1f)
        state.x += state.vx
        state.y += state.vy

        val floorY = (env.workBottom - state.height).toFloat()
        if (state.y >= floorY) {
            state.y = floorY
            state.vy = 0f
            return ActionStatus.DONE
        }
        return ActionStatus.RUNNING
    }

    private fun setupJump() {
        val v = (def.attrs["VelocityParam"]?.toFloatOrNull() ?: 20f)
        val tx = targetX ?: state.anchorAbsX
        val ty = targetY ?: (state.anchorAbsY - 100f)
        val dx = tx - state.anchorAbsX
        val dy = ty - state.anchorAbsY
        val dist = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
        state.vx = v * (dx / dist)
        state.vy = v * (dy / dist)
        if (abs(dx) > 1f) state.lookRight = dx > 0
    }

    private fun stepJump(): ActionStatus {
        state.vy += 1.0f // light gravity arc
        state.x += state.vx
        state.y += state.vy
        val reachedY = targetY != null && state.anchorAbsY >= targetY && state.vy > 0
        val floorY = (env.workBottom - state.height).toFloat()
        if (reachedY || state.y >= floorY) {
            if (state.y >= floorY) state.y = floorY
            return ActionStatus.DONE
        }
        return ActionStatus.RUNNING
    }

    private fun applyLook() {
        params["LookRight"]?.let { state.lookRight = ShimejiExpr.evalBool(it, ctx, state.lookRight) }
    }

    private fun applyOffset() {
        params["X"]?.let { state.x += ShimejiExpr.eval(it, ctx).toFloat() * scale }
        params["Y"]?.let { state.y += ShimejiExpr.eval(it, ctx).toFloat() * scale }
    }

    // ---- border helpers ----

    private fun keepOnBorder() {
        when (def.borderType) {
            BorderType.FLOOR -> state.y = (env.workBottom - state.height).toFloat()
            BorderType.CEILING -> state.y = env.workTop.toFloat()
            BorderType.WALL -> stickToWall()
            BorderType.NONE -> Unit
        }
    }

    private fun stickToWall() {
        // keep the side we're already nearest to
        val nearLeft = state.anchorAbsX <= env.workLeft + (env.workWidth / 2)
        state.x = if (nearLeft) (env.workLeft - state.anchorX) else (env.workRight - state.anchorX)
    }

    /** Choose the animation clip whose condition currently holds. */
    private fun pickClip(): AnimationClip {
        val c = MascotExprContext(state, env, params)
        return def.clips.firstOrNull { it.condition != null && ShimejiExpr.evalBool(it.condition, c) }
            ?: def.clips.firstOrNull { it.condition == null }
            ?: def.clips.first()
    }
}

/**
 * Walks a behavior tree (SEQUENCE / SELECT and nested nodes), resolving the
 * primitive at each leaf into a [PrimitiveActionRunner]. Call [step] each tick.
 */
class BehaviorRunner(
    private val spec: ShimejiSpec,
    private val root: BehaviorNode,
    private val state: MascotState,
    private val envProvider: () -> MascotEnv,
    private val speed: () -> Float = { 1f },
    private val scale: () -> Float = { 1f }
) {
    // flattened leaf actions to execute in order (resolved lazily for SELECT)
    private val frame = ArrayDeque<Step>()
    private var current: PrimitiveActionRunner? = null
    private var finished = false

    private data class Step(
        val refName: String,
        val params: Map<String, String>,
        val node: BehaviorNode? = null
    )

    init { enqueueNode(root) }

    val isFinished get() = finished
    val currentPose: Pose? get() = current?.currentPose

    fun step(): ActionStatus {
        if (finished) return ActionStatus.DONE

        if (current == null) {
            if (!advanceToNextPrimitive()) {
                if (root.loop) { enqueueNode(root); if (!advanceToNextPrimitive()) { finished = true; return ActionStatus.DONE } }
                else { finished = true; return ActionStatus.DONE }
            }
        }

        val runner = current ?: run { finished = true; return ActionStatus.DONE }
        if (runner.step() == ActionStatus.DONE) current = null
        return ActionStatus.RUNNING
    }

    /** Pull the next leaf step and turn it into a runnable primitive. */
    private fun advanceToNextPrimitive(): Boolean {
        while (frame.isNotEmpty()) {
            val step = frame.removeFirst()
            if (step.node != null) {                 // anonymous nested Sequence/Select
                expandNodeFront(step.node, step.params)
                continue
            }
            val def = spec.actions[step.refName]
            if (def != null) {
                current = PrimitiveActionRunner(def, step.params, state, envProvider(), speed(), scale())
                return true
            }
            // a referenced behavior (composed) -> expand inline
            spec.behaviors[step.refName]?.let { node ->
                expandNodeFront(node, step.params)
            }
        }
        return false
    }

    private fun enqueueNode(node: BehaviorNode) {
        val env = envProvider()
        val ctx = MascotExprContext(state, env)
        when (node.type) {
            ActionType.SELECT -> {
                val chosen = node.children.firstOrNull { childMatches(it, ctx) }
                chosen?.let { addChild(it, end = true) }
            }
            else -> node.children.forEach { addChild(it, end = true) } // SEQUENCE
        }
    }

    /** Expand a nested behavior at the FRONT (so it runs before remaining steps). */
    private fun expandNodeFront(node: BehaviorNode, @Suppress("UNUSED_PARAMETER") params: Map<String, String>) {
        val env = envProvider()
        val ctx = MascotExprContext(state, env)
        val children = when (node.type) {
            ActionType.SELECT -> listOfNotNull(node.children.firstOrNull { childMatches(it, ctx) })
            else -> node.children
        }
        // insert preserving order at the front
        children.asReversed().forEach { child ->
            when (child) {
                is RefChild -> frame.addFirst(Step(child.ref.name, child.ref.params))
                is NodeChild -> frame.addFirst(Step("", emptyMap(), child.node))
            }
        }
    }

    private fun addChild(child: SpecChild, end: Boolean) {
        val step = when (child) {
            is RefChild -> Step(child.ref.name, child.ref.params)
            is NodeChild -> Step("", emptyMap(), child.node)
        }
        if (end) frame.addLast(step) else frame.addFirst(step)
    }

    private fun childMatches(child: SpecChild, ctx: MascotExprContext): Boolean {
        val cond = when (child) {
            is RefChild -> child.ref.params["Condition"]
            is NodeChild -> child.node.condition
        }
        return cond == null || ShimejiExpr.evalBool(cond, ctx, default = true)
    }
}
