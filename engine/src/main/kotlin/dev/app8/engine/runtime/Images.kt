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
 * assets), then [url] is fetched over the network (iOS `CImageView.applyImageContent`).
 */
internal data class AssetRef(val id: String?, val name: String?, val url: String?)

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
        val url = ref.url ?: ref.name?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
        val (fetcher, _) = loader.components.newFetcher(url.toUri(), options, loader) ?: return null
        return fetcher.fetch()
    }

    class Factory(private val dataSource: App8DataSource) : Fetcher.Factory<AssetRef> {
        override fun create(data: AssetRef, options: Options, imageLoader: ImageLoader): Fetcher =
            AssetFetcher(data, options, imageLoader, dataSource)
    }
}

private object AssetKeyer : Keyer<AssetRef> {
    override fun key(data: AssetRef, options: Options): String = "a8:${data.id}:${data.name}:${data.url}"
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
