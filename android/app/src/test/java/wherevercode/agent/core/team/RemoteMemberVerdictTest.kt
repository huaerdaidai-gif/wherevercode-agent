package wherevercode.agent.core.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端成员汇报分类器（2026-11-11，任务 692638e2 教训）。
 *
 * 只认开头陈述：首个非空行、前 120 字内的显式失败表述。
 * 失败写在第三段以后的长汇报会误判成功——这是有意保守：
 * 宁可把失败误判成功、走 review 门让人看，也不把成功误判失败。
 */
class RemoteMemberVerdictTest {

    @Test
    fun `real 692638e2 report starting with failure is Failed`() {
        val output = "未能完成操作。实际仓库在 `/Users/example-user/Projects/wherevercode-agent`，" +
            "本地只有 `feat/android-ui-v2-20261109` 分支，没有 `main` 或其他默认分支的本地 ref；" +
            "尝试连接远程以确认默认分支时，因无法解析 `github.com` 失败。" +
            "创建分支时也遇到 `.git/index.lock` 写入被拒绝。"
        val verdict = classifyRemoteMemberOutput(output)
        assertTrue(verdict is RemoteMemberVerdict.Failed)
        assertEquals(output, (verdict as RemoteMemberVerdict.Failed).reason)
    }

    @Test
    fun `successful report is Success`() {
        val verdict = classifyRemoteMemberOutput(
            "已完成：分支 test/team-check-20261111 已提交，提交号 abc123def456。",
        )
        assertEquals(RemoteMemberVerdict.Success, verdict)
    }

    @Test
    fun `english failure markers are recognised`() {
        assertTrue(
            classifyRemoteMemberOutput(
                "Failed to create branch test/x: permission denied.",
            ) is RemoteMemberVerdict.Failed,
        )
        assertTrue(
            classifyRemoteMemberOutput(
                "Could not complete the task: network unreachable.",
            ) is RemoteMemberVerdict.Failed,
        )
        assertTrue(
            classifyRemoteMemberOutput(
                "Task failed: the build did not pass.",
            ) is RemoteMemberVerdict.Failed,
        )
    }

    @Test
    fun `english success report is Success`() {
        assertEquals(
            RemoteMemberVerdict.Success,
            classifyRemoteMemberOutput("Done: branch created and pushed."),
        )
    }

    @Test
    fun `failure only in a later paragraph is conservatively Success`() {
        // 有意保守：只认开头陈述；失败写在第三段以后的长汇报会误判成功。
        // 持久解法是 Hand 的 result 帧加 verdict/exit_code 字段。
        val output = "已完成：第一步已提交。\n\n第二步也已完成。\n\n但第三步未能完成操作：磁盘已满。"
        assertEquals(RemoteMemberVerdict.Success, classifyRemoteMemberOutput(output))
    }

    @Test
    fun `blank output is Failed with the empty-output reason`() {
        val verdict = classifyRemoteMemberOutput("   \n  ")
        assertTrue(verdict is RemoteMemberVerdict.Failed)
        assertEquals(
            RemoteMemberVerdictClassifier.EMPTY_OUTPUT_REASON,
            (verdict as RemoteMemberVerdict.Failed).reason,
        )
    }

    @Test
    fun `leading markdown and quote markers are stripped before matching`() {
        val verdict = classifyRemoteMemberOutput(
            "> **未能完成操作**：磁盘空间不足。",
        )
        assertTrue(verdict is RemoteMemberVerdict.Failed)
    }

    @Test
    fun `failure marker beyond the first 120 chars of the headline is ignored`() {
        val prefix = "已完成前置检查，" + "一切正常。".repeat(30)
        val output = "$prefix 未能完成操作。"
        assertEquals(RemoteMemberVerdict.Success, classifyRemoteMemberOutput(output))
    }

    @Test
    fun `real 4f3b597c retry report saying repo not found is Failed`() {
        // 2026-11-12：重试任务 4f3b597c-ae0d（父 692638e2）的 result 全文。
        // 旧分类器认不出“没有找到/尚未创建/请提供”，记成 completed（假完成）。
        val output = "在当前工作区 `/Users/example-user/v5-codex-work` 中没有找到 `wherevercode-agent` 仓库或 Git 元数据，" +
            "因此尚未创建分支、文件或提交。请提供该仓库在此环境中的路径；拿到路径后我就能继续完成，并报告分支名和完整提交号。"
        val verdict = classifyRemoteMemberOutput(output)
        assertTrue(verdict is RemoteMemberVerdict.Failed)
        assertEquals(output, (verdict as RemoteMemberVerdict.Failed).reason)
    }

    @Test
    fun `need-info variants are Failed`() {
        val cases = listOf(
            "找不到仓库，请提供路径。",
            "尚未创建分支，缺少仓库路径。",
            "这个活我做不了，没有仓库地址。",
            "缺少仓库路径，无法继续。",
            "在当前工作区中没有找到 wherevercode-agent 仓库，请提供该仓库在此环境中的路径。",
            "Could not find the repository, please provide the path.",
            "Repository not found: no such repo on this device.",
            "Not created yet: the branch does not exist.",
            "I need the repository path to proceed.",
        )
        for (text in cases) {
            assertTrue("expected Failed: $text", classifyRemoteMemberOutput(text) is RemoteMemberVerdict.Failed)
        }
    }

    @Test
    fun `success reports mentioning no problems are not Failed`() {
        // 误判护栏：宾语只认工作对象，“没有发现问题”“没有找到回归”不命中。
        assertEquals(RemoteMemberVerdict.Success, classifyRemoteMemberOutput("已完成检查，没有发现问题。"))
        assertEquals(RemoteMemberVerdict.Success, classifyRemoteMemberOutput("测试全部通过，没有找到回归问题。"))
        assertEquals(RemoteMemberVerdict.Success, classifyRemoteMemberOutput("已完成，已创建分支 test/x，提交号 abc123。"))
        assertEquals(RemoteMemberVerdict.Success, classifyRemoteMemberOutput("Done: branch created and pushed."))
    }

    @Test
    fun `structured JSON headlines are not judged as prose`() {
        // plan/verdict JSON 的合法性由解析器定；首行含“尚未创建”也不抢判。
        val plan = """{"goal": "修复尚未创建分支的问题", "steps": []}"""
        assertEquals(RemoteMemberVerdict.Success, classifyRemoteMemberOutput(plan))
        val fenced = "```json\n{\"verdict\": \"approve\"}\n```"
        assertEquals(RemoteMemberVerdict.Success, classifyRemoteMemberOutput(fenced))
    }

    @Test
    fun `markdown-stripped need-info is Failed`() {
        assertTrue(
            classifyRemoteMemberOutput("> **没有找到仓库**：请提供路径。") is RemoteMemberVerdict.Failed,
        )
    }
}
