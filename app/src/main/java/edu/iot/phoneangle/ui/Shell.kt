package edu.iot.phoneangle.ui

/** Implemented by tab fragments so the shell can ask them to re-read state (e.g. after switching client). */
interface Refreshable {
    fun refresh()
}

/** Implemented by the shell so tabs can update the top chips and navigate. */
interface ShellHost {
    fun refreshChips()
    fun openTab(menuItemId: Int)
    fun openDevTools()
}
