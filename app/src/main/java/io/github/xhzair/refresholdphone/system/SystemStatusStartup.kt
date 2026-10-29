package io.github.xhzair.refresholdphone.system

internal fun runSystemStatusStartup(isActive: () -> Boolean, vararg steps: () -> Unit) {
    for (step in steps) {
        if (!isActive()) return
        step()
    }
}
