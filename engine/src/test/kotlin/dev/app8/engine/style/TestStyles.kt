package dev.app8.engine.style

import dev.app8.engine.core.model.DslDecoder
import dev.app8.engine.core.model.style.Material
import dev.app8.engine.core.model.style.StyleEntity
import dev.app8.engine.core.model.style.TextModel

/** Decodes DSL style JSON the way styles.json is decoded, so tests exercise real DSL. */
internal object TestStyles {
    private val decoder = DslDecoder()

    fun material(layersJson: String): Material =
        (decoder.decodeStyles("""[{"id":"m","type":"material","content":$layersJson}]""").single() as StyleEntity<*>).content as Material

    fun text(json: String): TextModel =
        (decoder.decodeStyles("""[{"id":"t","type":"text","content":$json}]""").single() as StyleEntity<*>).content as TextModel
}
