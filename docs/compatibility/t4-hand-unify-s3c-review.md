# T4 S3c 修复交付：待 Muse 验收

阶段：S3c。状态：实现修复及全部 Hand 功能测试通过，等待 Muse 复核；尚未关闭阶段，未进入 S3d。

## Commit 与修改文件

实现提交：[0df76710e54fc50c2d88f36020198d2d9dba6d8a](https://github.com/huaerdaidai-gif/wherevercode-agent/commit/0df76710e54fc50c2d88f36020198d2d9dba6d8a)，父提交 df01c4164aa5ac5065a6816e0bc423f8a820357f。

只修改/新增：
- server/hand_server.py
- android/app/src/main/assets/install/hand_server.py
- server/test_hand_pending_policy.py（新增）

已有测试文件及断言均未修改；scripts/sync_hand_server.py、工作流、安装脚本、本机 MCP、runner、T6 Kotlin Dispatcher/状态机、iOS、签名配置均未在该实现提交变更。

开工核实：任务头 df01c41，集成头 b4db62288fabba61bf8bfe4efaae09b2467c604c。未合入集成分支或无关变更；工作通过 GitHub API 完成，未在无关设备克隆仓库。

## 实现内容及兼容性

同一个 _handle_pending_pairing 在两份文件中应用同样的策略分支：
- standalone：解密后若不是有效类型/版本的配对确认帧，直接返回、由连接处理器关闭连接，不回普通或密封错误帧。保留 A 兼容行为。
- bundled：同一拒绝分支保留 B 原有 cross_device_error 密封回包，args.code=cross_device_not_authorized，原有中文 message、seq=1、身份/任务/目录/action 字段保持。
- 有效密封 cross_device_pair_confirm 在两种策略中继续处理；正确配对码只改变配对状态，不分派工具或启动执行。
- 未批准调用在两种策略下都不进入 _dispatch，协议回包差别不改变执行拒绝边界。

原有 test_unapproved_client_sealed_call_rejected 的 self.assertEqual(events, []) 保持原样并恢复通过。

## 测试命令与实际结果

GitHub Actions 执行：

```sh
python3 -m pip install cryptography
git rev-parse HEAD
python3 -m unittest discover -s server -p 'test_*.py' -v 2>&1 | tee hand-tests.log
```

实际工作流启用 set -o pipefail，测试失败不能被 tee 掩盖。

- [Run 43630954840](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43630954840)
- [Hand functional tests / job 132557205930](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43630954840/job/132557205930)：success。
- 输出：Ran 57 tests in 17.246s；OK。
- 通过 57，失败 0，错误 0，跳过 0。
- 运行环境：GitHub Actions Ubuntu / CPython 3.11。
- 工作流上传 hand-tests-0df76710e54fc50c2d88f36020198d2d9dba6d8a 日志 artifact。

新增 3 个 unittest 方法，其中拒绝矩阵覆盖两份真实模块 × 两种策略 × 六种 action，共 24 个 subTest 场景；合法配对另覆盖两份模块 × 两种策略共 4 个场景。subTest 场景不冒充额外 unittest 方法数量。

六种拒绝 action：
run / write / read / list / authorize_project_folder / get_artifact。

使用真实 socketpair、实际 hello/X25519/HKDF/AES-GCM 与长度帧，发送任务/身份/目录绑定正确的已签名凭证，避免仅因伪造凭证被后续校验拒绝而误判 pending 门禁有效。

## 拒绝执行与安全性证据

新增测试同时断言：
1. module.spawn_agent 未调用；B 已存在的 run_agent_entry 也未调用。
2. _dispatch 未调用；_do_run、_do_control、_do_write、_do_read、_do_list、_do_authorize_project_folder、_get_artifact 各自 spy 均未调用。
3. task_meta、processes、process_attempts、starting_tasks 与原快照相同；预置 tasks.json 未修改。
4. 没有 artifacts 或 project_grants.json 执行副作用；测试工作目录文件集合及字节未改变，预置 runner/write 目标标记不存在。
5. 审计中没有 call、folder_authorized、change_goal 执行事件；客户端仍为 PENDING。
6. standalone 收到 EOF，socket timeout 不会被当作拒绝成功。
7. bundled 回包用错误会话密钥解密抛 InvalidTag；正确密钥解密后整帧字典精确比对类型、版本、seq、身份、task/agent/folder/action、错误码和消息，随后收到 EOF。
8. 合法配对确认在两种策略下均回 cross_device_pair_approved，客户端落 APPROVED，且没有工具/runner 或任务创建。

已有配对、授权、撤销/密钥替换、凭证、控制、产物、内置 Python 等 server/test_*.py 全部一起运行。没有修改或放宽已有安全断言。

本次新增注入仅在测试中以 mock/spy 包裹真实执行边界；没有新增生产端点、调度器、任务状态、用户路径入口或第二套结果协议。

## 防漂移与迁移状态

[drift / job 132557205694](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43630954840/job/132557205694)：failure，仍为 A/B 尚未字节一致所致，是 Muse 已允许的唯一红项。整个 workflow 因此仍红，不能称为全 CI 绿。

本次对两份源码添加相同兼容分支，A/B 的 LCS 行插入/删除距离未增：1630 → 1630；本次是原 S3c 的回归修复，S3c 组累计仍为 2034 → 1630。此指标只用于记录迁移状态，不作为任何功能验收证据。

--check 判定逻辑与同步脚本未修改；没有 continue-on-error、跳过功能测试或放宽字节比较。源/资产最终统一尚未完成，不在本阶段强行同步覆盖。

## 未验证事项及后续门禁

- 尚无 Android 模拟器/真设备链路证据；本报告只证明 Host CI 的实际协议和功能测试。
- S3d 尚未开始；log_path 缺口未补、不算豁免。用户本次已给出功能/安全/T6 协调约束，须在 Muse 验收 S3c 后执行。
- 未进行 log_path 允许目录之外的兼容裁定、未定义新对外警告字段，未改 T6 Dispatcher 或任务状态机。
- init-agents 配置差异与授权回执键序问题仍待独立分析及 Muse 复核；本次未改变其行为或已有断言。
- S3e/S3f、最终字节同步、S4 完整补测、S5 正反证据、S6 时间界和真实进程证据仍未完成。

下一步：技术测试门槛已通过；需 Muse 复核通过本提交，S3c 才能关闭并进入 S3d。等待期间不提前实施 runner/log_path 或协议接线。
