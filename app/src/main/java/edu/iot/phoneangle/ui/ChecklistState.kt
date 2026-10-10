package edu.iot.phoneangle.ui

import android.content.Context
import edu.iot.phoneangle.IdentityRegistry
import edu.iot.phoneangle.ProjectSettings
import edu.iot.phoneangle.R
import edu.iot.phoneangle.TransportMode
import edu.iot.phoneangle.ml.ModelSlot
import edu.iot.phoneangle.ml.ModelStore
import edu.iot.phoneangle.ml.PoseWorkflow

enum class StepStatus { TODO, DONE, BLOCKED, PREVIEW }

data class ChecklistStep(
    val id: String,
    val title: String,
    val detail: String,
    val status: StepStatus,
    /** Bottom-nav destination, or 0 for identity dialog. */
    val navId: Int,
)

/** Reads local prefs/stores and builds the Home checklist. */
class ChecklistState(context: Context) {

    private val app = context.applicationContext
    private val settings = ProjectSettings(app)
    private val registry = IdentityRegistry(app)
    private val workflow = PoseWorkflow(app)
    private val models = ModelStore(app)

    fun steps(): List<ChecklistStep> {
        val claimed = registry.isLocked
        val status = workflow.status()
        val hasBaseline = models.has(ModelSlot.LOCAL_BASELINE)
        val hasGlobal = models.has(ModelSlot.GLOBAL)
        val hasPeer = models.has(ModelSlot.PEER_BASELINE)
        val syncLooksSet = when (settings.transportMode) {
            TransportMode.GIT -> settings.githubToken.isNotBlank()
            TransportMode.LAN -> settings.serverBaseUrl.isNotBlank()
        }

        return listOf(
            ChecklistStep(
                id = "claim",
                title = app.getString(R.string.home_step_claim),
                detail = if (claimed) {
                    app.getString(R.string.home_step_claim_done, settings.displayLabel())
                } else {
                    app.getString(R.string.home_step_claim_todo)
                },
                status = if (claimed) StepStatus.DONE else StepStatus.TODO,
                navId = 0,
            ),
            ChecklistStep(
                id = "sync",
                title = app.getString(R.string.home_step_sync),
                detail = if (syncLooksSet) {
                    app.getString(R.string.home_step_sync_done, settings.transportMode.label)
                } else {
                    app.getString(R.string.home_step_sync_todo)
                },
                status = when {
                    !claimed -> StepStatus.BLOCKED
                    syncLooksSet -> StepStatus.DONE
                    else -> StepStatus.TODO
                },
                navId = R.id.nav_sync,
            ),
            ChecklistStep(
                id = "collect_pose",
                title = app.getString(R.string.home_step_collect),
                detail = if (status.guidanceComplete && status.ready) {
                    app.getString(R.string.home_step_collect_done, status.totalTrials)
                } else {
                    app.getString(R.string.home_step_collect_todo, status.totalTrials)
                },
                status = when {
                    !claimed -> StepStatus.BLOCKED
                    status.guidanceComplete && status.ready -> StepStatus.DONE
                    else -> StepStatus.TODO
                },
                navId = R.id.nav_collect,
            ),
            ChecklistStep(
                id = "train",
                title = app.getString(R.string.home_step_train),
                detail = if (hasBaseline) {
                    app.getString(R.string.home_step_train_done)
                } else {
                    app.getString(R.string.home_step_train_todo)
                },
                status = when {
                    !status.ready -> StepStatus.BLOCKED
                    hasBaseline -> StepStatus.DONE
                    else -> StepStatus.TODO
                },
                navId = R.id.nav_model,
            ),
            ChecklistStep(
                id = "round",
                title = app.getString(R.string.home_step_round),
                detail = if (hasGlobal) {
                    app.getString(R.string.home_step_round_done)
                } else {
                    app.getString(R.string.home_step_round_todo)
                },
                status = when {
                    !hasBaseline || !syncLooksSet -> StepStatus.BLOCKED
                    hasGlobal -> StepStatus.DONE
                    else -> StepStatus.TODO
                },
                navId = R.id.nav_sync,
            ),
            ChecklistStep(
                id = "compare",
                title = app.getString(R.string.home_step_compare),
                detail = if (hasGlobal && (hasPeer || hasBaseline)) {
                    app.getString(R.string.home_step_compare_done)
                } else {
                    app.getString(R.string.home_step_compare_todo)
                },
                status = when {
                    !hasGlobal -> StepStatus.BLOCKED
                    hasPeer || hasBaseline -> StepStatus.DONE
                    else -> StepStatus.TODO
                },
                navId = R.id.nav_results,
            ),
        )
    }

    fun nextAction(): ChecklistStep? =
        steps().firstOrNull { it.status == StepStatus.TODO }
}
