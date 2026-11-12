package wherevercode.agent.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.workspace.ChangeKind
import wherevercode.agent.core.workspace.ChangeSet
import wherevercode.agent.core.workspace.ChangedFile

/**
 * a-3 任务交付卡 / 产物展示：纯逻辑（无 Android 依赖）。
 *
 * 字段清单复用老项目 taskDeliveryCard（v6-collab.js:334）+ renderExecutionTaskFiles
 *（v6-core.js:627）：状态 + 清单进度 + 测试 verdict 与来源 + 文件列表 +
 * 轮次改动量 + 保留/撤销；交付锚点按报告 §7：轮次 id + ChangeSet + artifact sha256，
 * 不照搬 branch/commit/pr_url。
 *
 * 诚实规则（测试即契约）：
 * - 没有产物的任务不产生卡（空 ChangeSet 不建卡、不建轮次记录）；
 * - 行数（+N/−N）ChangeSet 没有，不编数字；
 * - 测试 verdict 没有来源就是 UNKNOWN，不编一个；
 * - 保留/撤销按轮次 id 绑定，轮次对不上不写（复用老项目
 *   execution_artifacts.update_workspace_result 的 round_id 匹配语义）。
 */
class DeliveryCardTest {

    private fun changeSet(roundId: String, vararg files: Pair<String, ChangeKind>) =
        ChangeSet(roundId, files.map { ChangedFile(it.first, it.second) })

    private fun round(roundId: String, vararg files: Pair<String, ChangeKind>) =
        DeliveryRound(
            roundId = roundId,
            files = files.map { DeliveryFile(it.first, it.second) },
            recordedAtEpochSeconds = 1000L,
        )

    // -- 建卡规则 -----------------------------------------------------------

    @Test
    fun `no files means no card`() {
        assertNull(DeliveryCardBuilder.build("task-1", emptyList()))
        assertNull(
            DeliveryCardBuilder.build(
                "task-1",
                listOf(round("r1")),
            ),
        )
    }

    @Test
    fun `single round counts files by kind`() {
        val card = DeliveryCardBuilder.build(
            "task-1",
            listOf(
                round(
                    "r1",
                    "a.py" to ChangeKind.A,
                    "b.py" to ChangeKind.M,
                    "c.py" to ChangeKind.D,
                ),
            ),
        )
        assertNotNull(card)
        card!!
        assertEquals(1, card.addedFiles)
        assertEquals(1, card.modifiedFiles)
        assertEquals(1, card.deletedFiles)
        assertEquals(listOf("a.py", "b.py", "c.py"), card.files.map { it.path })
        assertEquals(DeliveryTestVerdict.UNKNOWN, card.verdict)
        assertNull(card.verdictSource)
    }

    @Test
    fun `files are merged across rounds with net kind composition`() {
        // 新增后又改 = 仍是新增；修改后删除 = 删除；删除后重建 = 修改。
        val card = DeliveryCardBuilder.build(
            "task-1",
            listOf(
                round("r1", "a.py" to ChangeKind.A, "b.py" to ChangeKind.M, "d.py" to ChangeKind.D),
                round("r2", "a.py" to ChangeKind.M, "b.py" to ChangeKind.D, "d.py" to ChangeKind.A),
            ),
        )
        assertNotNull(card)
        card!!
        val byPath = card.files.associate { it.path to it.kind }
        assertEquals(ChangeKind.A, byPath["a.py"])
        assertEquals(ChangeKind.D, byPath["b.py"])
        assertEquals(ChangeKind.M, byPath["d.py"])
    }

    @Test
    fun `added then deleted in later rounds leaves no file and no card`() {
        val card = DeliveryCardBuilder.build(
            "task-1",
            listOf(
                round("r1", "tmp.py" to ChangeKind.A),
                round("r2", "tmp.py" to ChangeKind.D),
            ),
        )
        assertNull(card)
    }

    @Test
    fun `composeKind table`() {
        assertEquals(ChangeKind.A, DeliveryCardBuilder.composeKind(ChangeKind.A, ChangeKind.M))
        assertNull(DeliveryCardBuilder.composeKind(ChangeKind.A, ChangeKind.D))
        assertEquals(ChangeKind.D, DeliveryCardBuilder.composeKind(ChangeKind.M, ChangeKind.D))
        assertEquals(ChangeKind.M, DeliveryCardBuilder.composeKind(ChangeKind.D, ChangeKind.A))
        assertEquals(ChangeKind.M, DeliveryCardBuilder.composeKind(ChangeKind.M, ChangeKind.M))
    }

    // -- 交付锚点 -----------------------------------------------------------

    @Test
    fun `anchor is deterministic and changes with content`() {
        val files = listOf(DeliveryFile("a.py", ChangeKind.A))
        val first = DeliveryCardBuilder.anchorSha256("task-1", listOf("r1"), files)
        val second = DeliveryCardBuilder.anchorSha256("task-1", listOf("r1"), files)
        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        val changed = DeliveryCardBuilder.anchorSha256(
            "task-1",
            listOf("r1"),
            listOf(DeliveryFile("a.py", ChangeKind.M)),
        )
        assertTrue(changed != first)
        val otherTask = DeliveryCardBuilder.anchorSha256("task-2", listOf("r1"), files)
        assertTrue(otherTask != first)
    }

    // -- Store --------------------------------------------------------------

