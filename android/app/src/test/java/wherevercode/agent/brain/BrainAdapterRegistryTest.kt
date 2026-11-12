package wherevercode.agent.brain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * BrainAdapterRegistry 配置注册/注销。
 *
 * 删脑时注册表必须同步清掉：引擎按 brainId 找配置走的就是
 * configFor（BrainEngine.chat），光删 BrainConfigStore 不清这里，
 * 删掉的脑在本次启动内仍能被路由到。
 */
class BrainAdapterRegistryTest {

    @After
    fun tearDown() {
        BrainAdapterRegistry.clearForTests()
    }

    @Test
    fun `unregisterConfig removes the brain from engine lookup`() {
        val config = BrainConfig(
            brainId = "model-api-zhipu",
            kind = BrainKind.MODEL_API,
            adapterId = "model-api-generic",
            label = "zhipu",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm-5.3",
            authRef = "model-api:model-api-zhipu",
        )
        BrainAdapterRegistry.registerConfig(config)
        assertEquals(config, BrainAdapterRegistry.configFor("model-api-zhipu"))
        BrainAdapterRegistry.unregisterConfig("model-api-zhipu")
        assertNull(BrainAdapterRegistry.configFor("model-api-zhipu"))
        // 删不存在的 id 不抛错、不影响别的配置。
        BrainAdapterRegistry.registerConfig(config)
        BrainAdapterRegistry.unregisterConfig("model-api-other")
        assertEquals(config, BrainAdapterRegistry.configFor("model-api-zhipu"))
    }
}
