package wherevercode.agent.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5 项目添加（2026-11-11）：Hand list 回包解析与文件夹路径工具。
 *
 * 服务端 `server/hand_server.py` 的 `_do_list` 回包：
 * `cross_device_result`，output 为 JSON 数组 `[{name, is_dir}]`。
 */
class RemoteFolderParseTest {

    @Test
    fun `parse folder list json`() {
        val entries = parseFolderListJson(
            """[{"name":"fenshen","is_dir":true},{"name":"README.md","is_dir":false}]""",
        )
        assertEquals(2, entries.size)
        assertEquals(RemoteFolderEntry("fenshen", true), entries[0])
        assertEquals(RemoteFolderEntry("README.md", false), entries[1])
    }

    @Test
    fun `parse empty output is empty list`() {
        assertTrue(parseFolderListJson("").isEmpty())
        assertTrue(parseFolderListJson("   ").isEmpty())
        assertTrue(parseFolderListJson("[]").isEmpty())
    }

    @Test
    fun `parse bad json throws`() {
        try {
            parseFolderListJson("not json")
            org.junit.Assert.fail("非法 JSON 应抛异常")
        } catch (_: IllegalArgumentException) {
            // 预期。
        }
    }

    @Test
    fun `parse skips blank names but keeps the rest`() {
        val entries = parseFolderListJson("""[{"name":"","is_dir":true},{"name":"ok","is_dir":true}]""")
        assertEquals(1, entries.size)
        assertEquals("ok", entries[0].name)
    }

    @Test
    fun `folder basename`() {
        assertEquals("example-project", folderBasename("/workspace/example-project"))
        assertEquals("example-project", folderBasename("/workspace/example-project/"))
        assertEquals("/", folderBasename("/"))
        assertEquals("workspace", folderBasename("/workspace"))
    }

    @Test
    fun `join folder path`() {
        assertEquals("/workspace/fenshen", joinFolderPath("/workspace", "fenshen"))
        assertEquals("/workspace", joinFolderPath("/workspace", ""))
        assertEquals("/workspace", joinFolderPath("/workspace/", "/"))
        assertEquals("/a/b/c", joinFolderPath("/a", "b/c"))
        assertEquals("/", joinFolderPath("/", ""))
    }

    @Test
    fun `is absolute folder path`() {
        assertTrue(isAbsoluteFolderPath("/workspace"))
        assertTrue(isAbsoluteFolderPath("  /workspace  "))
        assertFalse(isAbsoluteFolderPath("root/work"))
        assertFalse(isAbsoluteFolderPath(""))
    }

    @Test
    fun `resolve project relative path`() {
        assertEquals("", resolveProjectRelativePath("/workspace/example-project", "/workspace/example-project"))
        assertEquals(
            "sub/project",
            resolveProjectRelativePath("/workspace/example-project", "/workspace/example-project/sub/project"),
        )
        assertEquals(
            "",
            resolveProjectRelativePath("/workspace/example-project/", "/workspace/example-project"),
        )
        // 不在 Agent 文件夹之下：Hand 会拒绝（凭证 folder 须在授权列表里）。
        assertNull(resolveProjectRelativePath("/workspace/example-project", "/opt/dsh-projects/x"))
        assertNull(resolveProjectRelativePath("/workspace/example-project", "/workspace/example-project2"))
    }
}
