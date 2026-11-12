package wherevercode.agent.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 任务拆分器（2026-11-11，大脑总管自动拆分），Robolectric（plan 用
 * org.json，本地 JVM 单元测试里是桩，与 PipelinePlanTest 同因）。
 *
 * 覆盖：按父目录分组、互不相交、拆不开的三条诚实口径（单文件/
 * 同目录/无清单）、禁改矛盾拒绝、超 5 组自动合并、派生 plan 把
 * 兄弟子任务文件写进禁改清单、方案 JSON 往返。
 */
@RunWith(RobolectricTestRunner::class)
class TaskDecomposerTest {

    private fun plan(files: List<String>, outOfScope: List<String> = emptyList()) = PlanArtifact(
        objective = "重构计算器",
        filesInScope = files,
        filesOutOfScope = outOfScope,
        steps = listOf("重构加法", "补单测"),
        acceptanceCriteria = listOf("测试全过"),
    )

    @Test
    fun `groups by parent directory with disjoint file sets`() {
        val proposal = TaskDecomposer.decompose(
            plan(
                listOf(
                    "core/task/Foo.kt",
                    "core/task/Bar.kt",
                    "core/team/Baz.kt",
                    "ui/Main.kt",
                ),
            ),
        )
        assertTrue(proposal is DecompositionProposal.Splittable)
        val subtasks = (proposal as DecompositionProposal.Splittable).subtasks
        assertEquals(3, subtasks.size)
        val allFiles = subtasks.flatMap { it.files }
        assertEquals(4, allFiles.size)
        assertEquals(4, allFiles.toSet().size)
        assertTrue(subtasks.any { it.files == listOf("core/task/Bar.kt", "core/task/Foo.kt") })
        assertTrue(subtasks.any { it.files == listOf("core/team/Baz.kt") })
        assertTrue(subtasks.any { it.files == listOf("ui/Main.kt") })
    }

    @Test
    fun `single file is honestly unsplittable`() {
        val proposal = TaskDecomposer.decompose(plan(listOf("only.py")))
        assertTrue(proposal is DecompositionProposal.Unsplittable)
        assertTrue((proposal as DecompositionProposal.Unsplittable).reason.contains("只有一个文件"))
    }

    @Test
    fun `all files in one directory is honestly unsplittable`() {
        val proposal = TaskDecomposer.decompose(
            plan(listOf("core/task/A.kt", "core/task/B.kt", "core/task/C.kt")),
        )
        assertTrue(proposal is DecompositionProposal.Unsplittable)
        assertTrue((proposal as DecompositionProposal.Unsplittable).reason.contains("同一目录"))
    }

    @Test
    fun `no file inventory and no plan scope is honestly unsplittable`() {
        val proposal = TaskDecomposer.decompose(
            PlanArtifact(
                objective = "做点什么",
                steps = listOf("第一步"),
                acceptanceCriteria = listOf("能跑"),
            ),
        )
        assertTrue(proposal is DecompositionProposal.Unsplittable)
        assertTrue((proposal as DecompositionProposal.Unsplittable).reason.contains("没有文件清单"))
    }

    @Test
    fun `plan without scope splits from the project file inventory`() {
        val proposal = TaskDecomposer.decompose(
            PlanArtifact(
                objective = "做点什么",
                steps = listOf("第一步"),
                acceptanceCriteria = listOf("能跑"),
            ),
            availableFiles = listOf("a/Foo.kt", "b/Bar.kt"),
        )
        assertTrue(proposal is DecompositionProposal.Splittable)
        assertEquals(2, (proposal as DecompositionProposal.Splittable).subtasks.size)
    }

    @Test
    fun `file in both scope and out of scope is rejected`() {
        val proposal = TaskDecomposer.decompose(
            plan(
                listOf("a/Foo.kt", "b/Bar.kt"),
                outOfScope = listOf("b/Bar.kt"),
            ),
        )
        assertTrue(proposal is DecompositionProposal.Unsplittable)
        assertTrue((proposal as DecompositionProposal.Unsplittable).reason.contains("自相矛盾"))
    }

    @Test
    fun `more than five groups merge down to five`() {
        val files = (1..7).map { "mod$it/File.kt" }
        val proposal = TaskDecomposer.decompose(plan(files))
        assertTrue(proposal is DecompositionProposal.Splittable)
        val subtasks = (proposal as DecompositionProposal.Splittable).subtasks
        assertEquals(TaskDecomposer.MAX_SUBTASKS, subtasks.size)
        assertEquals(7, subtasks.flatMap { it.files }.toSet().size)
    }

    @Test
    fun `derived plan puts sibling files out of scope`() {
        val proposal = TaskDecomposer.decompose(
            plan(listOf("a/Foo.kt", "b/Bar.kt", "c/Baz.kt")),
        ) as DecompositionProposal.Splittable
        val first = proposal.subtasks[0]
        val siblings = proposal.subtasks.drop(1).flatMap { it.files }
        val derived = TaskDecomposer.derivedPlan(plan(listOf("a/Foo.kt", "b/Bar.kt", "c/Baz.kt")), first, siblings)
        assertEquals(first.files, derived.filesInScope)
        assertTrue(derived.filesOutOfScope.containsAll(siblings))
        assertTrue(derived.filesOutOfScope.none { it in first.files })
    }

    @Test
    fun `proposal json round trip keeps subtasks`() {
        val proposal = TaskDecomposer.decompose(
            plan(listOf("a/Foo.kt", "b/Bar.kt")),
        ) as DecompositionProposal.Splittable
        val restored = TaskDecomposer.proposalFromJson(TaskDecomposer.proposalToJson(proposal))
        assertEquals(proposal, restored)
        assertNull(TaskDecomposer.proposalFromJson("{\"version\": 99}"))
        assertNull(TaskDecomposer.proposalFromJson("not json"))
    }

    @Test
    fun `parent dir of root files is empty`() {
        assertEquals("", TaskDecomposer.parentDir("README.md"))
        assertEquals("a/b", TaskDecomposer.parentDir("a/b/File.kt"))
        assertEquals("a", TaskDecomposer.parentDir("a/File.kt"))
    }
}
