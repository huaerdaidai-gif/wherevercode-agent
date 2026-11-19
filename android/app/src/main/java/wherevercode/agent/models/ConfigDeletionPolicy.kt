package wherevercode.agent.models

import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainConfigStore

/** Shared safety checks for deleting a model/brain credential and adapter registration. */
object ConfigDeletionPolicy {
    fun isAuthRefStillUsed(
        authRef: String,
        brainConfigs: List<BrainConfig>,
        modelEntries: List<ModelEntry>,
        agentAuthRefs: Iterable<String?>,
    ): Boolean =
        brainConfigs.any { it.authRef == authRef } ||
            modelEntries.any { it.authRef == authRef } ||
            agentAuthRefs.any { it == authRef }

    /** A model-side copy in the merged view must keep its adapter registered. */
    fun remainsInRuntimeView(brainId: String, runtimeStore: BrainConfigStore): Boolean =
        runtimeStore.getConfig(brainId) != null
}
