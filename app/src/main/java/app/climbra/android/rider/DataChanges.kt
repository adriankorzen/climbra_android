package app.climbra.android.rider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bumped when the rider's rides or analyses change (a ride deleted, reanalysed, its interval source switched, linked
 * to a workout), so screens that show them load again when shown — the web's `RiderContext.dataChanged()`.
 */
class DataChanges {
    private val mutableVersion = MutableStateFlow(0)
    val version: StateFlow<Int> = mutableVersion.asStateFlow()

    fun changed() = mutableVersion.update { it + 1 }
}
