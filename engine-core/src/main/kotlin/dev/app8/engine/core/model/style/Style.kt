package dev.app8.engine.core.model.style

import dev.app8.engine.core.model.Cursor
import kotlinx.serialization.json.JsonObject

/** Style discriminator (iOS `DSL.Model.Style.SType`): a known key or a custom name. */
public sealed interface StyleType {
    public data class Known(val key: Key) : StyleType
    public data class Custom(val name: String) : StyleType

    public fun isKeyed(key: Key): Boolean = this is Known && this.key == key

    @Suppress("EnumEntryName")
    public enum class Key {
        fill, outline, shadow, corner, visualEffect, material,
        color, gradient,
        text, font,
        view, image, icon, video,
        systemButton,
    }

    public companion object {
        public fun of(raw: String): StyleType =
            Key.entries.firstOrNull { it.name == raw }?.let { Known(it) } ?: Custom(raw)

        /** Style types that may appear as material layers. */
        public val MATERIAL_KEYS: Set<Key> = setOf(Key.fill, Key.outline, Key.shadow, Key.corner, Key.visualEffect, Key.material)
    }
}

/**
 * Type-erased style (iOS `DSL.Model.Style.Any`): a [StylePointer] (`{ "id": … }` without
 * `content`) to be resolved against styles.json, or a concrete [StyleEntity].
 */
public sealed interface StyleAny {
    public val id: String
    public val type: StyleType

    public val isMaterial: Boolean
        get() = (type as? StyleType.Known)?.key in StyleType.MATERIAL_KEYS
}

public data class StylePointer(
    override val id: String,
    override val type: StyleType,
    /** False when the pointer had no `type`; then [type] is `Custom("")`. */
    val typeSpecified: Boolean,
) : StyleAny

public data class StyleEntity<T : Any>(
    override val id: String,
    override val type: StyleType,
    val content: T,
) : StyleAny

/** Shared shape for `@Wrapped` style fields: either a style reference or a direct value. */
public data class Wrapped<T : Any>(
    /** A pointer or entity when the JSON had `id` (and maybe `type` / `content`). */
    val base: StyleAny? = null,
    /** The value when written inline (no `id`). */
    val direct: T? = null,
    private val valueClass: Class<T>,
) {
    /** The direct value, or the entity content when it has the expected type. `null` for pointers. */
    public val value: T?
        get() = direct ?: (base as? StyleEntity<*>)?.content?.let { if (valueClass.isInstance(it)) valueClass.cast(it) else null }

    /** Pointer ids still waiting for resolution. */
    public val pointerId: String? get() = (base as? StylePointer)?.id

    public companion object {
        public inline fun <reified T : Any> none(): Wrapped<T> = Wrapped(null, null, T::class.java)
        public inline fun <reified T : Any> of(value: T): Wrapped<T> = Wrapped(null, value, T::class.java)
    }
}

internal fun decodeStyleAny(c: Cursor): StyleAny {
    c.obj()
    val type = c.opt("type") { StyleType.of(it.string()) }
    if (!c.hasNonNull("content")) {
        val id = c.reqString("id")
        return if (type != null) StylePointer(id, type, true) else StylePointer(id, StyleType.Custom(""), false)
    }
    if (type == null) c.fail("style with 'content' requires a 'type' field")
    val id = c.reqString("id")
    val known = type as? StyleType.Known ?: c.fail("unsupported custom style type with content: ${(type as StyleType.Custom).name}")
    val content: Any = c.req("content") { cc ->
        when (known.key) {
            StyleType.Key.fill -> decodeFill(cc)
            StyleType.Key.color -> decodeThemedColor(cc)
            StyleType.Key.gradient -> decodeGradient(cc)
            StyleType.Key.outline -> decodeOutline(cc)
            StyleType.Key.shadow -> decodeShadow(cc)
            StyleType.Key.corner -> decodeCorner(cc)
            StyleType.Key.visualEffect -> decodeVisualEffect(cc)
            StyleType.Key.material -> decodeMaterial(cc)
            StyleType.Key.text -> decodeTextModel(cc)
            StyleType.Key.font -> decodeFont(cc)
            StyleType.Key.view -> decodeViewStyle(cc)
            StyleType.Key.image -> decodeImageStyle(cc)
            StyleType.Key.video -> decodeVideoStyle(cc)
            StyleType.Key.icon -> decodeIconStyle(cc)
            StyleType.Key.systemButton -> decodeSystemButtonStyle(cc)
        }
    }
    return StyleEntity(id, type, content)
}

/**
 * `@Wrapped` decoding: missing / `null` → none; a decodable style reference → [Wrapped.base];
 * otherwise the inline value, and any failure there gives none (iOS wraps optionals, so it never throws).
 */
internal inline fun <reified T : Any> Cursor.wrapped(key: String, crossinline decode: (Cursor) -> T): Wrapped<T> {
    val c = (element as? JsonObject)?.get(key)?.let { with(it, childPath(key)) } ?: return Wrapped.none()
    if (c.isNull) return Wrapped.none()
    c.attempt(::decodeStyleAny)?.let { return Wrapped(it, null, T::class.java) }
    return Wrapped(null, c.attempt { decode(it) }, T::class.java)
}
