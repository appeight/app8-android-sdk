package dev.app8.engine.render.components

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.WeakHashMap

/**
 * Where the video component spills a data source's bytes so ExoPlayer can open them as a file.
 *
 * Each data source instance gets its own folder under `cacheDir/app8-video/`. Asset names are
 * only unique inside one app, so a host that runs several apps (two stores that both ship a
 * "launch-reel") must not share files between them. Folders from earlier processes are removed
 * the first time the engine asks for one, as are the flat `app8-video-<name>.<ext>` files that
 * engine 0.4.1 and earlier wrote into `cacheDir` for every app.
 */
internal object VideoTempFiles {
    private val namespaces = WeakHashMap<Any, String>()
    private var purged = false

    @Synchronized
    fun directory(context: Context, dataSource: Any): File {
        val root = File(context.cacheDir, "app8-video")
        if (!purged) {
            purged = true
            root.deleteRecursively()
            context.cacheDir.listFiles { f -> f.isFile && f.name.startsWith("app8-video-") }?.forEach { it.delete() }
        }
        val namespace = namespaces.getOrPut(dataSource) { UUID.randomUUID().toString() }
        return File(root, namespace).apply { mkdirs() }
    }
}
