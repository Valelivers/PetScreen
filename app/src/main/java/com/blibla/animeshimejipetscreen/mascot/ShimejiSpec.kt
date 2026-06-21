package com.blibla.animeshimejipetscreen.mascot

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File

/**
 * Phase 1 of the Shimeji interpreter.
 *
 * Parses the full Group-Finity "actions.xml" into a rich, condition-aware model
 * instead of the flat pose list produced by [ActionsParser.parsePrimitiveActions].
 *
 * It understands:
 *  - Action [type] (Stay / Move / Animate / Sequence / Select / Embedded)
 *  - [BorderType] (Floor / Wall / Ceiling)
 *  - Embedded [ActionDef.embeddedClass] (Fall, Jump, Look, Offset, Breed, ...)
 *  - Per-<Animation> [AnimationClip.condition] (so ClimbWall up/down and the 7
 *    directional Pinched poses are kept as SEPARATE clips, not merged together)
 *  - The second <ActionList> ("behaviors"): Sequence / Select trees with params
 *    such as TargetX / TargetY / Duration / InitialVX.
 *
 * Expression strings (`${...}` / `#{...}`) are kept verbatim here; evaluating them
 * is Phase 2. Wiring this model into the animator is Phase 3+.
 */

enum class ActionType { STAY, MOVE, ANIMATE, SEQUENCE, SELECT, EMBEDDED, UNKNOWN }

enum class BorderType { FLOOR, WALL, CEILING, NONE }

/** One <Animation> block: an optional condition + the poses to play when it holds. */
data class AnimationClip(
    val condition: String?,   // raw expression, null = always applies
    val poses: List<Pose>
)

/** Common supertype so a top-level <Action> can be either a primitive or a behavior. */
sealed interface SpecNode

/** A primitive / embedded action that has <Animation> poses. */
data class ActionDef(
    val name: String,
    val type: ActionType,
    val borderType: BorderType,
    val embeddedClass: String?,            // for Type="Embedded"
    val attrs: Map<String, String>,        // raw attributes (Gravity, VelocityParam, ...)
    val clips: List<AnimationClip>
) : SpecNode

/** A reference to another action inside a behavior, with its call-site params. */
data class ActionRef(
    val name: String,
    val params: Map<String, String>        // TargetX, TargetY, Duration, Condition, ...
)

/** A child of a behavior is either a reference or a nested Sequence/Select. */
sealed interface SpecChild
data class RefChild(val ref: ActionRef) : SpecChild
data class NodeChild(val node: BehaviorNode) : SpecChild

/** A composed behavior (Sequence/Select), possibly nested and conditional. */
data class BehaviorNode(
    val name: String?,                     // null for anonymous nested nodes
    val type: ActionType,                  // SEQUENCE or SELECT
    val loop: Boolean,
    val condition: String?,
    val children: List<SpecChild>
) : SpecNode

/** Parsed mascot definition. */
data class ShimejiSpec(
    val actions: Map<String, ActionDef>,
    val behaviors: Map<String, BehaviorNode>
)

object ShimejiSpecParser {

    fun parse(xmlFile: File): ShimejiSpec {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        xmlFile.inputStream().use { input ->
            parser.setInput(input, "UTF-8")

            // advance to the root START_TAG (<Mascot>)
            var ev = parser.eventType
            while (ev != XmlPullParser.START_TAG && ev != XmlPullParser.END_DOCUMENT) {
                ev = parser.next()
            }
            if (ev != XmlPullParser.START_TAG) return ShimejiSpec(emptyMap(), emptyMap())

            val root = readElement(parser)

            val actions = LinkedHashMap<String, ActionDef>()
            val behaviors = LinkedHashMap<String, BehaviorNode>()

            root.children
                .filter { it.name == "ActionList" }
                .flatMap { it.children }
                .filter { it.name == "Action" }
                .forEach { el ->
                    when (val node = transform(el)) {
                        is ActionDef -> if (node.name.isNotBlank()) actions[node.name] = node
                        is BehaviorNode -> node.name?.let { behaviors[it] = node }
                    }
                }

            return ShimejiSpec(actions, behaviors)
        }
    }

    // ---- generic, namespace-agnostic DOM read (file is small) ----

    private data class XmlEl(
        val name: String,
        val attrs: Map<String, String>,
        val children: List<XmlEl>
    )

    /** Precondition: parser is positioned on a START_TAG. Returns positioned on its END_TAG. */
    private fun readElement(parser: XmlPullParser): XmlEl {
        val name = parser.name
        val attrs = readAttrs(parser)
        val children = ArrayList<XmlEl>()

        var ev = parser.next()
        while (true) {
            when (ev) {
                XmlPullParser.START_TAG -> children.add(readElement(parser))
                XmlPullParser.END_TAG -> return XmlEl(name, attrs, children)
                XmlPullParser.END_DOCUMENT -> return XmlEl(name, attrs, children)
            }
            ev = parser.next()
        }
    }

