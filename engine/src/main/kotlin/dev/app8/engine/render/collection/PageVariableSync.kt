package dev.app8.engine.render.collection

/**
 * The bookkeeping behind a paging collection's `currentPageVariable`, kept free of Compose so
 * it can be tested on its own.
 *
 * Several pagers may bind the same variable (a product gallery, the full-screen gallery on top
 * of it, and product screens further back with fewer images). Without care they fight: a short
 * pager clamps the shared value to its own last page and writes that clamped page back, the
 * long pager follows it, and the page dots stick on page one or two. The rules, shared with
 * iOS and web:
 *
 * - The sync tracks the page the pager actually shows. It writes only when that page changes,
 *   and only if the variable holds a different value. The first report merely records the page.
 * - Following the variable records the clamped target as the pager's page, so a short pager
 *   never writes its clamped page back over the longer pager's value.
 * - A scroll driven by the variable mutes reports until it ends. The mute is guarded by a token
 *   and expires after [muteMillis] in case the end is never seen; the caller ends it at once
 *   when a user drag cancels the animation.
 */
internal class PageVariableSync(
    private val read: () -> Int?,
    private val write: (Int) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val muteMillis: Long = 1_000,
) {
    /** The page the pager shows, or null before its first report. */
    var page: Int? = null
        private set

    private var token = 0
    private var activeToken: Int? = null
    private var muteUntil = 0L

    private val muted: Boolean get() = activeToken != null && now() < muteUntil

    /** The page under the pager's offset is [p]. Called only when it changes. */
    fun report(p: Int) {
        val previous = page
        page = p
        if (previous == null || previous == p || muted) return
        if (read() != p) write(p)
    }

    /**
     * The variable now holds [target]. Returns the page to scroll to, clamped to the pager's
     * [pageCount], or null when the pager already shows it.
     */
    fun follow(target: Int, pageCount: Int): Int? {
        if (pageCount <= 0) return null
        val clamped = target.coerceIn(0, pageCount - 1)
        if (clamped == page) return null
        page = clamped
        return clamped
    }

    /** A variable-driven scroll starts; reports are muted until [endScroll] or the timeout. */
    fun beginScroll(): Int {
        token += 1
        activeToken = token
        muteUntil = now() + muteMillis
        return token
    }

    /** The scroll started with [scrollToken] ended, finished or cancelled by a user drag. */
    fun endScroll(scrollToken: Int) {
        if (activeToken == scrollToken) activeToken = null
    }
}
