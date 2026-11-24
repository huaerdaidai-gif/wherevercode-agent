# 任务派工规格（Task Dispatch Spec）v1

本文件是 WhereverCode Agent App 内嵌服务「任务派工」的实现合同。双端（Android Kotlin / iOS Swift）按本规格实现，行为以夹具对拍为准。

## 0. 基线与优先级

- 语义基线 = 线上 V5 服务端 `v5_platform.py` 的现行行为（已在线运行）。A 组与 C 组（recorded）夹具由 `recorder/record_fixtures.py` 直接驱动基线代码录制，是基线行为的地面真值。
- 本规格与基线冲突时：**既有 13 个工具的行为以 A/C 夹具为准**；**新增能力（update/withdraw/reopen、频道绑定、幂等作用域收紧、软删除、角色化暴露）以本规格为准**（B 组夹具）。
- 平台差异（iOS 仅前台、主机切换等）只影响可用性，不许改变语义。

## 1. 数据模型

### tasks（在基线字段上新增 5 列，其余不变）

基线字段：task_id, project_id, kind(work|review|pack), parent_id, assignee_id/name, reviewer_id/name, title, goal, state, round, max_rounds(默认 3), lease_until, summary, branch, commit_sha, pr_url, verdict, feedback, decision_reason, claimed_by_id/name, reserved_for_id/until, delivery_total_pages, delivery_pages, delivery_truncated, delivery_files, version(起 1), attempt, attempt_id, cancel_pending(**保留列，第一版不启用、不置位**), created_at, updated_at。

新增列：

| 列 | 类型 | 说明 |
|---|---|---|
| channel_id | TEXT NULL | 建单时绑定，之后不可改（update_task 不许改）。NULL = 未绑频道的老式任务 |
| epoch | INTEGER NOT NULL DEFAULT 1 | reopen 时 +1；参与幂等键（见 §4.2） |
| created_by | TEXT | 建单人 id（基线已有此列，迁移补齐即可） |
| deleted_at | REAL NULL | 软删除时间 |
| deleted_by | TEXT NULL | 软删除操作人 |

### 其余表（照基线）

- task_events(seq, task_id, at, who, kind, text)：账本，只追加。
- delivery_pages(task_id, page, total_pages, text)，主键 (task_id, page)。注意：页按 **work 任务 id** 存储（pack 子任务递的页落在父 work 名下）。
- idempotency(task_id, action, attempt_id, result, created_at)：主键 (task_id, action, attempt_id)。**reopen 后果靠 epoch 隔离**：记录键在实现层拼接 epoch，即 (task_id, epoch, action, attempt_id)；基线库无 epoch 列时等价 epoch=1。
- agent_status_reports(agent_id, quota_text, quota_reset_at, reported_at)：report_status 的落点，与任务状态无关。

### version 规则

- 每个工具调用对每个受影响任务的每次逻辑变更，version +1（基线 `_update` 语义）。心跳、报进度、续租都算变更（基线如此，夹具 a03/a04 为准）。
- 新增工具：update/withdraw/reopen 对每个受影响任务恰好 +1（withdraw 级联取消子任务时，子任务也 +1）。幂等重放（withdraw 对已取消任务）不 +1、不写事件。

## 2. 工具契约（16 个）

参数/返回的完整形状以 A 组夹具的录制为准；本节只写要点与新增部分。错误模型见 §4.5。

### 干活侧 9 个（agent 作用域）