    private fun readAttrs(parser: XmlPullParser): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (i in 0 until parser.attributeCount) {
            m[parser.getAttributeName(i)] = parser.getAttributeValue(i)
        }
        return m
    }

    // ---- transform raw elements into the spec model ----

    private fun transform(el: XmlEl): SpecNode {
        val name = el.attrs["Name"].orEmpty()
        val type = parseType(el.attrs["Type"])
        val animations = el.children.filter { it.name == "Animation" }

        if (animations.isNotEmpty()) {
            val clips = animations.map { anim ->
                AnimationClip(
                    condition = anim.attrs["Condition"]?.takeIf { it.isNotBlank() },
                    poses = anim.children.filter { it.name == "Pose" }.map { poseFrom(it) }
                )
            }.filter { it.poses.isNotEmpty() }

            return ActionDef(
                name = name,
                type = type,
                borderType = parseBorder(el.attrs["BorderType"]),
                embeddedClass = el.attrs["Class"],
                attrs = el.attrs,
                clips = clips
            )
        }

        // otherwise it's a composed behavior (Sequence/Select)
        val children = el.children.mapNotNull { c ->
            when (c.name) {
                "ActionReference" -> RefChild(ActionRef(c.attrs["Name"].orEmpty(), c.attrs))
                "Action" -> (transform(c) as? BehaviorNode)?.let { NodeChild(it) }
                else -> null
            }
        }

        return BehaviorNode(
            name = name.ifBlank { null },
            type = if (type == ActionType.UNKNOWN) ActionType.SEQUENCE else type,
            loop = el.attrs["Loop"]?.trim()?.equals("true", ignoreCase = true) == true,
            condition = el.attrs["Condition"]?.takeIf { it.isNotBlank() },
            children = children
        )
    }

    private fun poseFrom(el: XmlEl): Pose {
        val img = (el.attrs["Image"].orEmpty()).trim().removePrefix("/")
        val (ax, ay) = parsePair(el.attrs["ImageAnchor"] ?: "0,0")
        val (vx, vy) = parsePair(el.attrs["Velocity"] ?: "0,0")
        val dur = el.attrs["Duration"]?.trim()?.toFloatOrNull()?.toInt() ?: 1
        return Pose(
            image = img,
            anchorX = ax.toInt(),
            anchorY = ay.toInt(),
            vx = vx,
            vy = vy,
            durationTicks = dur.coerceAtLeast(1)
        )
    }

    /** Tolerant "x,y" parser: accepts decimals, spaces, or missing components. */
    private fun parsePair(s: String): Pair<Float, Float> {
        val parts = s.split(",")
        val a = parts.getOrNull(0)?.trim()?.toFloatOrNull() ?: 0f
        val b = parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: 0f
        return a to b
    }

    private fun parseType(s: String?): ActionType = when (s?.trim()?.lowercase()) {
        "stay" -> ActionType.STAY
        "move" -> ActionType.MOVE
        "animate" -> ActionType.ANIMATE
        "sequence" -> ActionType.SEQUENCE
        "select" -> ActionType.SELECT
        "embedded" -> ActionType.EMBEDDED
        else -> ActionType.UNKNOWN
    }

    private fun parseBorder(s: String?): BorderType = when (s?.trim()?.lowercase()) {
        "floor" -> BorderType.FLOOR
        "wall" -> BorderType.WALL
        "ceiling" -> BorderType.CEILING
        else -> BorderType.NONE
    }
}

/**
 * "Android profile" curation: drop behaviors/actions that have no meaning on a
 * phone. Everything tied to a desktop window ("IE") and anything that relies on a
 * hover mouse cursor is removed. Breed/clone is deferred unless [includeBreed].
 */
fun ShimejiSpec.forAndroid(includeBreed: Boolean = false): ShimejiSpec {
    fun isIe(n: String) = n.contains("IE") || n.contains("Ie")

    val cursorOnly = setOf("ChaseMouse", "SitAndFaceMouse")
    val breedNames = setOf(
        "PullUpShimeji", "PullUpShimeji1", "PullUpShimeji2", "PullUp",
        "SplitIntoTwo", "Divide1", "Divided"
    )

    val keptActions = actions.filter { (name, def) ->
        !isIe(name) &&
            (includeBreed || (name !in breedNames && def.embeddedClass?.contains("Breed") != true))
    }

    val keptBehaviors = behaviors.filterKeys { name ->
        !isIe(name) && name !in cursorOnly && (includeBreed || name !in breedNames)
    }

    return ShimejiSpec(keptActions, keptBehaviors)
}

/**
 * Bridge to the current animator until the new runtime (Phase 3+) lands.
 * Flattens each action to a single [ActionAnim] by choosing one clip, which avoids
 * the old parser's "merge every Animation block together" bug (e.g. ClimbWall and
 * the 7 Pinched poses). Prefers the unconditional clip, else the first one.
 */
fun ShimejiSpec.toLegacyActionAnims(): Map<String, ActionAnim> =
    actions.mapNotNull { (name, def) ->
        val clip = def.clips.firstOrNull { it.condition == null } ?: def.clips.firstOrNull()
        val poses = clip?.poses.orEmpty()
        if (poses.isEmpty()) null else name to ActionAnim(name, poses)
    }.toMap()
