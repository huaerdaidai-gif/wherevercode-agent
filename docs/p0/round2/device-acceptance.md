# Redmi K70 验收：NOT_RUN

本次云环境的 `adb devices -l` 只返回 `List of devices attached`，无设备行。未取得 USB 转发、无线 ADB 地址/配对或远程设备入口。不能安装、启动或代用户批准真实工具写入，真机截图数为 0。没有用 HTML/模拟器/Robolectric 截图替代。

取得连接后先执行：

```bash
ADB_BIN=/path/to/adb AAPT_BIN=/path/to/aapt \
  android/tools/verify-k70-debug.sh SERIAL /path/to/app-debug.apk /private/evidence/k70
```

脚本只接受 `.ci` 包与 K70 型号，保存真实安装/启动/包元数据/截图/应用日志；不卸载、不清数据、不操作原包、不自动批准写入。签名不兼容须保留失败记录，不通过卸载来强行安装。安装/启动成功仍不是以下验收通过。

| 场景 | 必留证据 | 本轮 |
|---|---|---|
| 包身份与启动 | APK hash、签名、型号/系统、install、am start -W、启动截图、崩溃日志 | NOT_RUN |
| C01–C18 导航 | 原屏编号、入口、返回、空/加载/错误/离线、深浅主题、390 比例与系统字体 | NOT_RUN |
| 本地真实任务 | 聊天明确任务 → sessionId=null 任务 → attempt/lease 认领 → 沙箱修改 → 真实测试/结果 | NOT_RUN |
| 远端真实任务 | 配对已确认、scope、taskId/attemptId、进度/result 同一归属 | NOT_RUN |
| 暂停/继续 | 请求前/待回执/确认后截图，服务端进程与 task/control 事件对应 | NOT_RUN |
| 停止/删除 | 断网停留 STOPPING；回执前记录保留；确认后允许删除 | NOT_RUN |
| 改目标 | queued goalVersion 增长；活动任务先停止确认后新建；旧回执不得更新新轮次 | NOT_RUN |
| 写审批 | 拒绝不写、批准一次后写、过期/撤权后拒绝、拒绝后任务状态与日志 | NOT_RUN |
| 离线与失败 | 真实断连、错误凭据、缺 CLI、不可送达；保留失败和最后更新时间 | NOT_RUN |
| 旋转/重启 | 不重复发任务；恢复真实账本/上下文；未确认控制不变成已停止 | NOT_RUN |

本地隔离临时项目只创建用于验收的文件；不使用生产端点。日志按 taskId/operationId/时间关联，发出回执不是收到回执。原始 logcat 可能含用户输入、端点与令牌，留在私有证据目录，脱敏后分享。S16/S17 现为缺失能力，不能通过一次在线派工来验收它们。