1. **list_my_tasks**() → {tasks: [agent_view], note}。列 assignee 是我或 claimed_by 是我的 queued/claimed 任务，FIFO。调用前先跑租约清扫。agent_view 字段照基线（含 goal 全文、version、feedback、instructions；返工轮带 previous_delivery 与 review_feedback；pack/review 视图带 delivery 信息）。
2. **claim_task**(task_id, expected_version?, attempt_id?) → {task: agent_view, lease_seconds}。仅指派给我的 queued 可领；返工预留期内仅 reserved_for_id 可领（其余 409 task_reserved）；已被我领时重领 = 续租；被别人领 409 task_state_claimed；非指派人按不存在处理（404 task_not_found）。
3. **release_task**(task_id, reason?, expected_version?, attempt_id?) → {ok}。仅 claimed 的持有人。后果按 kind 分：work → needs_decision(decision_reason=released)；pack → queued；review → cancelled 且父 work → needs_decision(review_released)。事件记在父任务（无父记自己）。
4. **report_progress**(task_id, text≤2000, ...) → {ok, lease_seconds}。仅持有人、仅 claimed。续租 + progress 事件。
5. **heartbeat**(task_id, expected_version?, attempt_id?) → {ok, lease_seconds}。仅持有人、仅 claimed。只续租、不写事件。租约已过期时 409 stale_attempt。
6. **report_status**(quota_text≤500?, quota_reset_at≤120?) → {ok, quota_text, quota_reset_at, reported_at}。报 **Agent 自己的额度**，与任务无关；两者至少填一个，全空报错；upsert。
7. **submit_result**(task_id, summary≤8000, branch?/commit?/pr_url?, verdict?, ...) → {ok, state, note}。按任务 kind 与 verdict 有无分流：
   - work 且无 verdict：证据门——summary 必填且 branch/commit/pr_url 至少一个，否则 422 evidence_required；commit 须 7–64 位十六进制、branch 须合法 ref、pr_url 须 http(s) URL，格式错 409 v5_error。有 pack 角色 Agent 且带 branch → 开 pack 子任务、本任务 reviewing；否则有 reviewer → 开 review 子任务、reviewing；无 reviewer → needs_decision(no_reviewer)。提交即清本任务旧 delivery_pages 并快照证据进子任务。
   - review 且有 verdict(approve|changes_requested)：审查任务 → done；父 work 按 §3 流转。changes_requested 时 summary 必填（驳回理由）。
   - 交叉误用：work 交 verdict → 409 use_submit_for_work_tasks；review 交 summary 式 → 409 use_verdict_for_review_tasks；pack 用 submit → 409 use_attach_for_pack_tasks。
   - 状态守卫：任务须 claimed 且我是持有人，否则 404/409 task_state_<state>（见 _owned_open 语义与夹具 a12）。
8. **attach_delivery_diff**(task_id, page, total_pages, text≤65536, truncated?, ...) → {ok, work_id, pack_task_id, pages_received, total_pages, complete}。仅 pack 角色、仅 pack 子任务（task_id 可填 pack 子任务 id；填 work id 时自动定位其最新 pack 子任务）。page/total_pages 须 1≤page≤total_pages≤200；total_pages 中途不许变；整份 diff ≤2MB。页齐：pack 子任务 → done；work 有 reviewer → 开审查（事件 review_opened），无 → needs_decision(no_reviewer, 事件 pack_done)。
9. **get_delivery_diff**(task_id, page) → {ok, work_id, page, total_pages, pages_received, truncated, changed_files, text}。仅该 work 的审查人（审查子任务的 assignee/claimer）可读，其余 404 task_not_found；页不存在 404 delivery_page_missing；独立审查（无父任务）409 no_delivery_diff。

### 派单侧 7 个（owner 作用域）

