package app.climbra.android.coach

import app.climbra.android.data.api.CoachApi
import app.climbra.android.data.api.CoachQuota
import app.climbra.android.data.api.ConversationType
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.session.SessionRepository
import app.climbra.android.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What a request to the coach is, for its cost (cyclingapp spec 013; the web's `CoachCostKind`). */
enum class CostKind { REVIEW, REVIEW_MESSAGE, TRAINING_BLOCK, PLAN_ADJUSTMENT, BLOCK_MESSAGE }

/** The cost of a follow-up in a conversation of [type] (the web's `messageCost`). */
fun messageCost(type: String): CostKind =
    if (type == ConversationType.TRAINING_BLOCK || type == ConversationType.PLAN_ADJUSTMENT) CostKind.BLOCK_MESSAGE
    else CostKind.REVIEW_MESSAGE

/** The cost of starting a conversation of [type] (the web's `startCost`). */
fun startCost(type: String): CostKind = when (type) {
    ConversationType.TRAINING_BLOCK -> CostKind.TRAINING_BLOCK
    ConversationType.PLAN_ADJUSTMENT -> CostKind.PLAN_ADJUSTMENT
    else -> CostKind.REVIEW
}

fun CoachQuota.cost(kind: CostKind): Int = when (kind) {
    CostKind.REVIEW -> costs.review
    CostKind.REVIEW_MESSAGE -> costs.reviewMessage
    CostKind.TRAINING_BLOCK -> costs.trainingBlock
    CostKind.PLAN_ADJUSTMENT -> costs.planAdjustment
    CostKind.BLOCK_MESSAGE -> costs.blockMessage
}

/** The remaining credits cover a request of [kind]; always while the credits are not loaded (the backend decides). */
fun CoachQuota?.covers(kind: CostKind): Boolean = this == null || unlimited || remaining >= cost(kind)

/** No credits left (the free first review of a ride still works). */
val CoachQuota.exhausted: Boolean get() = !unlimited && remaining <= 0

/**
 * The account's AI credits, one for the whole app (spec 006): loaded with the session and again after every coach
 * request and its end; the coach and the profile (spec 004) read [quota].
 */
class CoachQuotaRepository(
    private val api: CoachApi,
    session: SessionRepository,
    private val scope: CoroutineScope,
) {
    /** A load on its way, which a second caller waits for instead of loading twice. */
    private var loading: Deferred<ApiResult<CoachQuota>>? = null
    private val mutableQuota = MutableStateFlow<CoachQuota?>(null)
    val quota: StateFlow<CoachQuota?> = mutableQuota.asStateFlow()

    init {
        scope.launch {
            session.state
                .map { (it as? SessionState.LoggedIn)?.account?.id }
                .distinctUntilChanged()
                .collect { account ->
                    mutableQuota.value = null
                    if (account != null) reload()
                }
        }
    }

    /** Loads the credits again; a failure keeps what was loaded before. */
    suspend fun reload(): ApiResult<CoachQuota> {
        val load = synchronized(this) {
            loading?.takeIf { it.isActive } ?: scope.async {
                apiCall(Network.json) { api.coachQuota() }.also { if (it is ApiResult.Success) mutableQuota.value = it.value }
            }.also { loading = it }
        }
        return load.await()
    }
}
