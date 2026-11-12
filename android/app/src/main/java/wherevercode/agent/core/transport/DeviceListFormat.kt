package wherevercode.agent.core.transport

import java.util.Calendar

/**
 * 设备列表的显示格式（纯函数，可单测）。
 *
 * 最后在线：只说真实发生过的事——最后一次 hello 成功的时间。
 * 从没有 hello 成功过就写“从未在线”，绝不拿添加时间冒充在线时间。
 * 在线/离线没有心跳探测，所以这里不写“在线”：只给最后在线时间，
 * 由用户自己看它是不是 stale；真在线态见后续（hello 探测缓存）。
 */
object DeviceListFormat {

    /**
     * 相对时间：刚刚 / N 分钟前 / 今天 HH:mm / 昨天 HH:mm / N 天前 /
     * YYYY-MM-DD / 从未在线。入参都是 epoch 秒（UTC），显示用本机时区。
     */
    fun formatLastSeen(lastSeenEpochSeconds: Long, nowEpochSeconds: Long): String {
        if (lastSeenEpochSeconds <= 0) return "从未在线"
        val diff = nowEpochSeconds - lastSeenEpochSeconds
        if (diff < 60) return "刚刚"
        if (diff < 3600) return "${diff / 60} 分钟前"
        val last = Calendar.getInstance().apply { timeInMillis = lastSeenEpochSeconds * 1000 }
        val now = Calendar.getInstance().apply { timeInMillis = nowEpochSeconds * 1000 }
        val hm = "%02d:%02d".format(
            last.get(Calendar.HOUR_OF_DAY),
            last.get(Calendar.MINUTE),
        )
        val sameDay = last.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            last.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return "今天 $hm"
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = nowEpochSeconds * 1000
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val isYesterday = last.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
            last.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
        if (isYesterday) return "昨天 $hm"
        val days = diff / 86400
        if (days < 30) return "$days 天前"
        return "%04d-%02d-%02d".format(
            last.get(Calendar.YEAR),
            last.get(Calendar.MONTH) + 1,
            last.get(Calendar.DAY_OF_MONTH),
        )
    }
}
