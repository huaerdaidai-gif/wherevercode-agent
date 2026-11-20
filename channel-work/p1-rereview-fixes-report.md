# 一期复审残留修复

- 基线 `feat/channel-phase1-20261120 @ 43e7cef30ad85db8f54df3027978e7e2365933fa`。未改该分支，未合 main。
- 交付分支 `feat/channel-p1-rereview-fixes-20261121`。
- 代码提交 `448289eedd108b69cda5b32229d008d790c71fbd`。只动 iOS，安卓未改。

## 四条修复

| 项 | 修法 |
| --- | --- |
| 转发绕过 @所有人门闩 | `forward(_:to:)` 先走 `ChannelExtras.forwardMessage`。正文含 `@所有人` / `@all` 且 `localCanMentionAll(in:)` 对目标频道为假时，返回「只有目标频道 owner/协调者可以转发 @所有人，消息未发送。」，不调用 `postMessageEx`。有权限或正文没有全员提及时照常转发。 |
| 协调者被误判为不可移除 | `isProtectedMember` 不再用 `canMentionAll`。只保护本人（发送者 ID 或 `isSelf`）和频道群主。群主按 role：`owner` / `所有者` / `项目主管` / `群主` / `频道群主`。`coordinator` / `协调者` 即使能 @所有人也可以移除，请求能到平台。 |
| 本人消息 isAgent 被改写 | 平台消息保留原样 `is_agent`（`platformIsAgent`）。明确给了 true/false 就用平台值，包括本人。字段缺失时，本人兜底为非 Agent；其他人仍看成员名单。`recordChannelPost` 不再把该发送者全部消息强制写成 false。 |
| 定时队列空回执丢消息 | `postMessageEx` 在响应无 id 时仍返回 `""`。`flushScheduled` 用 `scheduledQueueAfterFlush`：空 id 不删队列项、不改 `effect_key`，下次到期再试。抛错路径同样保留。非空 id 才出队。 |

## 测试

四条都进 `WhereverCodeAgentTests` 的 `ChannelCollaborationTests`：

- `testForwardInterceptsMentionAllWithoutTargetPermission`
- `testCoordinatorIsRemovableWhileOwnerAndSelfStayProtected`
- `testPlatformIsAgentIsKeptForSelfWhenExplicit`
- `testEmptyPostReceiptKeepsScheduledItemAndEffectKey`（先打到 `postMessageEx`，无 id 的回执是 `""`，再断言队列项和 `ck_keep` 都还在）

## CI 证据

代码提交 `448289eedd108b69cda5b32229d008d790c71fbd`。

| 工作流 | 运行 | 结论 |
| --- | --- | --- |
| Android CI | [#588](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/44792003510)（`44792003510`） | `success` |
| iOS | [#303](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/44797340973)（`44797340973`） | `success` |

iOS 推送触发的 [#302](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/44792003291) 卡在「Build and capture live simulator launch」约 31 分钟（同步骤常态约 6 分钟），测试步骤还没开始，已取消。同一提交用 `workflow_dispatch` 重跑为 #303。#303 的「Test on iOS Simulator」实际执行了上述四条并 passed；该步骤汇总 `Executed 240 tests, with 0 failures`。同一次运行的 UI 测试步骤也是 success。

本报告提交不改源码，提交说明带 `[skip ci]`，避免再空跑一轮。验证以 `448289e` 上的两次成功运行为准。

## 未动

- 安卓代码与测试。
- 发送、定时发送原有的 @所有人门闩。
- `feat/channel-phase1-20261120` 与 `main`。未开 PR，未合并。

CHANNEL-P1-REREVIEW-FIXES-DONE
