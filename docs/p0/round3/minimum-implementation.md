# P0 第二轮：三个最小闭环方案

已参考 design-sources.md 中的下一阶段规格。S16/S17 沿用上一轮能力编号，非 Claude C16/C17。

本文件是实施方案，不是功能完成声明。当前 S16/S17 产品仍 MISSING，九类 manifest 的执行激活仍 MISSING。先复用既有 TaskStore、TaskScheduler、ControlPlane、AndroidApprovalGate、ExecutionFence 和 ExtensionToolBridge，不改为另一个任务系统。供应商不决定角色。

## S16 自动化规则引擎

首个切片：用户在原生规则页创建「一次定时 → 为已授权本地项目创建一个任务」；默认停用，预览目标/执行者/权限并由用户启用。先支持本地 runner；多成员继续使用既有原子认领，不新建并行执行器。先不承诺秒级或精确定时。

新增 Room 表：`automation_rule(id, version, enabled, triggerType, nextRunAt, projectId, agentId, goal, authEpoch, maxRuns)` 与 `automation_run(id, ruleId, ruleVersion, eventId, taskId, state, retryAt, error)`；唯一键 `(ruleId, ruleVersion, eventId)`。调度器使用 WorkManager（需增加依赖）；系统可能延迟调度，显示预计时间。启动时重扫到期规则，事务写触发账本与真实 queued 任务，不能以频道消息替代。TaskStore 需增加可在同一数据库事务内调用的创建口，不在两个独立写入间声称 exactly-once。

首版预算用用户批准的最大运行次数；预留一次额度与建任务在同一事务，未成功创建不得扣额。金额/token 预算在可提供用量回执前标 MISSING。授权 epoch 变化、项目解绑或写权限不足时 blocked，用户重新审批后恢复。禁用规则只停止未来触发；已建任务须明确列出并通过 ControlPlane 停止。

闭环验收：创建一次规则 → 到期只建一个任务 → runner 认领 → 工具写入弹审批 → 拒绝不写/批准写沙箱 → 结果可查；在触发事务前后杀进程、重复唤醒、重启、预算耗尽、旧 epoch 分别回归。验收前不得标 VERIFIED。

后续顺序：持久化事件 inbox → 事件规则；再加周期规则；最后加入被动/主动唤醒。重试只重试投递，不偷偷重跑有副作用的任务。每次触发记录独立 eventId、截止时间与有界退避；返工仍走现有 pipeline。

## S17 多网络与耐久离线队列

首个切片：一个用户明确配置并配对的 Hand 端点 + 本地路由 + 可查看/取消的离线任务意图。LAN/VPN 使用同一受认证端点；VPN 由系统/用户管理，不新增 VPNService。规格目标为 HTTPS/WSS/TLS；现有 Direct/LAN Hand 实际为密封信封上的 TCP，不能标作 TLS。HTTPS 中继在实现与验证前维持 MISSING，不能在直连失败时自动转向未知服务。

新增 Room `dispatch_outbox(id, taskId, operationId, attemptId, endpointId, goalVersion, authEpoch, expiresAt, state, nextAttemptAt, retryCount, receipt)`；保存意图与 taskId，不保存令牌/私钥。幂等键稳定，凭据在投递时从受保护存储按当前授权签发。任务创建与 outbox 入队必须同事务。网络恢复只触发重扫，不能把「可联网」等同「设备在线」。

接收端需要耐久投递账本，以 `(sourceDeviceId, operationId)` 去重并返回相同 taskId/attemptId；先验签、配对、scope、epoch、goalVersion 与截止时间，再认领。发送后无回执用 UNKNOWN_DELIVERY，先查询接收端账本，不直接重放执行。权限改变/过期进入 NEEDS_APPROVAL/EXPIRED。仅收到执行端确认标 DELIVERED，任务完成仍需执行结果；二者不能混用。

控制命令不得排在普通离线工作之后。用户离线取消意图可阻止未投递工作；可能已送达的工作保持 STOPPING，保留证据至真实停止回执。路由切换保留相同幂等身份，不能产生第二次执行。

闭环验收：断网创建 → 进程重启仍待投递 → 恢复连接 → Hand 只认领一次 → 丢响应/重连仍不重复 → 成果入库；另测过期、撤权、用户取消、错误证书/配对失败、回执迟到。UI 显示最后成功时间、当前路径、队列原因和重试截止时间。

## 扩展执行器

现状：九类 ExtensionManifest 仅为独立元数据目录；七类 ExtensionRegistry 中只有内置 plugin 和 MCP 经 Bridge 提供运行工具。导入清单不能产生可执行代码或授权。

首个切片：只激活 APK 已编译的 `LocalUtilsPlugin` 和用户显式配置的 MCP 服务。新增 `ExtensionActivationStore`，记录 manifest id/version、绑定的已知 runtime id、用户批准权限、授权版本和 enabled。原生权限预览 → 用户确认 → 验证已知绑定与权限子集 → 激活 → 枚举工具 → BrainEngine 调用 → 审批/Fence → 结果/审计 → 停用后拒绝调用。manifest 不增加任意 shell/下载入口；连接凭据只进入既有 secret store。

MCP 服务初始化、tools/list 与根目录配置成功后才显示可用；失败保留原因。动态声明未知工具的 readOnly 必须默认 false；用户授予清单权限也不绕过逐次写审批。禁用后对新调用立即生效，活动调用走取消/停止回执，不能用注册表开关冒充进程已经停止。

闭环验收：合法清单绑定内置 calc 成功；未知 runtime 拒绝；只读调用返回真实结果；写工具必须弹审批；拒绝不执行；停用拒绝；重启恢复激活状态但重新验证权限。Harness/Agent/Model/Skills/Automation/Network/Device/UI 动态执行适配留待独立接口与测试，不一次性放开任意外来代码。
