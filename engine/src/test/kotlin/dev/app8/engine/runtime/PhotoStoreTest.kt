package dev.app8.engine.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Photos (app8-dsl docs/photos.md): references, options, geometry, store, cleanup, reset.
 * Decoding and the JPEG encode use Android graphics: they run on the emulator (PhotoLog E2E).
 */
class PhotoStoreTest {
    private val id = "3f2b9c1e-6a4d-4e2f-9b1a-7c8d9e0f1a2b"
    private val root: File = Files.createTempDirectory("app8-photos").toFile()

    @Test
    fun references() {
        assertEquals(id, PhotoStore.photoId("photo://$id"))
        for (v in listOf(null, 1, "", "photo://${id.uppercase()}", "photo://$id.jpg", "https://x/$id", "photo://abc")) assertNull(PhotoStore.photoId(v))
    }

    @Test
    fun `options default and clamp`() {
        assertEquals(PhotoProcessing.Options(1600, 0.8, null), PhotoProcessing.Options.of(null, null, null))
        assertEquals(PhotoProcessing.Options(256, 1.0, null), PhotoProcessing.Options.of(10L, 3.0, 0L))
        assertEquals(PhotoProcessing.Options(4096, 0.1, 0.75), PhotoProcessing.Options.of("9000", "0.01", "0.75"))
    }

    @Test
    fun geometry() {
        assertEquals(PhotoProcessing.Geometry(504, 0, 3024, 3024, 1600, 1600), PhotoProcessing.geometry(4032, 3024, PhotoProcessing.Options(aspectRatio = 1.0)))
        assertEquals(PhotoProcessing.Geometry(875, 0, 2250, 3000, 1200, 1600), PhotoProcessing.geometry(4000, 3000, PhotoProcessing.Options(aspectRatio = 0.75)))
        assertEquals(PhotoProcessing.Geometry(0, 500, 1000, 1000, 1000, 1000), PhotoProcessing.geometry(1000, 2000, PhotoProcessing.Options(aspectRatio = 1.0)))
        assertEquals(PhotoProcessing.Geometry(0, 0, 800, 600, 800, 600), PhotoProcessing.geometry(800, 600, PhotoProcessing.Options()))
    }

    @Test
    fun `save, sweep, delete and the account reset`() {
        val app = "photo-test"
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val kept = PhotoStore.save(root, app, jpeg)
        val orphan = PhotoStore.save(root, app, jpeg)
        val recent = PhotoStore.save(root, app, jpeg)
        val other = PhotoStore.save(root, "other-app", jpeg)
        val dir = PhotoStore.directory(root, app)
        val old = System.currentTimeMillis() - 2 * PhotoStore.SWEEP_MIN_AGE_MS
        File(dir, "$kept.jpg").setLastModified(old)
        File(dir, "$orphan.jpg").setLastModified(old)

        val removed = PhotoStore.sweep(root, app, listOf("""[{"photo":"photo://$kept"}]"""))
        assertEquals(listOf(orphan), removed)
        assertTrue(File(dir, "$kept.jpg").exists())
        assertTrue(File(dir, "$recent.jpg").exists())

        val before = PhotoStore.generation.value
        PhotoStore.delete(root, app, recent)
        assertFalse(File(dir, "$recent.jpg").exists())
        assertTrue(PhotoStore.generation.value > before)

        // Account switch: clearPersistedData removes every app's photos.
        PhotoStore.clearAll(root)
        assertFalse(dir.exists())
        assertFalse(File(PhotoStore.directory(root, "other-app"), "$other.jpg").exists())
    }
}
