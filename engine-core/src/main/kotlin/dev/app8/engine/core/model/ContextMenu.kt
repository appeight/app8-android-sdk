package dev.app8.engine.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `content.contextMenu` (app8-dsl `docs/context-menu.md`): a long-press preview and a list of
 * actions on a `view`, `stackView`, `image` or `button`. String fields may be expressions,
 * evaluated in the owning component's scope when the menu opens.
 */
public data class ContextMenu(
    val preview: Preview? = null,
    val items: List<Item> = emptyList(),
    val onPreviewTap: List<Action>? = null,
    /** Boolean or expression; default `true`. */
    val isEnabled: String? = null,
) {
    public data class Preview(
        val component: Component,
        val width: Double? = null,
        val height: Double? = null,
    )

    public data class Item(
        val id: String,
        val title: String,
        val subtitle: String? = null,
        val icon: String? = null,
        val destructive: Boolean = false,
        val isEnabled: String? = null,
        val hidden: String? = null,
        val actions: List<Action> = emptyList(),
    )
}

/** Bool-or-expression field as the string the property resolver takes. */
private fun Cursor.optBoolOrExpression(key: String): String? =
    opt(key) { v -> v.stringOrNull() ?: v.bool().toString() }

internal fun decodeContextMenu(c: Cursor): ContextMenu {
    c.obj()
    return ContextMenu(
        preview = c.opt("preview") { p ->
            p.obj()
            p.opt("component") { cc ->
                // A `templateId` preview usually has no `id`: give it one so it decodes.
                val o = cc.obj()
                val node = if (o.containsKey("id")) o else JsonObject(o + ("id" to JsonPrimitive("contextMenuPreview")))
                try {
                    decodeComponent(cc.with(node))
                } catch (e: PlatformExcludedException) {
                    null
                }
            }?.let { comp ->
                ContextMenu.Preview(comp, p.optDouble("width"), p.optDouble("height"))
            }
        },
        items = c.safeOptionalList("items", "ContextMenu.Item") { i ->
            i.obj()
            ContextMenu.Item(
                id = i.reqString("id"),
                title = i.reqString("title"),
                subtitle = i.optString("subtitle"),
                icon = i.optString("icon"),
                destructive = i.optBool("destructive") ?: false,
                isEnabled = i.optBoolOrExpression("isEnabled"),
                hidden = i.optBoolOrExpression("hidden"),
                actions = i.opt("actions", ::decodeActionList) ?: emptyList(),
            )
        } ?: emptyList(),
        onPreviewTap = c.opt("onPreviewTap", ::decodeActionList),
        isEnabled = c.optBoolOrExpression("isEnabled"),
    )
}
