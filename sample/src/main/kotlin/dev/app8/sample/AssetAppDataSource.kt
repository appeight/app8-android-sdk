package dev.app8.sample

import android.content.res.AssetManager
import dev.app8.engine.App8DataSource
import dev.app8.engine.core.vars.DatasourceException

/**
 * An app bundled in the sample's assets, in the shoppu-web demo layout:
 * `app.json`, `styles.json`, `components/`, `screens/<id>.json`, `datasources/<name>.json`,
 * `media/<asset>.(jpg|png)`.
 */
class AssetAppDataSource(private val assets: AssetManager, private val root: String) : App8DataSource {
    private fun read(path: String): ByteArray = assets.open("$root/$path").use { it.readBytes() }
    private fun list(dir: String): List<String> = assets.list("$root/$dir")?.sorted().orEmpty()

    override suspend fun getApp(): ByteArray = read("app.json")
    override suspend fun getStyles(): List<ByteArray> = listOf(read("styles.json"))
    override suspend fun getComponents(): List<ByteArray> = list("components").filter { it.endsWith(".json") }.map { read("components/$it") }
    override suspend fun getScreen(screenId: String): ByteArray = read("screens/$screenId.json")
    override suspend fun getComponent(componentId: String): ByteArray = read("components/$componentId.json")

    override suspend fun getAsset(assetId: String?, assetName: String?): ByteArray? {
        val name = assetName ?: assetId ?: return null
        for (candidate in listOf(name, "$name.jpg", "$name.png", "$name.webp")) {
            runCatching { return read("media/$candidate") }
        }
        return null
    }

    override suspend fun getDatasource(screenId: String, datasourceId: String): ByteArray =
        runCatching { read("$datasourceId.json") }.getOrElse { throw DatasourceException.NotFound(screenId, datasourceId) }

    override suspend fun getAllScreenIds(): List<String> = list("screens").map { it.removeSuffix(".json") }
}
