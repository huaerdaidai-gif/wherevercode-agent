# 第二轮设计来源

2026-11-14 通过已连接 Google Drive 只读找到并下载：

| 输入 | 来源 | SHA-256 |
|---|---|---|
| Claude 原始 18 屏整理版 | https://drive.google.com/file/d/1Ppwk89ULB983rPV8mmL0PBufMQkbHkUB/view | d77b4d9dc7c24d4249b455cd71d290226cd677b71905e58c29dffb6389a8e01e |
| 旗舰 UI HTML（脚本实含 70 个 pages 条目，页脚仍写 60） | https://drive.google.com/file/d/1i4J4IpTw4ZT70b0aZdwlAbqRopHPyCzi/view | 76cc2603006caa0e49e8c06c60838ca26daa235f38c66fbc2111fd21ec670ae8 |
| 下一阶段自动化/网络架构规格 | https://drive.google.com/file/d/10M4G_miZh7pinnYQSJGPyiKDzMuqjf3n/view | 421081178e450515dcadda5e1b7ce661379ef9cf1c2caa3c75bbeb6f06bf759d |

原始准备包 ZIP、精确文件名 `claude_reference.html`、`prototype.html` 及其原有 UI 映射表仍未定位。因此本轮对照是以上可确认来源的重新提取，不宣称读到了未取得的准备包或其映射表。

Claude 整理版是双层 HTML bundler：外层 18 个 board，内层资源 manifest gzip/base64。以外层 `#screen-1` 至 `#screen-18` 的导航和 iframe 顺序为屏标识；不能以每个内层 `<title>` 为准（首页、抽屉、执行和任务列表内层 title 均误写「设置中心」）。已解包逐项读取内层模板与组件内容。

本轮使用独立 Chromium 390×844 渲染解包页和原始嵌套页，内容可见，但运行时共享样式未正常呈现。因此这些截图只用于输入诊断，不是可信视觉金样，更不是 Android 截图。视觉一致性仍 UNVERIFIED，需准备包的可正常渲染原稿与 K70 同尺寸截图。

`acceptance-18.csv` 的 C01–C18 对应 Claude 原屏；`prototype-70-pages.csv` 对应旗舰脚本的真实 70 页。`wiring-70.csv` 保留上一轮 70 个操作入口，S01–S18 仍是上一轮自拟架构 ID，不能用它冒充原稿页名。方案里的 S16 自动化/S17 网络指该自拟能力编号；Claude C16 是异常状态、C17 是首次欢迎。

本轮不把完整私有设计文件复制到公开源码仓库；来源、内容摘要和差异表足以复核。