10. **start_task**(agent, goal≤20000, project_id, title?, reviewer?, channel_id?, idempotency_key?) → {task: owner_view, state: "queued"}。agent/reviewer 须是已注册 Agent，不存在报错。title 缺省取 goal 首行前 100 字。created_by = 调用者。**幂等作用域 = (channel_id, idempotency_key)**（channel_id 为 NULL 时作用域为 (NULL, key)）：同作用域同 key 且内容（agent/goal/project_id/reviewer/title）相同 → 返回已有任务；同 key 内容不同 → 409 idempotency_conflict。（基线是全局 key 作用域，本规格收紧之；B 组 b04 为准。）
11. **get_task**(task_id, include_events?) → {task: owner_view(+events)}。owner_view 字段照基线（含 state_label、decision_reason、version）。软删除的任务仍可读，视图带 deleted_at/deleted_by。
12. **list_tasks**(project_id?, channel_id?, limit≤200, offset?) → {tasks: [owner_view]}。仅 kind=work；按 updated_at 倒序；**排除已软删除**；channel_id 给定时按频道过滤。调用前先跑租约清扫。
13. **decide_task**(task_id, action=done|cancel, reason 必填) → {ok, task: owner_view, state}。仅 needs_decision，其余状态 409 task_state_<state>。
14. **update_task**(task_id, expected_version **必填**, title?, goal?, reviewer?, agent?) → {task: owner_view}。（新增）仅 work、仅 queued/claimed；reviewing/needs_decision/终态 → 409 task_state_<state>。缺 expected_version → 409 v5_error「expected_version 必填」；版本不符 409 stale_version。改 agent（改派）：state → queued，清 claimed_by 与 lease_until，reserved_for_id = 新 assignee、reserved_until = now + lease_seconds，事件 reassigned；未改派只改内容时事件 updated。channel_id 不可改（传了不同值 → 409 v5_error）。reviewer 可改（须已注册 Agent；传 null 清除）。
15. **withdraw_task**(task_id, reason?) → {ok, state: "cancelled", task: owner_view}。（新增）仅 work；queued/claimed/reviewing 可撤：state → cancelled，清 lease/claimed/reserved，事件 withdrawn（text 带 reason）；其打开着的 review/pack 子任务（非终态者）同步 → cancelled 并记 withdrawn 事件。approved/done → 409 task_state_<state>；**已 cancelled 时幂等返回成功**（state=cancelled，不 +version、不写事件）。
16. **reopen_task**(task_id) → {task: owner_view}。（新增）仅 work、仅终态（approved/done/cancelled）；其余 409 task_state_<state>；非 work 任务 409 v5_error「只有工作任务可以重开」。效果：state → queued；round = 1；**epoch +1**；清 verdict/feedback/summary/branch/commit_sha/pr_url/decision_reason/claimed/reserved/lease 与全部 delivery 字段及该任务的 delivery_pages 行；保留原 assignee/reviewer/channel_id；事件 reopened，text 含上一轮快照（state/round/verdict/summary 摘要）。

### UI 专属（不进 Agent 工具表）

17. **ui_delete_task**(task_id)：软删除。仅终态 work 任务；仅建单人（created_by）或频道 owner 可操作（UI 层校验并隐藏入口，服务层再校验一次：不符 → 403 task_forbidden）；非终态 → 409 task_not_terminal。效果：deleted_at/deleted_by 落列，事件 deleted，version +1；list_tasks/list_my_tasks 排除；get_task 仍可读。

## 3. 状态机

7 态：queued / claimed / reviewing / approved / needs_decision / done / cancelled（终态 = approved、done、cancelled）。完整迁移表见 `transitions.json`（双端共读一份数据）。要点：

- 审查子任务（kind=review）：queued → claimed → done（交 verdict 时）。审查人 ≠ 执行人靠指派结构隔离（子任务只出现在审查人的 list_my_tasks 里）。
- pack 子任务（kind=pack）：queued → claimed → done（页齐时）；排队超 30 分钟未领被清扫跳过（→ cancelled，事件 pack_skipped），父 work 直接开审查或转 needs_decision。
- 返工：verdict=changes_requested 且 round < max_rounds → work 回 queued、round+1、预留原 claimed_by 一个租约窗口；round == max_rounds → needs_decision(rounds_exceeded)。
- approved 与 done 的关系：approved = 审查通过的终态；done = decide_task 确认完成（或审查子任务自身的完成态）。两者都是终态，reopen 来源都允许。

## 4. 机制

### 4.1 租约

lease_seconds = 7200（2 小时），**墙钟时间戳**落库（不用单调时钟）。heartbeat/report_progress/同人重领续租。清扫（expire_leases）在 list_my_tasks/list_tasks/claim_task 路径上先行：claimed 且 lease_until < now → 回 queued、清 claimed、事件 lease_expired；顺带清过期预留；顺带处理 pack 超时跳过。主机重启不批量释放（靠墙钟比较，重启后未到期租约继续有效）。宽限：清扫比较用 lease_until < now（无额外宽限常量；W13 的主机切换宽限只作用于连接层重试，不改租约语义）。

### 4.2 fencing（顺序不可换）

写操作校验顺序（基线 `_check_fencing`，夹具 a03 为准）：
1. expected_version 给定时：类型须为整数（bool 不算）→ 不符 409 v5_error；与当前 version 不等 → **409 stale_version**。
2. attempt_id 给定时：查 (task_id, epoch, action, attempt_id) 幂等表，命中 → **回放首次结果**（不再执行）。
即：版本检查在幂等回放之前。reopen 后 epoch 变化，旧 attempt_id 不再命中（b03 为准）。

### 4.3 归属与可见性

