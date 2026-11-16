package dev.app8.engine.navigation

import dev.app8.engine.render.hexColor
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.model.NavigationBar
import dev.app8.engine.core.model.SearchBar
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.render.LocalDispatcher
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.render.ScreenSession
import dev.app8.engine.render.string

/** Where `navigationBar.search` sits in the Material 3 top app bar. */
internal enum class SearchSlot {
    /** In the title slot (iOS `inline` / `integrated` / `integratedCentered`, or a bar with no title). */
    Title,

    /** Its own row under the title (iOS `stacked`, and `automatic` with a title). */
    Row,

    /** A search action that expands into the title slot (iOS 26 `integratedButton`). */
    Button,
}

/**
 * `fillsBar` never moves the field (iOS crashed relocating it into the left bar items): with
 * `integrated` it stays in the title slot, which already spans the bar width.
 */
internal fun searchSlot(bar: NavigationBar, title: String?): SearchSlot? {
    val search = bar.search ?: return null
    return when (search.placement) {
        SearchBar.Placement.integratedButton -> SearchSlot.Button
        SearchBar.Placement.stacked -> SearchSlot.Row
        SearchBar.Placement.inline, SearchBar.Placement.integrated, SearchBar.Placement.integratedCentered -> SearchSlot.Title
        SearchBar.Placement.automatic, null -> if (title.isNullOrEmpty()) SearchSlot.Title else SearchSlot.Row
    }
}

/** Material 3 search bar / search input field height (SearchBarTokens.ContainerHeight). */
internal const val SEARCH_FIELD_HEIGHT = 56

/** Height a [SearchSlot.Row] field adds under the top app bar: the 56dp field and an 8dp gap below. */
internal const val SEARCH_ROW_HEIGHT = 64.0

/**
 * The DSL `navigationBar.search` as a Material 3 search input field (app8-dsl platforms.md:
 * system chrome follows Android). The text is two-way bound to `bindVariable` (written on
 * every edit, like iOS `updateSearchResults`); the IME search key runs `onSubmit`. While
 * focused, a ✕ clears the text and closes the field (the iOS cancel button).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavSearchField(
    session: ScreenSession,
    search: SearchBar,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    onClose: (() -> Unit)? = null,
    container: Color? = null,
) {
    val env = LocalRenderEnv.current
    val dispatcher = LocalDispatcher.current
    val root = session.root
    val store = session.scope.store
    val bind = search.bindVariable
    // Reads the variable through the tracked scope so outside writes (an action clearing
    // the query) flow back into the field.
    val bound = bind?.let { root.string("{{$it}}") }
    var query by rememberSaveable(session.screenId) { mutableStateOf(bound.orEmpty()) }
    // Only an outside write (not our own echo) replaces what the user is typing.
    val lastSeen = remember { arrayOf(bound) }
    if (bound != lastSeen[0]) {
        lastSeen[0] = bound
        if (bound != null && bound != query) query = bound
    }
    var expanded by remember { mutableStateOf(false) }
    val button = search.isButton
    // Like iOS, `autoFocus` raises the keyboard on the first appearance only:
    // back from a result, the results stay without the keyboard.
    var autoFocused by rememberSaveable(session.screenId) { mutableStateOf(false) }
    val cancel = search.onCancel
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    // Colors from the app's scheme (app8-dsl platforms.md, "Android system colors"):
    // a neutral step off the background, never Material's lavender baseline.
    val scheme = MaterialTheme.colorScheme
    val tint = root.hexColor(search.tintColor) ?: scheme.primary
    val label = scheme.onSurface
    val secondary = scheme.onSurfaceVariant
    val fill = container ?: scheme.surfaceContainerHigh
    fun write(text: String) {
        query = text
        lastSeen[0] = text
        if (bind == null) return
        if (store.getValue(bind) as? String == text) return
        runCatching { store.setValue(bind, text) }
            .onFailure { env.runtime.diagnostics.warn("navigationBar.search", "can't set '$bind': ${it.message}") }
    }
    val colors = SearchBarDefaults.inputFieldColors(
        focusedTextColor = label,
        unfocusedTextColor = label,
        cursorColor = tint,
        focusedLeadingIconColor = secondary,
        unfocusedLeadingIconColor = secondary,
        focusedPlaceholderColor = secondary,
        unfocusedPlaceholderColor = secondary,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
    )
    if (button) {
        // `mode: "button"`: the collapsed Material 3 search bar, drawn like the field; a tap
        // runs `onTap` (the search screen) instead of focusing.
        Surface(
            onClick = { search.onTap?.forEach { dispatcher.execute(root, it) } },
            modifier = modifier,
            shape = SearchBarDefaults.inputFieldShape,
            color = fill,
        ) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().height(SEARCH_FIELD_HEIGHT.dp).padding(horizontal = 16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Symbol("magnifyingglass", secondary)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 12.dp))
                Text(
                    query.ifEmpty { search.placeholder?.let { root.string(it) }.orEmpty() },
                    color = if (query.isEmpty()) secondary else label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
            }
        }
        return
    }
    Surface(modifier, shape = SearchBarDefaults.inputFieldShape, color = fill) {
        SearchBarDefaults.InputField(
            query = query,
            onQueryChange = ::write,
            onSearch = {
                // Like UISearchBar, the field keeps focus (and the keyboard) after submit.
                search.onSubmit?.let { dispatcher.run(root, it) }
            },
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth().height(SEARCH_FIELD_HEIGHT.dp).focusRequester(requester),
            placeholder = search.placeholder?.let { p -> { Text(root.string(p).orEmpty(), maxLines = 1) } },
            leadingIcon = { Symbol("magnifyingglass", secondary) },
            trailingIcon = if (expanded || onClose != null || cancel != null) {
                {
                    IconButton(onClick = {
                        write("")
                        focus.clearFocus()
                        expanded = false
                        onClose?.invoke()
                        cancel?.forEach { dispatcher.execute(root, it) }
                    }) { Symbol("xmark", tint) }
                }
            } else null,
            colors = colors,
        )
    }
    // Registered with the screen's focus manager so `dismissKeyboard` and leaving the
    // screen (ActionDispatcher.navigation) release the field and its keyboard.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.runtime.DisposableEffect(session) {
        val key = "navigationBar.search"
        session.focus.register(
            dev.app8.engine.render.controls.FocusManager.Entry(key, key, -1,
                request = { runCatching { requester.requestFocus() } },
                release = { keyboard?.hide(); focus.clearFocus(force = true) }),
        )
        onDispose { session.focus.unregister(key) }
    }
    if (autoFocus || (search.autoFocus == true && !autoFocused)) LaunchedEffect(Unit) {
        autoFocused = true
        runCatching { requester.requestFocus() }
    }
}

/** [NavSearchField] in its own row under the top app bar. */
@Composable
internal fun NavSearchRow(session: ScreenSession, search: SearchBar, modifier: Modifier = Modifier) {
    NavSearchField(session, search, modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
}
