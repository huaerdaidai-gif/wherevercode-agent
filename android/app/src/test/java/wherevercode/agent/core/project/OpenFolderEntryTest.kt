package wherevercode.agent.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * “打开文件夹”唯一入口的单子（2026-11-12，maintainer：项目来源收成一个）：
 * 一个入口、一个对话框、一张单子，按位置种类列三项，每项有名 + 一行说明。
 * 老项目 8 个选项按种类收进这三类，不照搬 8 个并列。
 */
class OpenFolderEntryTest {

    @Test
    fun `exactly three location kinds in order`() {
        val options = openFolderEntryOptions()
        assertEquals(3, options.size)
        assertEquals(
            listOf(
                OpenFolderLocationKind.LOCAL,
                OpenFolderLocationKind.REMOTE,
                OpenFolderLocationKind.GIT,
            ),
            options.map { it.kind },
        )
    }

    @Test
    fun `each option has a name`() {
        assertEquals(
            listOf("本机文件夹", "远程设备上的文件夹", "git 仓库地址"),
            openFolderEntryOptions().map { it.name },
        )
    }

    @Test
    fun `each option has exactly one description line`() {
        for (option in openFolderEntryOptions()) {
            assertTrue(option.description.isNotBlank())
            assertTrue(option.description.none { it == '\n' })
        }
    }

    @Test
    fun `local description folds mounted and synced folders`() {
        val local = openFolderEntryOptions().first { it.kind == OpenFolderLocationKind.LOCAL }
        assertTrue(local.description.contains("挂载"))
        assertTrue(local.description.contains("网盘"))
    }

    @Test
    fun `remote description folds lan devices`() {
        val remote = openFolderEntryOptions().first { it.kind == OpenFolderLocationKind.REMOTE }
        assertTrue(remote.description.contains("局域网"))
        assertTrue(remote.description.contains("已配对设备"))
    }

    @Test
    fun `git description folds all git address kinds`() {
        val git = openFolderEntryOptions().first { it.kind == OpenFolderLocationKind.GIT }
        assertTrue(git.description.contains("GitHub"))
        assertTrue(git.description.contains("GitLab"))
        assertTrue(git.description.contains("Gitee"))
        assertTrue(git.description.contains("Hugging Face"))
        assertTrue(git.description.contains("Git URL"))
        assertTrue(git.description.contains("不拉代码"))
    }

    @Test
    fun `every option maps to an available registered project source`() {
        val options = openFolderEntryOptions()
        assertEquals(
            listOf("local_folder", "device_folder", "git_host"),
            options.map { it.sourceId },
        )
        for (option in options) {
            val source = ProjectSourceRegistry.find(option.sourceId)
            assertNotNull("未注册的来源：${option.sourceId}", source)
            assertTrue(source!!.available)
            // 标题唯一事实来源是注册表：选项名必须等于注册表条目标题
            assertEquals(source.title, option.name)
        }
        // 尚不可用的自定义来源不进单子
        assertTrue(options.none { it.sourceId == "custom" })
    }
}
