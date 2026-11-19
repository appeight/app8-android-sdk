package dev.app8.engine.icons

/**
 * Spoken names for icon-only chrome buttons (top app bar items, the search button, the back
 * arrow). iOS VoiceOver reads a bar button's title or its SF Symbol's name; TalkBack needs a
 * content description, else it reads nothing useful. Common actions get Material's wording
 * ("Navigate up", "Search", "More options"); any other symbol is read from its name
 * ("cart.badge.plus" → "Cart").
 */
internal object SymbolLabels {
    /** The Material back arrow's description (AppCompat `abc_action_bar_up_description`). */
    const val NAVIGATE_UP: String = "Navigate up"

    private val known = mapOf(
        "arrow_back" to NAVIGATE_UP,
        "chevron.left" to NAVIGATE_UP,
        "chevron.backward" to NAVIGATE_UP,
        "arrow.left" to NAVIGATE_UP,
        "arrow.backward" to NAVIGATE_UP,
        "magnifyingglass" to "Search",
        "search" to "Search",
        "xmark" to "Close",
        "xmark.circle" to "Close",
        "close" to "Close",
        "heart" to "Favourite",
        "favorite" to "Favourite",
        "bookmark" to "Bookmark",
        "ellipsis" to "More options",
        "ellipsis.circle" to "More options",
        "more_vert" to "More options",
        "more_horiz" to "More options",
        "line.3.horizontal" to "Menu",
        "line.horizontal.3" to "Menu",
        "menu" to "Menu",
        "square.and.arrow.up" to "Share",
        "share" to "Share",
        "slider.horizontal.3" to "Filter",
        "line.3.horizontal.decrease" to "Filter",
        "line.3.horizontal.decrease.circle" to "Filter",
        "plus" to "Add",
        "add" to "Add",
        "trash" to "Delete",
        "delete" to "Delete",
        "pencil" to "Edit",
        "square.and.pencil" to "Edit",
        "edit" to "Edit",
        "gearshape" to "Settings",
        "gear" to "Settings",
        "settings" to "Settings",
        "bell" to "Notifications",
        "cart" to "Cart",
        "bag" to "Bag",
        "person" to "Account",
        "person.circle" to "Account",
        "person.crop.circle" to "Account",
        "info.circle" to "Info",
        "questionmark.circle" to "Help",
        "checkmark" to "Done",
        "arrow.clockwise" to "Refresh",
    )

    /** The spoken label for symbol [name] (SF or Material); empty for an empty name. */
    fun of(name: String): String {
        val n = name.trim()
        if (n.isEmpty()) return ""
        known[n]?.let { return it }
        val base = n.removeSuffix(".fill").replace(".fill.", ".")
        known[base]?.let { return it }
        // Material names: words joined by underscores; SF names: the first dot component.
        val word = if ('.' in base) base.substringBefore('.') else base
        known[word]?.let { return it }
        val spoken = word.replace('_', ' ').trim()
        return spoken.replaceFirstChar { it.uppercaseChar() }
    }
}
