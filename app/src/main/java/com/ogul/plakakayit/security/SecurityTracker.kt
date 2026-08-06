package com.ogul.plakakayit.security

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.google.mlkit.vision.face.Face
import com.ogul.plakakayit.data.NormalizedBox
import com.ogul.plakakayit.data.PersonObservation
import com.ogul.plakakayit.data.SecurityEventDraft
import com.ogul.plakakayit.data.SecurityEventType
import com.ogul.plakakayit.data.SecurityFrameResult
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class SecurityTracker {
    private val tracks = mutableMapOf<Int, TrackState>()
    private var nextTrackId = 1

    fun update(
        bitmap: Bitmap,
        scene: SecurityScene,
        faces: List<Face>,
        now: Long,
        restrictedZoneEnabled: Boolean,
        dwellThresholdSeconds: Int
    ): SecurityFrameResult {
        removeExpiredTracks(now)

        val availableTracks = tracks.values.toMutableList()
        val observations = mutableListOf<PersonObservation>()
        val events = mutableListOf<SecurityEventDraft>()

        scene.persons.sortedByDescending { it.score }.forEach { person ->
            val track = matchTrack(person.box, availableTracks) ?: TrackState(
                id = nextTrackId++,
                box = RectF(person.box),
                firstSeenAt = now,
                lastSeenAt = now,
                lastCenterX = person.box.centerX(),
                lastCenterY = person.box.centerY()
            ).also { tracks[it.id] = it }

            availableTracks.remove(track)
            val previousCenterX = track.lastCenterX
            val previousCenterY = track.lastCenterY
            val elapsedSeconds = ((now - track.lastSeenAt).coerceAtLeast(1L) / 1000f)
            val currentCenterX = person.box.centerX()
            val currentCenterY = person.box.centerY()
            val diagonal = hypot(bitmap.width.toFloat(), bitmap.height.toFloat()).coerceAtLeast(1f)
            val normalizedSpeed = hypot(
                currentCenterX - previousCenterX,
                currentCenterY - previousCenterY
            ) / diagonal / elapsedSeconds

            val direction = directionFor(
                currentCenterX - previousCenterX,
                currentCenterY - previousCenterY,
                bitmap.width,
                bitmap.height
            )
            val movement = when {
                normalizedSpeed >= FAST_SPEED_THRESHOLD -> "Hızlı hareket"
                normalizedSpeed >= MOVE_SPEED_THRESHOLD -> "Hareket ediyor"
                else -> "Bekliyor"
            }
            val dwellSeconds = ((now - track.firstSeenAt) / 1000L).toInt().coerceAtLeast(0)
            val upperColor = sampleBodyColor(bitmap, person.box, 0.18f, 0.52f)
            val lowerColor = sampleBodyColor(bitmap, person.box, 0.55f, 0.90f)
            val accessory = accessoryFor(person.box, scene.accessories)
            val face = bestFaceFor(person.box, faces)
            val faceInfo = faceInfo(person.box, face, bitmap)
            val normalizedBox = normalize(person.box, bitmap.width, bitmap.height)
            val inZone = restrictedZoneEnabled && restrictedZoneContains(normalizedBox)

            if (restrictedZoneEnabled && inZone && !track.wasInRestrictedZone &&
                canEmit(track, SecurityEventType.RESTRICTED_ZONE, now, ZONE_EVENT_COOLDOWN_MS)
            ) {
                events += eventDraft(
                    track = track,
                    type = SecurityEventType.RESTRICTED_ZONE,
                    now = now,
                    confidence = person.score,
                    upperColor = upperColor,
                    lowerColor = lowerColor,
                    accessory = accessory,
                    movement = movement,
                    direction = direction,
                    dwellSeconds = dwellSeconds,
                    faceInfo = faceInfo,
                    summaryPrefix = "Kişi-${track.id} kısıtlı bölgeye girdi"
                )
            }

            if (normalizedSpeed >= FAST_SPEED_THRESHOLD &&
                canEmit(track, SecurityEventType.FAST_MOVEMENT, now, FAST_EVENT_COOLDOWN_MS)
            ) {
                events += eventDraft(
                    track = track,
                    type = SecurityEventType.FAST_MOVEMENT,
                    now = now,
                    confidence = person.score,
                    upperColor = upperColor,
                    lowerColor = lowerColor,
                    accessory = accessory,
                    movement = movement,
                    direction = direction,
                    dwellSeconds = dwellSeconds,
                    faceInfo = faceInfo,
                    summaryPrefix = "Kişi-${track.id} hızlı hareket ediyor"
                )
            }

            if (dwellSeconds >= dwellThresholdSeconds &&
                normalizedSpeed < MOVE_SPEED_THRESHOLD &&
                canEmit(track, SecurityEventType.LONG_DWELL, now, DWELL_EVENT_COOLDOWN_MS)
            ) {
                events += eventDraft(
                    track = track,
                    type = SecurityEventType.LONG_DWELL,
                    now = now,
                    confidence = person.score,
                    upperColor = upperColor,
                    lowerColor = lowerColor,
                    accessory = accessory,
                    movement = movement,
                    direction = direction,
                    dwellSeconds = dwellSeconds,
                    faceInfo = faceInfo,
                    summaryPrefix = "Kişi-${track.id} ${dwellSeconds} saniyedir bölgede"
                )
            }

            track.box = RectF(person.box)
            track.lastCenterX = currentCenterX
            track.lastCenterY = currentCenterY
            track.lastSeenAt = now
            track.wasInRestrictedZone = inZone

            observations += PersonObservation(
                trackId = track.id,
                box = normalizedBox,
                upperColor = upperColor,
                lowerColor = lowerColor,
                accessory = accessory,
                movement = movement,
                direction = direction,
                dwellSeconds = dwellSeconds,
                faceVisibility = faceInfo.visibility,
                faceQuality = faceInfo.quality,
                confidence = person.score,
                inRestrictedZone = inZone
            )
        }

        return SecurityFrameResult(
            persons = observations.sortedBy { it.trackId },
            events = events
        )
    }

    private fun matchTrack(box: RectF, candidates: List<TrackState>): TrackState? {
        return candidates
            .map { it to intersectionOverUnion(box, it.box) }
            .filter { it.second >= TRACK_IOU_THRESHOLD }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun canEmit(
        track: TrackState,
        type: SecurityEventType,
        now: Long,
        cooldownMs: Long
    ): Boolean {
        val previous = track.lastEventAt[type] ?: 0L
        if (now - previous < cooldownMs) return false
        track.lastEventAt[type] = now
        return true
    }

    private fun eventDraft(
        track: TrackState,
        type: SecurityEventType,
        now: Long,
        confidence: Float,
        upperColor: String,
        lowerColor: String,
        accessory: String,
        movement: String,
        direction: String,
        dwellSeconds: Int,
        faceInfo: FaceInfo,
        summaryPrefix: String
    ): SecurityEventDraft {
        val details = buildList {
            if (upperColor.isNotBlank()) add("üst $upperColor")
            if (lowerColor.isNotBlank()) add("alt $lowerColor")
            if (accessory.isNotBlank()) add(accessory.lowercase())
            if (direction != "Sabit") add(direction.lowercase())
        }.joinToString(", ")
        val summary = if (details.isBlank()) "$summaryPrefix." else "$summaryPrefix: $details."
        return SecurityEventDraft(
            trackId = track.id,
            type = type,
            summary = summary,
            confidence = confidence,
            upperColor = upperColor,
            lowerColor = lowerColor,
            accessory = accessory,
            movement = movement,
            direction = direction,
            dwellSeconds = dwellSeconds,
            faceVisibility = faceInfo.visibility,
            faceQuality = faceInfo.quality,
            occurredAt = now
        )
    }

    private fun bestFaceFor(personBox: RectF, faces: List<Face>): Face? {
        return faces
            .filter { face ->
                val box = RectF(face.boundingBox)
                personBox.contains(box.centerX(), box.centerY())
            }
            .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
    }

    private fun faceInfo(personBox: RectF, face: Face?, bitmap: Bitmap): FaceInfo {
        if (face == null) return FaceInfo("Görünmüyor", 0)
        val faceBox = RectF(face.boundingBox)
        val personArea = max(1f, personBox.width() * personBox.height())
        val faceArea = max(1f, faceBox.width() * faceBox.height())
        val relativeSize = (faceArea / personArea / 0.10f).coerceIn(0f, 1f)
        val absoluteSize = (faceArea / max(1f, bitmap.width * bitmap.height.toFloat()) / 0.025f)
            .coerceIn(0f, 1f)
        val anglePenalty = (
            1f - (abs(face.headEulerAngleY) / 55f).coerceIn(0f, 1f) * 0.55f -
                (abs(face.headEulerAngleZ) / 45f).coerceIn(0f, 1f) * 0.25f
            ).coerceIn(0f, 1f)
        val quality = ((relativeSize * 0.45f + absoluteSize * 0.25f + anglePenalty * 0.30f) * 100)
            .toInt()
            .coerceIn(0, 100)
        val visibility = when {
            quality >= 68 -> "Görünüyor"
            quality >= 35 -> "Kısmen görünür"
            else -> "Zayıf görünürlük"
        }
        return FaceInfo(visibility, quality)
    }

    private fun accessoryFor(personBox: RectF, accessories: List<DetectedObject>): String {
        val expanded = RectF(personBox).apply {
            inset(-personBox.width() * 0.12f, -personBox.height() * 0.08f)
        }
        return accessories
            .filter { item ->
                expanded.contains(item.box.centerX(), item.box.centerY()) ||
                    RectF.intersects(expanded, item.box)
            }
            .sortedByDescending { it.score }
            .mapNotNull { item ->
                when (item.label) {
                    "backpack" -> "Sırt çantası"
                    "handbag" -> "El çantası"
                    "suitcase" -> "Valiz"
                    else -> null
                }
            }
            .distinct()
            .joinToString(" + ")
    }

    private fun directionFor(dx: Float, dy: Float, width: Int, height: Int): String {
        val nx = dx / width.coerceAtLeast(1)
        val ny = dy / height.coerceAtLeast(1)
        if (hypot(nx, ny) < DIRECTION_THRESHOLD) return "Sabit"
        return if (abs(nx) >= abs(ny)) {
            if (nx > 0) "Sağa" else "Sola"
        } else {
            if (ny > 0) "Aşağı" else "Yukarı"
        }
    }

    private fun sampleBodyColor(
        bitmap: Bitmap,
        box: RectF,
        startFraction: Float,
        endFraction: Float
    ): String {
        val left = (box.left + box.width() * 0.20f).toInt().coerceIn(0, bitmap.width - 1)
        val right = (box.right - box.width() * 0.20f).toInt().coerceIn(left + 1, bitmap.width)
        val top = (box.top + box.height() * startFraction).toInt().coerceIn(0, bitmap.height - 1)
        val bottom = (box.top + box.height() * endFraction).toInt().coerceIn(top + 1, bitmap.height)
        val stepX = ((right - left) / 18).coerceAtLeast(1)
        val stepY = ((bottom - top) / 18).coerceAtLeast(1)

        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val maxChannel = max(r, max(g, b))
                val minChannel = min(r, min(g, b))
                if (maxChannel - minChannel > 5 || maxChannel < 245) {
                    red += r
                    green += g
                    blue += b
                    count++
                }
                x += stepX
            }
            y += stepY
        }
        if (count == 0L) return ""
        return colorName((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun colorName(r: Int, g: Int, b: Int): String {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]
        return when {
            value < 0.18f -> "Siyah"
            saturation < 0.10f && value > 0.82f -> "Beyaz"
            saturation < 0.18f -> "Gri"
            hue < 15f || hue >= 345f -> "Kırmızı"
            hue < 35f && value < 0.55f -> "Kahverengi"
            hue < 50f -> "Turuncu"
            hue < 70f -> "Sarı"
            hue < 165f -> "Yeşil"
            hue < 255f -> "Mavi"
            hue < 295f -> "Mor"
            else -> "Kırmızı"
        }
    }

    private fun normalize(box: RectF, width: Int, height: Int): NormalizedBox = NormalizedBox(
        left = (box.left / width).coerceIn(0f, 1f),
        top = (box.top / height).coerceIn(0f, 1f),
        right = (box.right / width).coerceIn(0f, 1f),
        bottom = (box.bottom / height).coerceIn(0f, 1f)
    )

    private fun restrictedZoneContains(box: NormalizedBox): Boolean {
        val centerX = (box.left + box.right) / 2f
        val centerY = (box.top + box.bottom) / 2f
        return centerX in ZONE_LEFT..ZONE_RIGHT && centerY in ZONE_TOP..ZONE_BOTTOM
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun removeExpiredTracks(now: Long) {
        tracks.entries.removeIf { now - it.value.lastSeenAt > TRACK_EXPIRY_MS }
    }

    private data class FaceInfo(val visibility: String, val quality: Int)

    private data class TrackState(
        val id: Int,
        var box: RectF,
        val firstSeenAt: Long,
        var lastSeenAt: Long,
        var lastCenterX: Float,
        var lastCenterY: Float,
        var wasInRestrictedZone: Boolean = false,
        val lastEventAt: MutableMap<SecurityEventType, Long> = mutableMapOf()
    )

    companion object {
        const val ZONE_LEFT = 0.20f
        const val ZONE_TOP = 0.18f
        const val ZONE_RIGHT = 0.80f
        const val ZONE_BOTTOM = 0.78f

        private const val TRACK_IOU_THRESHOLD = 0.18f
        private const val TRACK_EXPIRY_MS = 3_500L
        private const val MOVE_SPEED_THRESHOLD = 0.035f
        private const val FAST_SPEED_THRESHOLD = 0.18f
        private const val DIRECTION_THRESHOLD = 0.012f
        private const val ZONE_EVENT_COOLDOWN_MS = 30_000L
        private const val FAST_EVENT_COOLDOWN_MS = 20_000L
        private const val DWELL_EVENT_COOLDOWN_MS = 120_000L
    }
}
