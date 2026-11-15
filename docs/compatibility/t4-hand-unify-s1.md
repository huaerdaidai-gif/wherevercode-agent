# T4 S1：Hand 差异矩阵与阻塞报告

基线：`feat/native-dual-ui-delivery-20261114` @ `bd0e11ff21a753124f35ebe992a8228bb81977df`。通过 GitHub API 核对；工作分支 `task/t4-hand-unify` 从该 SHA 创建。未克隆仓库，未读取旧仓库。本文仅记录源码证据，不代表线上部署或设备验收。

A = `server/hand_server.py`（1568 非末尾空行）；B = `android/app/src/main/assets/install/hand_server.py`（3996 非末尾空行）。行号均相对此基线。

## 使用方与证据边界

- A：独立部署入口由 `server/README.md` 指向；`server/test_hand_server.py` 为其测试入口。本轮未执行。
- B：`HandInstallBundle.kt` 从 assets/install 读取完整字节，以 SHA-256 前 12 位计算版本；供 App SSH 安装包及更新使用。
- Android `HandClient.kt` 有工具调用、requestWsToken、requestHandAdmin；调用方存在不等于设备链路已通过。
- `server/test_embedded_python_control.py` 实际测的是 `android/app/src/main/python/agent_runner.py`，不能当成 B 的测试。
- 现网实际部署 SHA、在线使用方、设备操作日志尚未取得；以下“使用方”指仓库可追溯入口，不宣称已观察到线上流量。

## 逐组差异矩阵

| ID | 功能/端点 | A | B | 使用方/合并核对要求 |
|---|---|---|---|---|
| H01 | 长度帧、X25519/HKDF、AES-GCM、v2 凭证 | 有 | 有 | HandClient；保留互操作及错误码测试 |
| H02 | StateStore、私钥生成、指纹、审计 | 有 | 有 | 独立部署/安装包；状态兼容、0600 和日志脱敏须验证 |
| H03 | hello、pending/approve/revoke、公钥绑定 | 有 | 有，额外返回 client_status、hand_version、update_supported、extensions | App 发现/配对；保留旧 hello 与新增字段 |
| H04 | 六位配对码、轮换、错误尝试控制、密封 cross_device_pair_confirm | 无 | B:714–767、1302–1374 | App 配对、pair-code CLI；需新增实际协议测试 |
| H05 | agents()/agent_config() | agents.json；command 来自旧配置 | 注册表优先，兼容旧行；冲突 command 记录 legacy_command_ignored | hello/agents/discover；旧配置同 ID 的行为存在冲突，S2 必须明确兼容策略 |
| H06 | Agent repos 能力卡 | git_repos_for_folder 读取真实分支/commit（A:373–399），agents 输出 repos | 无此 helper/字段 | A 能力卡消费者；必须补回且非 git 不伪造 |
| H07 | authorized_folders | auth.json 列表 + client project_grants | 另支持 dict.folders，并自动加入注册表及配置 folder | 工具和 MCP；授权边界不同，不能以“并集”静默放宽 A 权限 |
| H08 | authorize_project_folder | 有，按客户端保存 | 有；实现/注册表上下文不同 | HandClient 项目授权；保留客户端隔离及路径校验 |
| H09 | list/read/write、路径解析、文本大小限制 | 有 | 有 | HandClient；旧安全测试仍须通过 |
| H10 | run 白名单 agent/__control__、凭证绑定、owner/epoch/goal 栅栏 | 有 | 有，叠加 registry/internal 分支 | 远程任务；保留状态语义、错误码与所有执行前检查 |
| H11 | 子进程执行 | A _do_run 内执行循环 | B _do_run 经 run_agent_entry/run_cli_agent | 真任务；必须逐分支核对断连/超时/输出/注册进程/清理 |
| H12 | watchdog、keepalive、starting_tasks、attempt supersession | 有 | 有 | 暂停任务与异常退出；不能误称 A 加固在 B 全部缺失 |
| H13 | pause/resume/stop、进程组控制、stopped_tasks 持久化 | 有 | 有 | __control__；需有界时间内实际进程证据 |
| H14 | get_artifact、校验、原子保存、客户端/attempt 绑定 | 有 | 有 | 结果回读；保留失败和跨客户端隔离测试 |
| H15 | WebSocket HTTP upgrade、帧协议、订阅队列、事件推送 | 无 | B:341–448、1080–1301 | App 控制台；需协议与断开/慢消费者测试 |
| H16 | wherevercode_ws_token/reply、一次性票据 | 无 | B:1103–1132；仅 APPROVED 后签发 | HandClient.requestWsToken；过期/重放/跨客户端需验证 |
| H17 | wherevercode_device_admin/update_hand | 无 | B:1552–1807，confirmed + stage/apply/rollback、watchdog、healthy start | HandClient.requestHandAdmin/HandUpdate；需临时目录与替身进程测试，不在审计期更新设备 |
| H18 | mDNS 编解码、组播宣告/查询响应、LAN IP | 无 | B:2462–2666 | 局域网发现；需 DNS 包测试和设备发现证据 |
| H19 | 七类扩展注册/覆盖/启停/删除/摘要 | 无 | B:2940 后 EXTENSION_KINDS、registry_* | CLI extensions/extension、hello；登记项不等于执行器 |
| H20 | CLI 二进制发现、设备别名、目录/日志模板、登录检查、派生 brain | 无 | resolve_binary/resolve_adapters/check_login/_derived_brains | discover、hello、CLI runner；内置 verified 字段不是本轮验证证据 |
| H21 | execution=internal | 无 | run_agent_entry 显式拒绝 Hand 执行 | App 自身，B:3570–3606；注释称 App 落册为后续工作，不能宣称 Hand 可执行该行 |
| H22 | webhook 出站发送 | 无 | webhook_event_id/webhook_deliver、webhook probe | CLI 探针；真实派单接线缺失，见阻塞 |
| H23 | healthcheck 插件 | 无 | run_healthcheck；plugin run healthcheck | CLI；需补实现输出测试 |
| H24 | 本机 stdio MCP | 无 | initialize/ping/tools/list/tools/call、notifications、错误响应；工具 list/read/write/run（B:3763–3996） | 本机 MCP 客户端；文件授权与 runner 单独验证，不能冒充密封远程任务控制链 |
| H25 | 通用 CLI 管理 | serve/fingerprint/pair-list/pair-approve/pair-revoke/grant/agents/init-agents | 全部保留，新增 pair-code/extensions/extension/discover/plugin/webhook/mcp | 管理员；init-agents 默认 command 与 grant dict 兼容实现不同，须回归 |
| H26 | WACF 总线 | 无 | 仅注册行 design-only，明确无实现 | 无可达 Hand 执行入口；阻塞，不能算已实现功能 |
| H27 | 外部组网/brain/轮询描述行 | 不登记 | 注册表记录外部依赖或 App 配置位置 | 不能据 status=live 就认定 Hand 内有对应执行器；不引入旧平台依赖 |