- 干活侧的任务可见性 = assignee 或 claimed_by 是我；对不属于我的任务一律按不存在处理（404 task_not_found），不泄露存在性。
- _owned_open 语义：任务不存在或不归我 → 404；状态不对 → 409 task_state_<state>；claimed 但租约已过期 → 409 stale_attempt。

### 4.4 常量

LEASE_SECONDS=7200；MAX_GOAL_CHARS=20000；MAX_SUMMARY_CHARS=8000；MAX_PROGRESS_CHARS=2000；MAX_PAGE_CHARS=65536；MAX_DELIVERY_BYTES=2MiB；MAX_DELIVERY_PAGES=200；MAX_ROUNDS 默认 3；PACK_TIMEOUT_SECONDS=1800；title 上限 100；quota_text 上限 500；quota_reset_at 上限 120。

### 4.5 错误模型

错误 = {status, code, message}。status 缺省 409；404 用于不存在/不可见；422 用于 evidence_required；403 用于 task_forbidden（软删除越权）。code 取值：stale_version / stale_attempt / task_not_found / task_state_<state> / task_reserved / task_forbidden / task_not_terminal / evidence_required / idempotency_conflict / delivery_page_missing / no_delivery_diff / pack_role_required / use_attach_for_pack_tasks / use_verdict_for_review_tasks / use_submit_for_work_tasks / v5_error（其余校验类，message 说明原因）。message 用中文、给人看，格式照基线（如「任务当前状态是等待接手，做不了这一步」）。

## 5. 频道绑定与事件写回

- start_task 带 channel_id 时任务绑定该频道；任务页/卡按 channel_id 归属频道。
- 账本事件反向写成频道消息（带 task_id 引用）：created、claimed、submitted、verdict（approve/changes_requested）、approved、decided、withdrawn、reopened、reassigned、lease_expired、rounds_exceeded。写回走 App 既有频道消息通道（本地层/内网层同一路径），失败不回滚任务操作（事件已落账本，写回可重试）。
- 任务讨论 = 回复这些频道消息；不另设任务评论工具。

## 6. 角色化暴露

- 干活 Agent 的 tools/list 只含 9 个干活工具（顺序：list_my_tasks, claim_task, release_task, report_progress, report_status, submit_result, attach_delivery_diff, get_delivery_diff, heartbeat）；调用派单工具按「未知工具」处理（409 v5_error「未知工具」，与基线对未授权工具的口径一致）。
- owner 作用域含全部 16 个。get_task 不向干活侧开放（干活侧读任务走 list_my_tasks 的 agent_view，已含 goal 全文与 version）。
- 角色（dev/review/pack）只影响 attach_delivery_diff（须 pack）与子任务指派，不改变工具可见性。

## 7. UI 契约

- 任务卡渲染 7 态全覆盖（状态标签用 transitions.json 的 label）；卡上操作按权限矩阵出现：撤回（owner，Q/C/R）、改派/编辑（owner，Q/C）、验收决定（owner，needs_decision 时 done/cancel）、重开（owner，终态）、软删除（建单人/频道 owner，终态）。
- 频道内派单入口：表单选 Agent（必选）、选审查人（可选）、填 goal；提交即 start_task 并绑当前频道。
- 无权按钮直接不渲染（不是禁用）；服务层校验是第二道关。

## 8. 夹具与验收

- 夹具目录 `fixtures/`：A 组（基线录制，精确比对）、C 组（对抗；recorded 部分精确比对，new-tool 部分按 B 组口径）、B 组（新行为，手写；result 用子集比对，state_after 与 error 精确比对）。
- 比对归一化：task_id → TASK_n（创建序）；时间戳 → "<ts>"；其余逐字比对（含 version、round、事件 kind 序列）。
- state_after 快照字段：ref, kind, parent, state, round, version, assignee_id, claimed_by_id, reserved_for_id, reviewer_id, decision_reason, verdict, delivery_pages, delivery_total_pages, events（kind 序列）。B 组快照另带 channel_id、epoch、deleted。
- 租约类用例靠可注入时钟推进（advance_clock_seconds 步）。
- 总装验收（W15）= 本规格 §2–§7 + 9 个端到端场景（见施工方案）：频道建单→领活→交付→审查→返工→裁定→撤回/重开→租约过期→离线回放幂等→软删除→角色隔离。
