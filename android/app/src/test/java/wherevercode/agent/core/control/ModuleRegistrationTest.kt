package wherevercode.agent.core.control

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Module registration check (M2, plan §2.1; CI check).
 *
 * Every core/control/ file must be registered in
 * docs/module-interfaces.json. Registration itself is done by the
 * merge coordinator (docs/module-interfaces.json is NOT touched by
 * either implementation route); this test fails until that merge step
 * runs, which is expected pre-merge.
 */
class ModuleRegistrationTest {

    private val expectedFiles = listOf(
        "ControlPlane.kt",
        "ControlState.kt",
        "GoalVersion.kt",
        "AuthorizationEpoch.kt",
        "CancellationToken.kt",
        "ExecutionFence.kt",
        "ControlAuditLog.kt",
        "ControlStore.kt",
    )

    private fun findModuleInterfaces(): File? {
        val candidates = listOf(
            "docs/module-interfaces.json",
            "../../docs/module-interfaces.json",
            "android/docs/module-interfaces.json",
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() }
    }

    @Test
    fun `core control files are registered in module-interfaces json`() {
        val file = findModuleInterfaces()
        if (file == null) {
            fail("docs/module-interfaces.json 未找到（候选路径均不存在）")
            return
        }
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains("core/control"))
        for (name in expectedFiles) {
            assertTrue("module-interfaces.json 未登记 core/control/$name（合并时由协调员登记）", text.contains(name))
        }
    }
}
