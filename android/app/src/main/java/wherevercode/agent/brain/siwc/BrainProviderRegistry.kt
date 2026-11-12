package wherevercode.agent.brain.siwc

/**
 * Registry for switching brains.
 *
 * 未接入（C3）：本注册表不在装配链上，全树零调用方。引擎选脑只走
 * [wherevercode.agent.brain.BrainAdapterRegistry] + 持久化的
 * `currentBrainId()`；本类的内存 `activeProviderId` 会与之打架，故
 * C3 不接线它。勿用、勿接线；删除留给后续清理批次。
 *
 * The super Agent holds one active brain at a time; the user (or the
 * dispatcher, with the user's approval) may switch it. No provider is the
 * permanent brain.
 *
 * Rules:
 * - Registering a provider does not authorize it. Authorization is
 *   per-provider and per-user (for example the SIWC sign-in), done explicitly
 *   by the user.
 * - Switching brains does not transfer one user's plan or quota to another
 *   user. Each brain runs only for the user who authorized it.
 * - A brain that is not configured must not be selected for inference. The
 *   dispatcher must report "no available brain" rather than faking a reply
 *   or silently falling back to a different user's authorization.
 * - This registry is in-memory only. Which brain is active is a user
 *   preference; persist it (if at all) as a plain provider id, never with
 *   any credential.
 */
class BrainProviderRegistry {

    private val providers = mutableMapOf<String, BrainProvider>()

    /** The provider id currently selected as the brain, or null if none. */
    var activeProviderId: String? = null
        private set

    fun register(provider: BrainProvider) {
        providers[provider.providerId] = provider
    }

    fun unregister(providerId: String) {
        providers.remove(providerId)
        if (activeProviderId == providerId) activeProviderId = null
    }

    fun get(providerId: String): BrainProvider? = providers[providerId]

    fun list(): List<BrainProvider> = providers.values.toList()

    /** The currently active brain, or null if none is selected. */
    fun active(): BrainProvider? = activeProviderId?.let { providers[it] }

    /**
     * Select a brain. The provider must be registered and configured.
     * Selecting an unconfigured provider is an error, not a silent fallback.
     */
    fun select(providerId: String) {
        val provider = providers[providerId]
            ?: throw BrainError.NotConfigured("Brain provider not registered: $providerId")
        if (!provider.isConfigured()) {
            throw BrainError.NotConfigured(
                "Brain provider not configured: $providerId (${provider.displayName}). " +
                    "The user must sign in / authorize it first.",
            )
        }
        activeProviderId = providerId
    }

    /** Clear the active brain (for example after a revoke). */
    fun clearActive() {
        activeProviderId = null
    }
}