## 红线触发：在此停下

1. B 的 BUILTIN_EXTENSIONS.protocol.wacf（约 3120 行，按 key 定位）：
   - `status: design-only`
   - `implementation: ... Hand 内暂无实现代码。`
   - `gap: 只登记设计，未实现；实现时按 WACF 设计接进注册表，不改核心。`
   这是明确的登记壳，不能在“每项合并后仍可达”的验收中声称可执行。
2. B 的 BUILTIN_EXTENSIONS.wakeup.webhook：
   - `status: mechanism-live`
   - gap 明确指出派单→webhook→Agent 经 MCP 认领的端到端接线未定。
   webhook_deliver 本身有实现；缺的是完整链路，不能把两者混为一谈。
3. B 的 internal Agent 行为是主动拒绝并指向 App；注释明确 App AgentRecord/TeamMember 落册属于后续工作。这不是一个已在 Hand 实现的执行分支，也不能靠新增平行引擎补齐。

依任务书“发现只有壳没实现的功能如实标注并停下报告”，本轮停在 S1。S1 的代码使用方矩阵已记录，现网部署使用方仍待设备证据。S2 未定稿，S3–S6 未开始，无测试/CI/设备通过结论。

## 交 Muse 的范围决议

请明确 H26 的设计登记项、H22 的接线缺口、H21 的 App 外部执行边界是否按“保留原登记与真实拒绝/缺口”验收，而不要求本任务实现。确认后才能继续 S2；S2 方案仍须单独提交并经 Muse 复核通过后才能改代码。

候选方向（非定稿）：以 server/hand_server.py 为唯一手写源，显式同步生成 assets/install/hand_server.py，字节级校验；迁入 B 增量同时保留 A 的 repos 与控制/安全语义。H05/H07 是必须先裁定的兼容和授权冲突，不能直接整文件覆盖。
