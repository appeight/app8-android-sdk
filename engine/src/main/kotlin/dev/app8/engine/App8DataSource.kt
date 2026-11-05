package dev.app8.engine

import dev.app8.engine.core.vars.DatasourceException
import kotlinx.coroutines.flow.Flow

/**
 * Supplies the DSL documents and assets of an app (iOS `App8DataSource`). Every method is
 * called off the main thread; implementations may do IO directly.
 */
public interface App8DataSource {
    /** `app.json`. */
    public suspend fun getApp(): ByteArray

    /** Each stylesheet (a JSON array of styles). */
    public suspend fun getStyles(): List<ByteArray>

    /** Each component template (`components/<id>.json`). */
    public suspend fun getComponents(): List<ByteArray>

    public suspend fun getScreen(screenId: String): ByteArray

    public suspend fun getComponent(componentId: String): ByteArray

    /** Binary asset (image, font, video) by id or name; null when unknown. */
    public suspend fun getAsset(assetId: String?, assetName: String?): ByteArray?

    /** A datasource file referenced by a screen variable's `source` (e.g. `datasources/listings`). */
    public suspend fun getDatasource(screenId: String, datasourceId: String): ByteArray =
        throw DatasourceException.NotFound(screenId, datasourceId)

    /** Every screen id, when the source can enumerate them (diagnostics); null otherwise. */
    public suspend fun getAllScreenIds(): List<String>? = null

    /** Structural screen updates over time; null opts out. */
    public fun streamScreen(screenId: String): Flow<ByteArray>? = null

    /** Variable-only datasource updates; null opts out. */
    public fun streamDatasource(screenId: String, datasourceId: String, componentPath: String?): Flow<ByteArray>? = null

    /** Style primitive updates (JSON array of style entities); null opts out. */
    public fun streamStyles(): Flow<ByteArray>? = null

    /** JSON `{ defaultLocale, locales: { <locale>: { key: value } } }`. */
    public suspend fun getTranslations(): ByteArray = """{"defaultLocale":"en","locales":{}}""".encodeToByteArray()
}
