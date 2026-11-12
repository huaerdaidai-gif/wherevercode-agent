package wherevercode.agent.core.security

import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.task.TaskDatabase

/**
 * 根因回归单测（V5 修复，2026-11-11）。
 *
 * 生产用的 `TaskDatabase.create()` 默认不开 `allowMainThreadQueries`，
 * Room 会在 UI 线程的任何 DAO 调用上抛 `IllegalStateException`。
 * 旧 `MainActivity.resolveRemoteTarget` 在 UI 线程直读配对库，
 * 异常被 catch 吞成“未配对”——同一台设备，测试（后台线程）说可用、
 * 对话（UI 线程）说未配对。本测试把这条禁令钉死：以后谁再把 Room
 * 读放回 UI 线程的判定路径，测试先红。修复方向是内存快照
 * （[PairingSnapshotCache]），不是给生产库开主线程查询。
 */
@RunWith(RobolectricTestRunner::class)
class PairingRoomMainThreadTest {
    @Test
    fun `file-based database forbids main-thread dao access`() {
        val db = TaskDatabase.create(
            RuntimeEnvironment.getApplication(),
            "pairing-main-thread-test.db",
        )
        try {
            try {
                db.pairingDao().load("remote-device")
                fail("expected IllegalStateException on a main-thread DAO call")
            } catch (e: IllegalStateException) {
                // 预期：Room 拒绝主线程查询。
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `in-memory test database allows main-thread dao access`() {
        val db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication())
        try {
            assertNull(db.pairingDao().load("remote-device"))
        } finally {
            db.close()
        }
    }
}
