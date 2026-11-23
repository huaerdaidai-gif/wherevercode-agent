package wherevercode.agent.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内网成员令牌注册表（频道三层二期）：签发形状、鉴权、撤销即刻
 * 生效、落盘恢复。
 */
class LanTokenRegistryTest {

    @Test
    fun `issue produces a 32 hex token and a lan prefixed id`() {
        val registry = LanTokenRegistry()
        val entry = registry.issue("甲机")
        assertEquals(32, entry.token.length)
        assertTrue(entry.token.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(entry.agentId.startsWith("lan-"))
        assertEquals("甲机", entry.agentName)
    }

    @Test
    fun `authenticate round trips and revoke takes effect immediately`() {
        val registry = LanTokenRegistry()
        val entry = registry.issue("乙机")
        assertEquals(entry, registry.authenticate(entry.token))
        assertNull(registry.authenticate(null))
        assertNull(registry.authenticate(""))
        assertNull(registry.authenticate("0".repeat(32)))
        assertTrue(registry.revoke(entry.token))
        assertNull(registry.authenticate(entry.token))
    }

    @Test
    fun `entries survive a reload through the persistence callbacks`() {
        var stored: String? = null
        val first = LanTokenRegistry(load = { stored }, save = { stored = it })
        val entry = first.issue("丙机")
        val second = LanTokenRegistry(load = { stored }, save = { stored = it })
        assertEquals(entry, second.authenticate(entry.token))
        assertEquals(listOf(entry), second.list())
        assertNotNull(stored)
    }

    @Test
    fun `issued ids never collide across many issues`() {
        val registry = LanTokenRegistry()
        val ids = (1..64).map { registry.issue("成员$it").agentId }
        assertEquals(ids.size, ids.toSet().size)
        val tokens = registry.list().map { it.token }
        assertEquals(tokens.size, tokens.toSet().size)
    }
}
