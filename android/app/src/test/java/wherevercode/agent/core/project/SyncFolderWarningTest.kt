package wherevercode.agent.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网盘同步目录警告（2026-11-11，项目来源调研第三步）：
 * `.git` 落在网盘同步目录里会被同步损坏——只警告，不拦截。
 * 判定是启发式的（路径段里出现网盘客户端的同步文件夹名）。
 */
class SyncFolderWarningTest {

    @Test
    fun `onedrive dropbox google drive paths are detected`() {
        assertEquals("OneDrive", detectSyncedFolder("/Users/example-user/OneDrive/work/app"))
        assertEquals("OneDrive", detectSyncedFolder("/Users/example-user/OneDrive - Personal/work"))
        assertEquals("Dropbox", detectSyncedFolder("/Users/example-user/Dropbox/projects/x"))
        assertEquals("Google Drive", detectSyncedFolder("/Users/example-user/Google Drive/code/x"))
        assertEquals("Google Drive", detectSyncedFolder("/Users/example-user/My Drive/code"))
    }

    @Test
    fun `icloud and chinese clients are detected`() {
        assertEquals("iCloud", detectSyncedFolder("/Users/example-user/Library/CloudStorage/iCloud Drive/x"))
        assertEquals("iCloud", detectSyncedFolder("/Users/example-user/iCloud Drive/x"))
        assertEquals("坚果云", detectSyncedFolder("/Users/example-user/坚果云/code"))
        assertEquals("百度网盘", detectSyncedFolder("/Users/example-user/百度网盘/code"))
    }

    @Test
    fun `plain folders are not flagged`() {
        assertNull(detectSyncedFolder("/workspace/example-project"))
        assertNull(detectSyncedFolder("/Users/example-user/Projects/wherevercode-agent"))
        assertNull(detectSyncedFolder("/Users/example-user/Drive-through/cafe"))
        assertNull(detectSyncedFolder("/data/data/wherevercode.agent/files/workspace/my-project"))
        assertNull(detectSyncedFolder(""))
    }

    @Test
    fun `warning text names the client and does not block`() {
        val warning = syncFolderWarning("/Users/example-user/Dropbox/projects/x")
        assertTrue(warning != null)
        assertTrue(warning!!.contains("Dropbox"))
        assertTrue(warning.contains(".git"))
        assertNull(syncFolderWarning("/workspace/example-project"))
    }
}
