package io.github.xhzair.refresholdphone.media

/** Process-local connection signal only; never holds a service or Activity. */
internal class ListenerConnectionRegistry {
    private val lock = Any()
    private val listeners = mutableSetOf<(Boolean) -> Unit>()
    private var current = false
    val connected: Boolean get() = synchronized(lock) { current }

    fun add(listener: (Boolean) -> Unit) { synchronized(lock) { listeners.add(listener) } }
    fun remove(listener: (Boolean) -> Unit) { synchronized(lock) { listeners.remove(listener) } }

    fun update(connected: Boolean) {
        val callbacks = synchronized(lock) {
            if (current == connected) return
            current = connected
            listeners.toList()
        }
        callbacks.forEach { it(connected) }
    }
}

internal val mediaListenerConnections = ListenerConnectionRegistry()
