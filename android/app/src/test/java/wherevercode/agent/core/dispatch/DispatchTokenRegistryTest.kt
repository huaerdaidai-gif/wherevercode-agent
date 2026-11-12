package wherevercode.agent.core.dispatch

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：令牌只存哈希、常量时间校验、吊销与文件持久化。 */
class DispatchTokenRegistryTest {
    @Test
    fun `tokens are stored only as hashes and survive reload`() {
        val dir = Files.createTempDirectory("dispatch-tokens").toFile()
        val file = File(dir, "dispatch-state.json")
        val registry = DispatchTokenRegistry(FileDispatchStateStore(file))
        val issued = registry.issue("chatgpt", DispatchRole.AGENT, setOf(DispatchCapability.DISPATCH))
        assertTrue(issued.token.startsWith("wcd_"))
        val onDisk = file.readText()
        assertFalse("plaintext token must never be persisted", onDisk.contains(issued.token))
        assertFalse(onDisk.contains(issued.token.removePrefix("wcd_")))
        assertTrue(onDisk.contains(DispatchTokenRegistry.sha256Hex(issued.token)))

        val reloaded = DispatchTokenRegistry(FileDispatchStateStore(file))
        val p = reloaded.authenticate(issued.token)
        assertNotNull(p)
        assertEquals("chatgpt", p!!.id)
        assertEquals(setOf(DispatchCapability.DISPATCH), p.capabilities)
        reloaded.revoke(issued.tokenId)
        assertNull(DispatchTokenRegistry(FileDispatchStateStore(file)).authenticate(issued.token))
        dir.deleteRecursively()
    }

    @Test
    fun `malformed or unknown tokens are rejected`() {
        val registry = DispatchTokenRegistry(InMemoryDispatchStateStore())
        val issued = registry.issue("owner", DispatchRole.OWNER, emptySet())
        assertEquals(DispatchCapability.values().toSet(), issued.principal.capabilities)
        assertNull(registry.authenticate(null))
        assertNull(registry.authenticate(""))
        assertNull(registry.authenticate("wco_" + issued.token.removePrefix("wcd_")))
        assertNull(registry.authenticate(issued.token.dropLast(1) + if (issued.token.last() == 'A') "B" else "A"))
        assertNotNull(registry.authenticate(issued.token))
    }

    @Test
    fun `each issue yields a distinct token`() {
        val registry = DispatchTokenRegistry(InMemoryDispatchStateStore())
        val tokens = (1..20).map { registry.issue("a$it", DispatchRole.AGENT, setOf(DispatchCapability.EXECUTE)).token }.toSet()
        assertEquals(20, tokens.size)
        assertTrue(tokens.all { it.length == 47 })
    }
}
