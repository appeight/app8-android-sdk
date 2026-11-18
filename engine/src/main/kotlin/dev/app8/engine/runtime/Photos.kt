package dev.app8.engine.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Photos (app8-dsl `docs/photos.md`): JPEG files `capturePhoto` stored, named by the id in their
 * `photo://<id>` reference. Storage: `filesDir/app8/<appId>/photos/<id>.jpg` (app-private).
 */
internal object PhotoStore {
    const val SCHEME: String = "photo://"

    /** Photos younger than this survive the launch cleanup even when nothing mentions them. */
    const val SWEEP_MIN_AGE_MS: Long = 60L * 60 * 1000

    private val ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** Bumped on every delete, so images showing a deleted photo reload (and draw nothing). */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation

    /** The id of a `photo://` reference, or null. */
    fun photoId(value: Any?): String? {
        val s = value as? String ?: return null
        if (!s.startsWith(SCHEME)) return null
        return s.removePrefix(SCHEME).takeIf { ID.matches(it) }
    }

    fun reference(id: String): String = SCHEME + id

    fun root(context: Context): File = File(context.applicationContext.filesDir, "app8")

    fun directory(context: Context, appId: String): File = directory(root(context), appId)

    fun directory(root: File, appId: String): File = File(File(root, sanitized(appId)), "photos")

    fun file(context: Context, appId: String, id: String): File = File(directory(context, appId), "$id.jpg")

    private fun sanitized(appId: String): String =
        appId.map { if (it.isLetterOrDigit() || it in "-_.") it else '_' }.joinToString("")
            .takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "default"

    fun save(context: Context, appId: String, jpeg: ByteArray): String = save(root(context), appId, jpeg)

    /** Writes a JPEG under [root] and returns its new id. */
    fun save(root: File, appId: String, jpeg: ByteArray): String {
        val dir = directory(root, appId).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val tmp = File(dir, "$id.tmp")
        tmp.writeBytes(jpeg)
        check(tmp.renameTo(File(dir, "$id.jpg"))) { "could not store the photo" }
        return id
    }

    fun delete(context: Context, appId: String, id: String) = delete(root(context), appId, id)

    fun delete(root: File, appId: String, id: String) {
        File(directory(root, appId), "$id.jpg").delete()
        _generation.value++
    }

    fun sweep(context: Context, appId: String, persistedJson: Collection<String>): List<String> =
        sweep(root(context), appId, persistedJson)

    /**
     * The launch cleanup: removes photos older than an hour whose id appears in none of
     * [persistedJson] (the app's stored persisted values). Returns the removed ids.
     */
    fun sweep(root: File, appId: String, persistedJson: Collection<String>, now: Long = System.currentTimeMillis()): List<String> {
        val files = directory(root, appId).listFiles { f: File -> f.name.endsWith(".jpg") } ?: return emptyList()
        val text = persistedJson.joinToString("\n")
        val removed = files.filter { now - it.lastModified() >= SWEEP_MIN_AGE_MS && !text.contains(it.nameWithoutExtension) }
        removed.forEach { it.delete() }
        if (removed.isNotEmpty()) _generation.value++
        return removed.map { it.nameWithoutExtension }
    }

    /** `App8.clearPersistedData`: every app's photos. */
    fun clearAll(context: Context) = clearAll(root(context))

    fun clearAll(root: File) {
        root.listFiles()?.forEach { File(it, "photos").deleteRecursively() }
        _generation.value++
    }
}

/** Downscale, center-crop and JPEG-encode a captured photo (docs/photos.md). */
internal object PhotoProcessing {
    data class Options(val maxDimension: Int = 1600, val quality: Double = 0.8, val aspectRatio: Double? = null) {
        companion object {
            /** `maxDimension` clamped to 256…4096, `quality` to 0.1…1; numbers given as strings accepted. */
            fun of(maxDimension: Any?, quality: Any?, aspectRatio: Any?): Options {
                fun num(v: Any?): Double? = when (v) {
                    is Number -> v.toDouble().takeIf { it.isFinite() }
                    is String -> v.trim().toDoubleOrNull()
                    else -> null
                }
                return Options(
                    maxDimension = num(maxDimension)?.let { min(4096, max(256, it.roundToInt())) } ?: 1600,
                    quality = num(quality)?.let { min(1.0, max(0.1, it)) } ?: 0.8,
                    aspectRatio = num(aspectRatio)?.takeIf { it > 0 },
                )
            }
        }
    }

    data class Geometry(val cropX: Int, val cropY: Int, val cropW: Int, val cropH: Int, val width: Int, val height: Int)

    /** The crop (in upright pixels) and the output size for a w×h image. */
    fun geometry(w: Int, h: Int, o: Options): Geometry {
        var sw = w.toDouble()
        var sh = h.toDouble()
        o.aspectRatio?.let { r -> if (w.toDouble() / h > r) sw = Math.round(h * r).toDouble() else sh = Math.round(w / r).toDouble() }
        val scale = min(1.0, o.maxDimension / max(sw, sh))
        return Geometry(
            cropX = Math.round((w - sw) / 2).toInt(), cropY = Math.round((h - sh) / 2).toInt(),
            cropW = sw.toInt(), cropH = sh.toInt(),
            width = max(1, Math.round(sw * scale).toInt()), height = max(1, Math.round(sh * scale).toInt()),
        )
    }

    /** Decodes [uri] upright (ImageDecoder applies the EXIF orientation), crops, scales and encodes. */
    fun encode(context: Context, uri: Uri, o: Options): Encoded {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val g = geometry(info.size.width, info.size.height, o)
            // Decode at most twice the output size, never larger than the image.
            val sample = max(1.0, min(g.cropW.toDouble() / (g.width * 2), g.cropH.toDouble() / (g.height * 2)))
            if (sample > 1.0) decoder.setTargetSize((info.size.width / sample).roundToInt(), (info.size.height / sample).roundToInt())
        }
        return encode(bitmap, o)
    }

    /** Crops and scales an upright bitmap, then encodes it (JPEG carries no metadata from a Bitmap). */
    fun encode(bitmap: Bitmap, o: Options): Encoded {
        val g = geometry(bitmap.width, bitmap.height, o)
        val cropped = Bitmap.createBitmap(bitmap, g.cropX, g.cropY, g.cropW, g.cropH)
        val scaled = if (g.width == g.cropW && g.height == g.cropH) cropped else Bitmap.createScaledBitmap(cropped, g.width, g.height, true)
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, (o.quality * 100).roundToInt(), out)
        return Encoded(out.toByteArray(), g.width, g.height)
    }

    class Encoded(val jpeg: ByteArray, val width: Int, val height: Int)
}
