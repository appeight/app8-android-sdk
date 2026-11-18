package dev.app8.engine.runtime

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.toUri
import dev.app8.engine.App8DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer

/**
 * An image the DSL names: the data source is asked first (it owns bundled and prefetched
 * assets, the app's asset map), then [url] is fetched over the network (iOS
 * `CImageView.applyImageContent`). A [local] asset name not in the data source falls back
 * to a drawable compiled into the host app (image.md, Image source).
 */
internal data class AssetRef(val id: String?, val name: String?, val url: String?, val local: Boolean = false)

private class AssetFetcher(
    private val ref: AssetRef,
    private val options: Options,
    private val loader: ImageLoader,
    private val dataSource: App8DataSource,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val bytes = runCatching {
            withContext(Dispatchers.IO) { dataSource.getAsset(ref.id, ref.name) }
        }.getOrNull()
        if (bytes != null) {
            return SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem), null, DataSource.DISK)
        }
        if (ref.local && ref.url == null) {
            val res = ref.name?.let { hostDrawable(options.context, it) } ?: 0
            if (res != 0) {
                val uri = "android.resource://${options.context.packageName}/$res".toUri()
                val (fetcher, _) = loader.components.newFetcher(uri, options, loader) ?: return null
                return fetcher.fetch()
            }
        }
        val url = ref.url ?: ref.name?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            // Say what is missing instead of Coil's "Unable to create a fetcher".
            ?: throw IllegalStateException("asset '${ref.name ?: ref.id}' not found in the data source and no url")
        val (fetcher, _) = loader.components.newFetcher(url.toUri(), options, loader) ?: return null
        return fetcher.fetch()
    }

    class Factory(private val dataSource: App8DataSource) : Fetcher.Factory<AssetRef> {
        override fun create(data: AssetRef, options: Options, imageLoader: ImageLoader): Fetcher =
            AssetFetcher(data, options, imageLoader, dataSource)
    }
}

/**
 * The host app's drawable for an asset name ("logo-black.png" -> R.drawable.logo_black),
 * or 0. Resource names are lowercase with underscores and no extension.
 */
@android.annotation.SuppressLint("DiscouragedApi")
internal fun hostDrawable(context: android.content.Context, name: String): Int {
    val resName = drawableName(name) ?: return 0
    return context.resources.getIdentifier(resName, "drawable", context.packageName)
}

/** A drawable resource name for an asset name, or null when nothing is left. */
internal fun drawableName(name: String): String? =
    name.substringAfterLast('/').substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9_]"), "_").takeIf { it.isNotEmpty() }

private object AssetKeyer : Keyer<AssetRef> {
    override fun key(data: AssetRef, options: Options): String = "a8:${data.id}:${data.name}:${data.url}:${data.local}"
}

/** The instance's image loader: data-source assets, network, and a pending-request count for idle detection. */
internal fun buildImageLoader(runtime: App8Runtime): ImageLoader =
    ImageLoader.Builder(runtime.appContext)
        .components {
            add(AssetFetcher.Factory(runtime.dataSource))
            add(AssetKeyer)
        }
        .eventListener(object : coil3.EventListener() {
            override fun onStart(request: ImageRequest) = runtime.beginWork()
            override fun onSuccess(request: ImageRequest, result: coil3.request.SuccessResult) = runtime.endWork()
            override fun onError(request: ImageRequest, result: coil3.request.ErrorResult) {
                runtime.diagnostics.warn("image", "failed to load ${request.data}: ${result.throwable.message}")
                runtime.endWork()
            }
            override fun onCancel(request: ImageRequest) = runtime.endWork()
        })
        .build()
