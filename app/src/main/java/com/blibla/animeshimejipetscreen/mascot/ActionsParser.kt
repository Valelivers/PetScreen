package com.blibla.animeshimejipetscreen.mascot

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File

data class Pose(
    val image: String,          // e.g. "shime1.png"
    val anchorX: Int,
    val anchorY: Int,
    val vx: Float,
    val vy: Float,
    val durationTicks: Int
)

data class ActionAnim(
    val name: String,
    val poses: List<Pose>
)

object ActionsParser {

    fun parsePrimitiveActions(xmlFile: File): Map<String, ActionAnim> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xmlFile.inputStream(), "UTF-8")

        val actions = mutableMapOf<String, ActionAnim>()

        var currentActionName: String? = null
        var inAnimation = false
        val poses = mutableListOf<Pose>()

        fun flush() {
            val name = currentActionName ?: return
            if (poses.isNotEmpty()) {
                actions[name] = ActionAnim(name, poses.toList())
            }
            poses.clear()
        }

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "Action" -> {
                            // Start a new action. We only keep it if it has Pose(s)
                            flush()
                            currentActionName = parser.getAttributeValue(null, "Name")
                        }
                        "Animation" -> inAnimation = true
                        "Pose" -> if (inAnimation && currentActionName != null) {
                            val imgRaw = parser.getAttributeValue(null, "Image") ?: ""
                            val img = imgRaw.trim().removePrefix("/") // "/shime1.png" -> "shime1.png"

                            val anchor = parser.getAttributeValue(null, "ImageAnchor") ?: "0,0"
                            val vel = parser.getAttributeValue(null, "Velocity") ?: "0,0"
                            val dur = parser.getAttributeValue(null, "Duration") ?: "1"

                            // tolerant parsing: accept decimals / spaces / missing parts
                            // instead of crashing on toInt() (e.g. ImageAnchor="64,104.0")
                            val anchorParts = anchor.split(",")
                            val ax = anchorParts.getOrNull(0)?.trim()?.toFloatOrNull()?.toInt() ?: 0
                            val ay = anchorParts.getOrNull(1)?.trim()?.toFloatOrNull()?.toInt() ?: 0

                            val velParts = vel.split(",")
                            val vx = velParts.getOrNull(0)?.trim()?.toFloatOrNull() ?: 0f
                            val vy = velParts.getOrNull(1)?.trim()?.toFloatOrNull() ?: 0f

                            poses += Pose(
                                image = img,
                                anchorX = ax,
                                anchorY = ay,
                                vx = vx,
                                vy = vy,
                                durationTicks = (dur.trim().toFloatOrNull()?.toInt() ?: 1).coerceAtLeast(1)
                            )
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "Animation" -> inAnimation = false
                        "Action" -> flush()
                    }
                }
            }
            parser.next()
        }

        return actions
    }
}
