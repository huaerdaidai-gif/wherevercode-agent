# Android / AGY iOS 对齐与剩余差异

参考固定为 `feat/v5-ios-1.5-build6-import-20261115@062fb33962d5b9093f0d96476697ac9c61b2c062`，不是对后续分支头的验收；未修改 iOS 文件。

- 70 个路由 ID、名称与四父级归属逐项比较：**0 差异**，见 ios-reference.json。控制台与其三级入口完整保留，无底部 TabBar。
- 搜索：Android 本批搜索现有会话正文、任务结果/错误与项目元数据，结果使用实体真实 ID；iOS 参考版本主要检索标题，并有部分列表落点。两端须明确“本地搜索”范围，云端/文件全文索引均不能据此标完成。
- 记忆：Android 用同一个 MemoryStore 接口与实例服务 BrainEngine / UI，本机持久化，事实二次确认、敏感内容拒绝、删除确认、存储异常不返回成功。iOS 参考页直接调用 confirmedByUser=true；后续应统一确认与错误反馈语义。设备备注不是配对授权，不得用删除记忆代替撤销权限。
- 技能：Android 复用既有 Room SkillStore；人工记录 successCount=0、failureCount=0、evidenceTaskIds=[]。两端均须区分人工记录、经执行验证的技能、规则自动化和市场安装，不能混为已执行。
- 控制：保持 queued/assigned/working/blocked/review/rework/requeued/completed/failed/cancelled 规范状态；pause/resume/stop/change-goal 使用现有独立控制面。暂停不等于取消，离线不等于停止，DELIVERED 不等于 CLAIMED / 执行成功。
- 安全：本批移植 PR #13 及二轮候选 Android 安全代码并额外修正锁透传与审批后暂停检查，不代表 PR #13 已合并；iOS 是否具备等价系统 CA+pin、密钥存储、路径与发送者检查需要其自身实现证据。
- 平台：iOS 对本地 CLI 的 UNSUPPORTED 不能直接套到 Android；Hand/Termux/外部 Agent 必须逐项检测并验收。未测模型/设备能力保持未验证。

本批没有新增服务端接口或修改共享 API DTO。规则服务、耐久触发/唤醒账本、成本账本、跨端同步冲突/离线发件箱等仍有后端缺口，沿用 BLOCKERS-AND-IOS.md 与 gap-70.csv；不伪造 endpoint 或远程成功回执。

验收表统一用五态：已完成并验证 / 已实现但未验证 / 接线待完成 / 仅设计 / 当前不支持。Android 现有 FeatureState 的 PLANNED 对应 DESIGN_ONLY，DISCONNECTED 对应 ADAPTER_PENDING，READY_VERIFIED / IMPLEMENTED_UNVERIFIED / UNSUPPORTED 保持原义；FAILED 是运行时错误，应与功能验收类别分开。当前没有证据能把任何整屏标成全量 READY_VERIFIED，局部通过写入 verified_operations，不改变原共享契约。
