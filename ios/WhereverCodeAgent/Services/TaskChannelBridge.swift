// WhereverCode Agent — iOS
//
// 任务派工的事件写回口（spec §5）：账本事件反向写成频道消息（带
// task_id 引用）。实现由 LocalChannelService 提供（W8 接线，以
// App 身份发帖，正文末尾带 [task:<id>] 引用行）。

import Foundation

public protocol TaskChannelBridge: AnyObject {
    func postTaskEvent(channelId: String, taskId: String, kind: String, text: String)
}

/// 弱引用桥：LocalChannelService 持有 TaskDispatchService、
/// 服务又持有 bridge，强引用会成环；目标随 LCS 生命周期走。
final class WeakTaskBridge: TaskChannelBridge {
    weak var target: LocalChannelService?

    func postTaskEvent(channelId: String, taskId: String, kind: String, text: String) {
        target?.postTaskEventMessage(channelId: channelId, taskId: taskId, text: text)
    }
}
