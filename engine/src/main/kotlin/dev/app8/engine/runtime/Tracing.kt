package dev.app8.engine.runtime

import android.os.Trace

/**
 * A `android.os.Trace` section around [block]: visible in Perfetto and measured by the
 * macrobenchmarks (`TraceSectionMetric("App8.solve")`). Costs a flag check when not tracing.
 * Names are `App8.<stage>`: `App8.layoutTree` (solver input from the node tree, with its
 * expression evaluation and text measuring), `App8.solve` (a solver run on a cache miss),
 * `App8.cell` (a collection cell's node tree).
 */
internal inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
