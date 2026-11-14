# T4 S3b/S3c 证据与停工报告

## 本轮裁定与边界

Muse 允许迁移中仅防漂移检查为红，S3a 按裁定完成；任何其他 Hand 测试失败必须停下报告。每个实现组必须缩小 A/B 差异，冻结 scripts/sync_hand_server.py 的 --check 判定；已有测试断言不删除、不修改。

本轮以 104c266 为任务头继续。集成头重新核实为 8841d69cec42fbfd8506a44b6a495cb01a9a6fbc，相对原 bd0e11ff 仍只有 ios/WhereverCodeAgent/Resources/Info.plist 改动，未带入任务分支。全部读写通过 GitHub API；未克隆到云工作区、未碰旧仓库、签名工作流、密钥或 iOS。

## 已提交与 CI

| 阶段 | 提交 | 变更 | 功能测试 | 防漂移 |
|---|---|---|---|---|
| CI 基线 | 3c69e15b6901d8c836d999ce764aa90ed1f2c05e | 增加独立 tests job，执行 server/test_*.py 并保留日志；实现未改 | [43629251642](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43629251642) tests 绿 | drift 红 |
| S3b | 4567f1ba696df89f0ad3c9dbd383ddc915dd1191 | B 注册表/发现/CLI、显式配置策略、bundled 审计和安装参数；A repos 保留并补到 B；7 项新增测试 | [43629557420](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43629557420) tests 绿 | drift 红 |
| S3c | dc066e72956f5c5a179689185d565af7a3cea44f | B 配对码/密封配对/hello/mDNS 迁入 A；6 项新增测试 | [43629733314](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43629733314) tests 红，54 项 / 1 失败 | drift 红 |

功能 tests job 与 drift 无 needs 依赖；没有 continue-on-error，没有弱化字节比较，没有修改已有测试文件或断言。脚本本轮未改。

S3b 的测试已包含 standalone 新发现目录不能自动授权、真实 grant 后才可访问；bundled 隐式授权按 agent/client/source/folder/policy 记录审计。WACF 设计登记和 webhook 接线缺口原样保留，旁加注释，未增加执行壳。

## 差异单调证据

指标固定为将 A 文本变为 B 的最小行插入数+删除数，基于逐行精确 LCS（行内容不归一化）：
- 原始 A/B 与 CI 基线：2796。
- S3b：2034。
- S3c：1630。

实现提交严格下降；CI/报告提交不动 A/B，指标不变。以上指标只是迁移量，不替代行为测试，更不意味着并集完成。

## 必须停下的功能回归

[失败 job 132553462587](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43629733314/job/132553462587)

原有 server/test_hand_server.py:883：
- test_unapproved_client_sealed_call_rejected
- self.assertEqual(events, [])，要求未批准的工具调用关闭连接且无回包。
- 实际收到一帧 cross_device_error，args.code=cross_device_not_authorized。

原因：S3c 将 B 的 _handle_pending_pairing 分支迁入 A，B 对非配对确认帧发送密封拒绝，A 原行为是直接关闭连接。安全门禁仍在，但协议可观察行为发生变化；迁移时遗漏了这一兼容差异。不能归入获准的防漂移红灯，不能改断言放行。

当前停止 S3d 及之后的实现。建议修复（尚未实施）：在同一 pending 配对处理函数中，非配对确认帧于 standalone 保留 A 的无回包拒绝，bundled 保留 B 的密封拒绝；正确的密封配对确认两策略都仍可用。保留旧断言，并补两策略拒绝行为的明确测试，再跑全部 Hand 测试。

## 新发现的未实现承诺（S3d 前）

B 的 run_cli_agent(binary, ..., log_path=None, ...)：
- 函数注释承诺：log_path 给出时，把抓到的完整输出另外写一份日志副本。
- 实际函数体从 subprocess 启动到 finally 关闭 lease，只收集/返回输出；log_path 在该函数中仅出现在参数声明和注释，无打开文件/写入实现。
- 远程 _do_run 和本机 MCP 都会通过 adapter_log_path / run_agent_entry 传入 log_path，不能因参数可传就认为日志副本已实现。
- 此结论是完整函数的静态核对，不声称已用设备复现。现有 stdout 返回和 progress 实现确实存在，缺的是额外磁盘副本。

依任务书发现只有壳/无实现须报告的要求，此项不假装迁移完成。需 Muse 决定：作为既有未实现承诺如实标注并保留参数，或在本任务明确补齐隔离的日志副本实现与测试。不得默认套用只批准给 WACF/webhook 接线的验收豁免。

## 剩余未完成

- S3c：上述协议回归未修复，阶段验收未通过。
- S3d：runner/MCP 尚未迁入 A；A 的输出排空和进程控制语义仍需完整保留；log_path 缺口待裁定。
- S3e：WS/token/events。
- S3f：更新/webhook 发送/healthcheck、完整重启参数保持；目前只补了 B watchdog 的 config-policy 保持。
- init-agents 默认配置和授权回执键序兼容仍待协调；两个现有来源的行为不能靠改既有断言消除。
- 最终源/资产说明、逐字节同步、S4 完整测试、S5 正反证据、S6 真设备链路均未完成。
- 未形成统一成功、发布或设备通过结论。

本报告提交只加文档，不继续改实现。需要先处理功能回归并恢复全绿；未有证据前，不将 S3c 或整个任务标为完成。