    @Test
    fun `empty changeset records nothing`() {
        val store = InMemoryDeliveryCardStore(nowSeconds = { 2000L })
        store.recordRound("task-1", "r1", changeSet("r1"))
        assertNull(store.cardFor("task-1"))
    }

    @Test
    fun `recorded rounds build a card and re-recording a round is idempotent`() {
        val store = InMemoryDeliveryCardStore(nowSeconds = { 2000L })
        store.recordRound("task-1", "r1", changeSet("r1", "a.py" to ChangeKind.A))
        store.recordRound("task-1", "r1", changeSet("r1", "a.py" to ChangeKind.A))
        val card = store.cardFor("task-1")
        assertNotNull(card)
        assertEquals(1, card!!.rounds.size)
        assertEquals(1, card.files.size)
        assertEquals("task-1", card.taskId)
    }

    @Test
    fun `markRound binds by round id and is idempotent`() {
        val store = InMemoryDeliveryCardStore(nowSeconds = { 2000L })
        store.recordRound("task-1", "r1", changeSet("r1", "a.py" to ChangeKind.A))
        assertFalse(store.markRound("task-1", "no-such-round", DeliveryRoundResult.KEPT))
        assertFalse(store.markRound("task-2", "r1", DeliveryRoundResult.KEPT))
        assertTrue(store.markRound("task-1", "r1", DeliveryRoundResult.KEPT))
        assertTrue(store.markRound("task-1", "r1", DeliveryRoundResult.KEPT))
        assertFalse(store.markRound("task-1", "r1", DeliveryRoundResult.UNDONE))
        assertEquals(
            DeliveryRoundResult.KEPT,
            store.cardFor("task-1")!!.rounds.single().result,
        )
    }

    @Test
    fun `different tasks have separate cards`() {
        val store = InMemoryDeliveryCardStore(nowSeconds = { 2000L })
        store.recordRound("task-1", "r1", changeSet("r1", "a.py" to ChangeKind.A))
        store.recordRound("task-2", "r2", changeSet("r2", "b.py" to ChangeKind.M))
        assertEquals(listOf("a.py"), store.cardFor("task-1")!!.files.map { it.path })
        assertEquals(listOf("b.py"), store.cardFor("task-2")!!.files.map { it.path })
        assertNull(store.cardFor("task-3"))
    }

    // -- 视图模型 -----------------------------------------------------------

    @Test
    fun `view model summary counts and verdict unknown is honest`() {
        val card = DeliveryCardBuilder.build(
            "task-1",
            listOf(
                round("r1", "a.py" to ChangeKind.A, "b.py" to ChangeKind.M),
                round("r2", "c.py" to ChangeKind.D),
            ),
        )!!
        val view = DeliveryCardView.build(card, stepsDone = 2, stepsTotal = 3, taskTerminal = true)
        assertEquals("共 3 个文件（新增 1 · 修改 1 · 删除 1），2 个轮次", view.summaryLine)
        assertEquals("步骤 2/3 已完成", view.progressLine)
        assertEquals("测试结论：未验证（暂无测试结果来源）", view.verdictLine)
        assertEquals(listOf("a.py", "b.py", "c.py"), view.files.map { it.path })
        assertEquals(listOf("新增", "修改", "删除"), view.files.map { it.kindLabel })
        assertEquals(2, view.rounds.size)
        assertTrue(view.rounds.all { it.canKeep && it.canUndo })
        assertTrue(view.anchorLine.startsWith("交付锚点："))
        assertTrue(view.anchorLine.contains(card.anchorSha256.take(12)))
        assertTrue(view.noteLine.contains("行数"))
        assertTrue(view.noteLine.contains("只保留在本进程内"))
    }

    @Test
    fun `view model hides keep undo while task is running`() {
        val card = DeliveryCardBuilder.build(
            "task-1",
            listOf(round("r1", "a.py" to ChangeKind.A)),
        )!!
        val view = DeliveryCardView.build(card, stepsDone = null, stepsTotal = null, taskTerminal = false)
        assertNull(view.progressLine)
        assertTrue(view.rounds.all { !it.canKeep && !it.canUndo })
    }

    @Test
    fun `view model verdict with source is shown and without source is not invented`() {
        val passed = DeliveryCardBuilder.build(
            "task-1",
            listOf(round("r1", "a.py" to ChangeKind.A)),
            verdict = DeliveryTestVerdict.PASSED,
            verdictSource = "pytest",
        )!!
        assertEquals(
            "测试结论：通过（来源：pytest）",
            DeliveryCardView.build(passed, null, null, true).verdictLine,
        )
        val failedNoSource = DeliveryCardBuilder.build(
            "task-1",
            listOf(round("r1", "a.py" to ChangeKind.A)),
            verdict = DeliveryTestVerdict.FAILED,
            verdictSource = null,
        )!!
        assertEquals(
            "测试结论：未通过（来源：未记录）",
            DeliveryCardView.build(failedNoSource, null, null, true).verdictLine,
        )
    }

    @Test
    fun `view model round result hides buttons after keep or undo`() {
        val store = InMemoryDeliveryCardStore(nowSeconds = { 2000L })
        store.recordRound("task-1", "r1", changeSet("r1", "a.py" to ChangeKind.A))
        store.markRound("task-1", "r1", DeliveryRoundResult.UNDONE)
        val view = DeliveryCardView.build(store.cardFor("task-1")!!, null, null, true)
        assertEquals("已撤销", view.rounds.single().resultLabel)
        assertFalse(view.rounds.single().canKeep)
        assertFalse(view.rounds.single().canUndo)
    }
}
