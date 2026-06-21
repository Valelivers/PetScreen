package com.blibla.animeshimejipetscreen.mascot

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import kotlin.random.Random

/**
 * Phase 4 of the Shimeji interpreter: behavior selection.
 *
 * Parses a `behaviors.xml` frequency table and picks the next behavior to run
 * based on the mascot's current contact state (floor / wall / ceiling / air) and
 * the relative [BehaviorEntry.frequency] weights.
 *
 * The stock Group-Finity file groups behaviors inside `<Condition>` elements that
 * use environment predicates (floor.isOn, ...). Those predicates can't be
 * evaluated cheaply on Android, so instead the contact state is computed
 * geometrically here and each behavior's *starting* border is inferred from its
 * first action. Hidden behaviors (Fall/Dragged/Thrown) are event-driven and never
 * chosen at random.
 *
 * Not yet wired into the animator (Phase 5).
 */

data class BehaviorEntry(
    val name: String,
    val frequency: Int,
    val hidden: Boolean
)

object BehaviorsParser {

    fun parse(file: File): List<BehaviorEntry> =
        file.inputStream().use { parse(it) }

    fun parse(input: InputStream): List<BehaviorEntry> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(input, "UTF-8")

        val out = LinkedHashMap<String, BehaviorEntry>() // dedupe by name, keep last
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG &&
                (parser.name == "Behavior" || parser.name == "BehaviorReference")
            ) {
                val name = attr(parser, "Name").orEmpty()
                if (name.isNotBlank()) {
                    val freq = attr(parser, "Frequency")?.trim()?.toIntOrNull() ?: 0
                    val hidden = attr(parser, "Hidden")?.trim()?.equals("true", true) == true
                    out[name] = BehaviorEntry(name, freq, hidden)
                }
            }
            ev = parser.next()
        }
        return out.values.toList()
    }

    private fun attr(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == name) return parser.getAttributeValue(i)
        }
        return null
    }
}

enum class Contact { FLOOR, WALL, CEILING, AIR }

/**
 * Chooses the next behavior to run for a mascot.
 *
 * @param spec       parsed (and Android-curated) mascot spec
 * @param entries    behavior frequency table (from pack or [default_behaviors.xml])
 */
class BehaviorScheduler(
    private val spec: ShimejiSpec,
    private val entries: List<BehaviorEntry>
) {
    /** Tolerance (px) for deciding the mascot is touching a border. */
    private val edge = 4

    /** Pick the next behavior node for the given state, or null if none fits. */
    fun pickNext(state: MascotState, env: MascotEnv): BehaviorNode? {
        val contact = contactOf(state, env)

        // Airborne -> always fall (event-style, but needed to recover control).
        if (contact == Contact.AIR) {
            return spec.behaviors["Fall"] ?: spec.behaviors["Thrown"]
        }

        val candidates = entries.filter { e ->
            !e.hidden &&
                e.frequency > 0 &&
                spec.behaviors.containsKey(e.name) &&
                startContactOf(e.name) == contact
        }
        if (candidates.isEmpty()) return null

        val name = weightedPick(candidates) ?: return null
        return spec.behaviors[name]
    }

    /** Convenience: build a ready-to-run [BehaviorRunner] for the next behavior. */
    fun nextRunner(
        state: MascotState,
        envProvider: () -> MascotEnv,
        speed: () -> Float = { 1f },
        scale: () -> Float = { 1f }
    ): BehaviorRunner? {
        val node = pickNext(state, envProvider()) ?: return null
        return BehaviorRunner(spec, node, state, envProvider, speed, scale)
    }

    /** Build a runner for a specific named behavior (event-driven: Fall/Dragged/Thrown). */
    fun runnerFor(
        name: String,
        state: MascotState,
        envProvider: () -> MascotEnv,
        speed: () -> Float = { 1f },
        scale: () -> Float = { 1f }
    ): BehaviorRunner? {
        val node = spec.behaviors[name] ?: return null
        return BehaviorRunner(spec, node, state, envProvider, speed, scale)
    }

    // ---- helpers ----

    private fun weightedPick(pool: List<BehaviorEntry>): String? {
        if (pool.isEmpty()) return null
        val total = pool.sumOf { it.frequency }
        if (total <= 0) return pool.first().name
        var r = Random.nextInt(total)
        for (e in pool) {
            r -= e.frequency
            if (r < 0) return e.name
        }
        return pool.last().name
    }

    /** Geometric contact test (no expression predicates needed). */
    private fun contactOf(state: MascotState, env: MascotEnv): Contact {
        val onFloor = state.anchorAbsY >= env.workBottom - edge
        val onCeiling = state.anchorAbsY <= env.workTop + edge
        val onLeft = state.anchorAbsX <= env.workLeft + edge
        val onRight = state.anchorAbsX >= env.workRight - edge

        return when {
            onFloor -> Contact.FLOOR
            onCeiling -> Contact.CEILING
            onLeft || onRight -> Contact.WALL
            else -> Contact.AIR
        }
    }

    /** Infer where a behavior starts from the border of its first concrete action. */
    private fun startContactOf(behaviorName: String): Contact {
        val node = spec.behaviors[behaviorName] ?: return Contact.FLOOR
        return when (firstActionBorder(node)) {
            BorderType.WALL -> Contact.WALL
            BorderType.CEILING -> Contact.CEILING
            else -> Contact.FLOOR
        }
    }

    private fun firstActionBorder(node: BehaviorNode, depth: Int = 0): BorderType {
        if (depth > 6) return BorderType.NONE
        for (child in node.children) {
            when (child) {
                is RefChild -> {
                    spec.actions[child.ref.name]?.let { a ->
                        if (a.borderType != BorderType.NONE) return a.borderType
                    }
                    spec.behaviors[child.ref.name]?.let { nested ->
                        val b = firstActionBorder(nested, depth + 1)
                        if (b != BorderType.NONE) return b
                    }
                }
                is NodeChild -> {
                    val b = firstActionBorder(child.node, depth + 1)
                    if (b != BorderType.NONE) return b
                }
            }
        }
        return BorderType.NONE
    }
}
