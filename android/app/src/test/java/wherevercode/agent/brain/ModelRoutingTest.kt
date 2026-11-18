package wherevercode.agent.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.generic.FreeProviderPresets

/**
 * 智能路由的池与守卫：Funk 口径逐项落地——默认关、只在免费之间切、
 * 付费须用户开启、池只收模型连接、顺序即优先级。
 */
class ModelRoutingTest {

    private fun modelConfig(
        brainId: String,
        free: Boolean,
        kind: BrainKind = BrainKind.MODEL_API,
    ) = BrainConfig(
        brainId = brainId,
        kind = kind,
        adapterId = "model-api-generic",
        label = brainId,
        baseUrl = "https://api.example.com/v1",
        model = "m",
        authRef = "model-api:$brainId",
        extra = if (free) {
            mapOf(FreeProviderPresets.EXTRA_TIER to FreeProviderPresets.TIER_FREE)
        } else {
            emptyMap()
        },
    )

    private val freeA = modelConfig("free-a", free = true)
    private val freeB = modelConfig("free-b", free = true)
    private val paidC = modelConfig("paid-c", free = false)
    private val all = listOf(freeA, freeB, paidC)

    @Test
    fun `disabled routing yields no fallbacks`() {
        val settings = ModelRoutingSettings(enabled = false, orderedBrainIds = listOf("free-b"))
        assertTrue(ModelRouting.fallbackOrder(settings, all, "free-a").isEmpty())
    }

    @Test
    fun `default pool switches only among free models`() {
        val settings = ModelRoutingSettings(
            enabled = true,
            allowPaid = false,
            orderedBrainIds = listOf("free-b", "paid-c"),
        )
        assertEquals(listOf("free-b"), ModelRouting.fallbackOrder(settings, all, "free-a"))
    }

    @Test
    fun `paid joins the pool only when the user allows it`() {
        val settings = ModelRoutingSettings(
            enabled = true,
            allowPaid = true,
            orderedBrainIds = listOf("free-b", "paid-c"),
        )
        assertEquals(listOf("free-b", "paid-c"), ModelRouting.fallbackOrder(settings, all, "free-a"))
    }

    @Test
    fun `current model, missing ids and non-model configs are excluded`() {
        val subscription = modelConfig("sub-x", free = true, kind = BrainKind.SUBSCRIPTION)
        val settings = ModelRoutingSettings(
            enabled = true,
            allowPaid = true,
            orderedBrainIds = listOf("free-a", "ghost", "sub-x", "free-b"),
        )
        assertEquals(
            listOf("free-b"),
            ModelRouting.fallbackOrder(settings, all + subscription, "free-a"),
        )
    }

    @Test
    fun `pool order is the priority order`() {
        val settings = ModelRoutingSettings(
            enabled = true,
            orderedBrainIds = listOf("paid-c", "free-b", "free-a"),
        )
        // 当前是 paid-c（用户主动在用付费）：往下切只许去免费（默认守卫）。
        assertEquals(listOf("free-b", "free-a"), ModelRouting.fallbackOrder(settings, all, "paid-c"))
    }

    @Test
    fun `policy carries the computed order`() {
        val settings = ModelRoutingSettings(enabled = true, orderedBrainIds = listOf("free-b"))
        assertEquals(listOf("free-b"), ModelRouting.policyFor(settings, all, "free-a").fallbackBrainIds)
    }
}
