package edu.iot.phoneangle.ui

/** Implemented by tab fragments so the shell can ask them to re-read state. */
interface Refreshable {
    fun refresh()
}

/** Implemented by the shell so tabs can update chips and navigate. */
interface ShellHost {
    fun refreshChips()
    fun openTab(menuItemId: Int)
    fun openDevTools()
    fun openIdentityDialog()
    /** Hide bottom nav while the soft keyboard is up (typed collect). */
    fun setBottomNavVisible(visible: Boolean)
}
