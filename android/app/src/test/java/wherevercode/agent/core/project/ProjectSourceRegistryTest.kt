package wherevercode.agent.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 项目来源注册表（70 屏补漏，2026-11-15）：来源类型可扩展、不写死厂商；
 * 入口仍只有一个「打开文件夹」。本轮只验注册表本身，接线待下一轮。
 */
class ProjectSourceRegistryTest {

    @Test
    fun `source ids are unique`() {
        val ids = ProjectSourceRegistry.sources().map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `builtin sources are all present`() {
        val ids = ProjectSourceRegistry.sources().map { it.id }
        assertTrue(ids.contains("local_folder"))
        assertTrue(ids.contains("device_folder"))
        assertTrue(ids.contains("git_host"))
        assertTrue(ids.contains("drive_sync"))
        assertTrue(ids.contains("nas_mount"))
        assertTrue(ids.contains("custom"))
    }

    @Test
    fun `folder-like sources fold into folder flows`() {
        assertTrue(ProjectSourceRegistry.find("drive_sync")!!.note.contains("本机文件夹"))
        assertTrue(ProjectSourceRegistry.find("nas_mount")!!.note.contains("设备文件夹"))
        assertTrue(ProjectSourceRegistry.find("git_host")!!.note.contains("不写死名单"))
    }

    @Test
    fun `custom source is honestly unavailable`() {
        val custom = ProjectSourceRegistry.find("custom")!!
        assertFalse(custom.available)
        assertTrue(custom.note.contains("待开发"))
    }

    @Test
    fun `register adds a new source that find can locate`() {
        val source = ProjectSource("test_source", "测试来源", true, "单测注册项。")
        assertTrue(ProjectSourceRegistry.register(source))
        assertNotNull(ProjectSourceRegistry.find("test_source"))
        assertEquals("测试来源", ProjectSourceRegistry.find("test_source")!!.title)
        // 同 id 重复注册被拒，不产生重复项
        assertFalse(ProjectSourceRegistry.register(source))
        assertNull(ProjectSourceRegistry.find("no_such_source"))
    }
}
