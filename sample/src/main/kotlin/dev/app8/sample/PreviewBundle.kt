package dev.app8.sample

import dev.app8.engine.App8DataSource
import dev.app8.engine.core.vars.DatasourceException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * The render-job envelope shared with the iOS preview host
 * (app8-ios `app8-preview-host/PreviewBundle.swift`): `{ screenId, params?, size?, bundle:
 * { app, styles[], components[], screens{}, datasources{screenId:{id:…}}, assetsRoot? },
 * target?, elementId?, mode?, maxPages?, kind? }`.
 */
class PreviewEnvelope(val root: JsonObject) {
    val screenId: String = (root["screenId"] as JsonPrimitive).content
    val kind: String? = (root["kind"] as? JsonPrimitive)?.contentOrNull
    val bundle: JsonObject = root["bundle"] as JsonObject
    val params: Map<String, Any?> = (root["params"] as? JsonObject)?.mapValues { (_, v) -> dev.app8.engine.core.vars.Values.fromJson(v) }.orEmpty()
    val maxPages: Int? = (root["maxPages"] as? JsonPrimitive)?.intOrNull

    companion object {
        fun parse(text: String) = PreviewEnvelope(Json.parseToJsonElement(text) as JsonObject)
    }
}

/** iOS `InMemoryPreviewDataSource`: one blob per data-source method; assets read from `assetsRoot`. */
class InMemoryPreviewDataSource(envelope: PreviewEnvelope) : App8DataSource {
    private val bundle = envelope.bundle
    private fun bytes(e: JsonElement) = e.toString().encodeToByteArray()

    override suspend fun getApp(): ByteArray = bytes(bundle["app"] ?: JsonObject(emptyMap()))
    override suspend fun getStyles(): List<ByteArray> = (bundle["styles"] as? JsonArray).orEmpty().map(::bytes)
    override suspend fun getComponents(): List<ByteArray> = (bundle["components"] as? JsonArray).orEmpty().map(::bytes)
    override suspend fun getScreen(screenId: String): ByteArray =
        (bundle["screens"] as? JsonObject)?.get(screenId)?.let(::bytes) ?: error("screen '$screenId' not found in bundle")

    override suspend fun getComponent(componentId: String): ByteArray = error("preview bundles list templates via getComponents()")

    override suspend fun getDatasource(screenId: String, datasourceId: String): ByteArray {
        val all = bundle["datasources"] as? JsonObject
        val byScreen = all?.get(screenId) as? JsonObject
        // build-bundle.sh keys datasources under the target screen; other screens share them.
        val hit = byScreen?.get(datasourceId) ?: all?.values?.firstNotNullOfOrNull { (it as? JsonObject)?.get(datasourceId) }
        return hit?.let(::bytes) ?: throw DatasourceException.NotFound(screenId, datasourceId)
    }

    override suspend fun getAsset(assetId: String?, assetName: String?): ByteArray? {
        val root = (bundle["assetsRoot"] as? JsonPrimitive)?.contentOrNull?.let(::File) ?: return null
        val names = listOfNotNull(assetName, assetName?.substringAfterLast('/'), assetId)
        for (name in names) for (ext in listOf("", ".png", ".jpg", ".jpeg", ".webp", ".gif")) {
            val f = File(root, name + ext)
            if (f.isFile) return f.readBytes()
        }
        return null
    }

    override suspend fun getAllScreenIds(): List<String> = (bundle["screens"] as? JsonObject)?.keys?.toList().orEmpty()
}
