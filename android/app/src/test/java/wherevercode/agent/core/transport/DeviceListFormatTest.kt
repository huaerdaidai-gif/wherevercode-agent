package wherevercode.agent.core.transport

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 最后在线的相对时间格式。时间点用 Calendar 在本机时区构造，
 * 与实现同一口径，不依赖测试机的时区与墙钟。
 */
class DeviceListFormatTest {

    private fun epoch(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000
    }

    @Test
    fun `never seen shows never online`() {
        assertEquals("从未在线", DeviceListFormat.formatLastSeen(0, 1794000000))
    }

    @Test
    fun `just now under a minute`() {
        val now = epoch(2026, 11, 11, 10, 30)
        assertEquals("刚刚", DeviceListFormat.formatLastSeen(now - 30, now))
    }

    @Test
    fun `minutes ago`() {
        val now = epoch(2026, 11, 11, 10, 30)
        assertEquals("25 分钟前", DeviceListFormat.formatLastSeen(now - 25 * 60, now))
    }

    @Test
    fun `today shows hour and minute`() {
        val now = epoch(2026, 11, 11, 10, 30)
        assertEquals("今天 09:15", DeviceListFormat.formatLastSeen(epoch(2026, 11, 11, 9, 15), now))
    }

    @Test
    fun `yesterday shows yesterday`() {
        val now = epoch(2026, 11, 11, 10, 30)
        assertEquals("昨天 22:05", DeviceListFormat.formatLastSeen(epoch(2026, 11, 10, 22, 5), now))
    }

    @Test
    fun `days ago under thirty`() {
        val now = epoch(2026, 11, 11, 10, 30)
        assertEquals("5 天前", DeviceListFormat.formatLastSeen(now - 5 * 86400, now))
    }

    @Test
    fun `older than thirty days shows date`() {
        val now = epoch(2026, 11, 11, 10, 30)
        assertEquals("2026-10-02", DeviceListFormat.formatLastSeen(epoch(2026, 10, 2, 8, 0), now))
    }

    @Test
    fun `future last seen is just now not negative`() {
        val now = epoch(2026, 11, 11, 10, 30)
        assertEquals("刚刚", DeviceListFormat.formatLastSeen(now + 60, now))
    }
}
