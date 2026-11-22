package app.climbra.android.app

import kotlinx.coroutines.flow.StateFlow

/** Whether the app is shown, at least started (the process lifecycle). */
class AppForeground(val visible: StateFlow<Boolean>)
