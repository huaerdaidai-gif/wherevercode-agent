package wherevercode.agent.core.agent

/**
 * Determines whether an Agent-owned Keystore reference is safe to delete.
 * All persisted owners must be supplied by the caller; if any owner cannot be
 * read, the caller must conservatively retain the secret.
 */
object AgentSecretReferencePolicy {
    fun isReferenced(
        authRef: String,
        agentAuthRefs: Iterable<String?>,
        brainConfigAuthRefs: Iterable<String?>,
        teamMemberAuthRefs: Iterable<String?>,
    ): Boolean =
        agentAuthRefs.any { it == authRef } ||
            brainConfigAuthRefs.any { it == authRef } ||
            teamMemberAuthRefs.any { it == authRef }
}
