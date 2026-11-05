package dev.app8.engine.core.vars

/** Handle returned by [ChangePublisher.subscribe]; [cancel] stops delivery (Combine `AnyCancellable`). */
public fun interface Cancellable {
    public fun cancel()
}

/**
 * Synchronous change stream (Combine `AnyPublisher<T, Never>` replacement). Listeners are invoked
 * on the sending thread, in subscription order, before `send` returns — the same semantics as a
 * Combine `PassthroughSubject` with a `sink` on the main actor.
 */
public fun interface ChangePublisher<T> {
    public fun subscribe(listener: (T) -> Unit): Cancellable
}

/** Combine `PassthroughSubject` equivalent. Not thread-safe; confine to one (main) thread. */
internal class PassthroughSubject<T> : ChangePublisher<T> {
    private class Entry<T>(val listener: (T) -> Unit) {
        var active = true
    }

    private var entries = ArrayList<Entry<T>>()

    override fun subscribe(listener: (T) -> Unit): Cancellable {
        val entry = Entry(listener)
        entries.add(entry)
        return Cancellable {
            if (entry.active) {
                entry.active = false
                entries.remove(entry)
            }
        }
    }

    fun send(value: T) {
        // Snapshot so listeners may (un)subscribe while being notified.
        for (entry in entries.toTypedArray()) {
            if (entry.active) entry.listener(value)
        }
    }
}
