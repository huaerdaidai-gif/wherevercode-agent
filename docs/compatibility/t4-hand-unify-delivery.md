# T4 开发交付：Hand 单一源码、功能回归与主机链路证据

开发状态：代码统一与 Host CI 完成；Muse 审查并行，未宣称 S3c 或 T4 已获人工验收。未合并 main、未改写 PR #10、未发布。Android/真实 provider CLI/系统 supervisor 真机链路仍未验证，S6 原始设备验收未闭环。

## 1. 分支、提交及变更范围

工作分支 task/t4-hand-unify；本轮从 4a4a695 继续，完整保留 S3c 0df7671 及其阶段报告。开工读取集成头 b4db62288fabba61bf8bfe4efaae09b2467c604c，但未合入集成分支。
使用 GitHub API 读写及 expected_sha 非强制快进；没有本地克隆/工作树，没有触碰旧仓库 wherevercode、iOS、签名密钥/变量、android-signed*.yml 或 T6 Kotlin Dispatcher。

| 阶段 | Commit | 实际内容 | 功能 CI |
|---|---|---|---|
| S3d | f957b8612e3fd919b83d8be9fc4a4dc3c46be979 | 统一远端/MCP CLI runner；实现配置型安全 log_path、内部非致命告警；10 项新增测试 | [43632041870](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43632041870)，67 tests OK |
| S3e | 41dc833c339a41e555c46856fd7ebbff2849e06e | WS HTTP/帧/订阅/票据/真实执行事件；4 项新增测试 | [43632178793](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43632178793)，71 tests OK |
| S3f | 16756cf8184b7c5e6d72d6d00fd8297f95dedd82 | 更新管理、webhook 发送、healthcheck；watchdog 保留原启动 argv；5 项新增测试 | [43632343967](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43632343967)，76 tests OK |
| 兼容分析 | 8a0c208f5e1ed41cd1163111e8e6180c84946bc6 | 独立区分 init 默认命令和授权回执键序，记录调用方和最小保留方案 | 文档，不替代测试 |
| S4/统一源码 | f448c81897a47d87260d9f0b928e911067acb675 | 保留两策略旧 init 命令/回执字节/凭证授权比较；源资产同字节；4 项新增测试 | 首跑 80 tests / 1 fixture mismatch，不计通过 |
| S5 | d97855e34cabc8f01e8a93d485061518b0fadfda | 仅 Hand 的 LF 属性与防漂移/负例日志 artifact；判定脚本未改 | 后续完整 CI 证明 |
| S4 fixture 修正 | 5d54e7547626e760af93957ee097601ce87437f5 | standalone 专项测试显式选 standalone，原“不自动授权”断言不改 | [43632917127](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43632917127)，全部 CI 绿 |
| S6 Host | 1457d223963d0a011b63bef3662f5bb25f1a1e65 | 两入口×两策略的真实进程组/加密协议/产物链；上传 JSON 和日志 | [43633046240](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43633046240)，81 tests OK，drift 绿 |

| S4 补充/最终回归 | 4901d4c8fc38d2db097c43b424154da5ab90e5b6 | 真实 stdio MCP/文件边界/internal 拒绝测试；修正受控停止时 WS 先发 ok 的竞态，不改任务状态机或字段 | [43633463473](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43633463473)，85 tests OK，全部 CI 绿 |

实现文件：server/hand_server.py；生成资产：android/app/src/main/assets/install/hand_server.py。
相关脚本/CI：scripts/sync_hand_server.py（保持原判定）、.github/workflows/hand.yml、.gitattributes。此前 S3b 已修改安装脚本显式 bundled，本轮未改无关构建。

测试文件：原 test_hand_server.py 仅增加安装入口 fixture；test_hand_config_policy.py 仅明确 standalone 构造参数；所有旧 expected 值及断言不变。新增 runner_logs、ws、admin_webhook、compatibility、host_chain、mcp_surface；此前 pairing_mdns、pending_policy 和 embedded_python_control 均保留。

## 2. 唯一源码与 S5 正反证据

只手工维护 server/hand_server.py，资产由主文件字节自动生成，文件头写明来源和生成命令；不按资产位置选择业务行为，没有第二套模板/引擎。

```sh
python3 scripts/sync_hand_server.py --write  # 显式生成
python3 scripts/sync_hand_server.py --check  # 默认同样只检查
```

最终两路径 Git blob 完全相同：16f8cc585dce1309bc6a36897cfa2d8205655b63，198048 bytes。
SHA-256：4a22c65e3a7d1ab7fa0d2428e2afddb86872901c896f94a3a1dae5cbbd0deb1a。
内容版本：4a22c65e3a7d。同步脚本 blob 仍为冻结的 bf7d5afa90f9c29fafbcb57d9cb77d0d3363cd44。

[drift job 132565026452](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43633463473/job/132565026452)：
- 已提交源/资产逐字节比较：通过。
- 临时副本只改资产：--check 非零，验证脚本断言为退出码 1。
- 临时副本删除资产：--check 非零（1）。
- 显式 --write 后 --check：通过；再次检查通过，生成可重复。
- 原始检查先执行，不自动修复仓库漂移，不用 continue-on-error 或忽略比较。
- artifact：[hand-drift-4901d4c](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43633463473/artifacts/14129330263)。

行差只作迁移记录：S3d 1630→1083，S3e 1083→673，S3f 673→168，最终168→0；验收依据是以下实际测试/协议证据。

## 3. 全部 Hand 测试

实际命令（CI 启用 pipefail）：

```sh
python3 -m pip install cryptography
python3 -m unittest discover -s server -p 'test_*.py' -v 2>&1 | tee hand-tests.log
```

[功能 job 132565026685](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43633463473/job/132565026685)：Ran 85 tests in 56.020s；OK。
通过85、失败0、错误0、跳过0。环境为 GitHub Actions Linux / CPython 3.11。
[测试日志与4份链路 JSON artifact](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43633463473/artifacts/14129112588)。

过程中的 fixture mismatch 已明确报告并修复，不将当时的功能红灯混为防漂移红灯。旧断言与期望字符串均未改；物理文件默认策略通过独立 subprocess 导入验证，避免测试 fixture 掩盖生产默认值。

## 4. 兼容行为及 log_path 安全

| 项 | standalone | bundled |
|---|---|---|
| 默认/入口 | 生产默认，无参数保持 A | 安装脚本明确 --config-policy bundled |
| 未批准工具调用 | 直接断连、无错误帧 | 原有 AES-GCM 密封拒绝错误帧 |
| 正确密封配对确认 | 正常批准 | 正常批准 |
| command 冲突 | agents.json 显式 command 优先 | 注册表优先，忽略冲突写审计 |
| 目录授权 | 显式 auth + 当前 client project grants | 另包含配置/注册目录，自动授权决策写审计 |
| 凭证授权目录比较 | A 的 lexical 比较，调度仍做 realpath 门禁 | B 的 realpath 等价比较 |
| init-agents | 原 A wrapper 默认 command，不覆盖现有文件 | 原 B 直接 CLI 默认 command，不覆盖现有文件 |
| 项目授权回执 | 原 A 字节键序 | 原 B 字节键序 |

统一源码按显式策略保留两套既有字节，不按 __file__/__name__ 识别资产。App 原本按字段解析两种回执，旧 raw-byte fixture 仍原样通过。内置版测试 loader 用真实类子类注入 bundled 默认以模拟安装服务，显式策略仍可覆盖；新增 subprocess smoke 验证两物理文件本身的默认均为 standalone。

log_path：
- 唯一来源仍是设备注册表 log_template，经 adapter_log_path 注入 task SHA-256；未增加远端或 MCP 用户路径参数。
- 仅配置时复制；成功副本为完整 runner 返回文本（可以长于远端/MCP 最终响应截断），UTF-8、0600。
- 工作目录内、任务 hash 绑定文件名；复用 _resolve_path 和产物相同的 directory-fd/O_NOFOLLOW 原语，O_EXCL 创建，不创建目录、不覆盖旧文件、不跟随链接。
- 并发不同任务隔离；重复任务/旧目标拒绝覆盖并警告。
- 越界、../、符号链接、已有文件、缺失父目录或权限失败：任务返回 tuple/退出码/终态保持原值；logging WARNING + 内部审计 hand_log_copy_failed，日志只记 task hash 和异常类型，不带路径/正文/凭证。
- 缺失目录/符号链接/越界/并发等测试为实际文件/进程；PermissionError 测试通过注入模拟，不能当实际设备权限验收。
- 未定义 T6 新字段；wire 结果及 Dispatcher 不变。以后如需统一对外日志 warning 字段，交 T6/Muse 接线。
- 当前库内内置日志模板均在工作目录内；现网用户自定义的外部目录尚未收集验证，越界配置会有非致命告警。若提供实际外部路径兼容依赖，需单独协调允许范围。
- 复制中磁盘写失败可能留下该任务的部分新文件，仍有明确告警且后续不覆盖；不会因此把任务成功改成失败。

## 5. S1 矩阵逐项对应

| S1 | 统一后的入口/证据 |
|---|---|
| H01 | 原 RFC7748/seal/credential 测试；pending_policy 实际密封连接、host_chain 加密调用 |
| H02 | 原 fingerprint/key/audit 测试；runner_logs 内部审计；admin_webhook healthcheck |
| H03 | 原 hello/批准/公钥替换/撤销测试；pairing_mdns 与 pending_policy |
| H04 | pairing_mdns：真实密封确认、错码/轮换/锁定/撤销；pending_policy 两策略合法配对 |
| H05 | config_policy：注册表/旧 command 冲突，发现可用性；compatibility init 默认 |
| H06 | 原 hello_agents_include_git_repos/non_git 测试；所有注册/旧行都输出真实 repos |
| H07 | config_policy：standalone 发现不授权、grant 后授权；bundled 审计；client grants 隔离 |
| H08 | 原授权矩阵/raw byte fixtures；compatibility 两策略回执/重复授权 |
| H09 | 原 list/read/write/穿越测试；pending_policy 真实拒绝无副作用；MCP S4 测试见下 |
| H10 | 原凭证/owner/goal/epoch/attempt 检查与 pause/stop 测试；host_chain |
| H11 | runner_logs：真实子进程、退出码/超时/输出排空、远端/MCP 两入口 |
| H12 | 原 dead attempt/keepalive/断连/Hand crash watchdog 测试全部保留并通过 |
| H13 | 原控制测试；host_chain 真实父/子进程停止态、稳定写入观察及有界时延 |
| H14 | 原 artifact integrity/retry/隔离测试；host_chain 实际21-byte产物回读摘要 |
| H15 | ws：RFC向量/掩码/分片/ping/close/帧长度、队列有界/身份隔离；原 HTTP upgrade 门禁测试 |
| H16 | ws：票据过期/重放/撤销；原 paired upgrade 门禁仍通过 |
| H17 | admin_webhook：真实临时文件 stage/apply/rollback，确认/hash/语法拒绝；exit/watchdog 以 mock 隔离；原始 argv 精确保留测试 |
| H18 | pairing_mdns：DNS报文/压缩/查询响应；host_chain：真实 UDP loopback 报文发现；非实际局域网 mDNS 验收 |
| H19 | config_policy：注册/覆盖/启停/删除/摘要 CLI，设计/缺口标签；真实文件 overlay |
| H20 | config_policy：当前 Python 二进制发现、命令冲突；CLI discover 逻辑保留；第三方 provider 登录未实测 |
| H21 | mcp_surface 显式测试 internal 拒绝且不 spawn；未新增 App 引擎，不可称 App 落册已完成 |
| H22 | admin_webhook：实际 loopback HTTP 发送/HMAC、默认 HTTPS门禁；完整派单接线按 Muse 决议排除，gap 保留 |
| H23 | admin_webhook：真实 key/监听端口 healthcheck；plugin CLI 保留 |
| H24 | runner_logs：本机 MCP 真实 run/日志副本/失败不改成功；mcp_surface：真实 stdio JSON-RPC、list/read/write/越权/链接拒绝 |
| H25 | compatibility：两策略 init 不覆盖/字段顺序/默认command；config_policy CLI 注册；原通用CLI保留 |
| H26 | WACF design-only/key/name/gap 原样保留，加未实现注释；按 Muse 决议排除执行验收 |
| H27 | 外部登记数据保留；其 status/verified 不是本轮真机证据，不连接旧平台 |

注意：本表有意区分已实测、mock 和仍待实测；不将条目“保留源码”自动写成真实设备全验收。

## 6. S6 Host 完整链路

对应最终测试 commit 4901d4c8fc38d2db097c43b424154da5ab90e5b6（Host 测试最初提交为1457d22），source SHA-256 如上。
真实步骤：UDP loopback DNS 报文→核对 hello 指纹→密封配对→TCP 派发实际 Python worker→暂停父/子进程→恢复写入→停止进程组→结果回读。

| 物理入口 | 策略 | 暂停 ms | 恢复 ms | 停止 ms |
|---|---|---:|---:|---:|
| hand_server | standalone | 5.793 | 6.052 | 6.610 |
| hand_server | bundled | 8.246 | 8.255 | 8.745 |
| bundled_hand_server | standalone | 5.761 | 6.420 | 6.637 |
| bundled_hand_server | bundled | 7.829 | 8.098 | 8.360 |

每例暂停观察3秒无新增写入，恢复后两进程继续写；停止观察5秒无新增写入，父进程退出、子进程不再运行。符合本轮 Host 的暂停/恢复≤2秒、停止≤5秒断言，不能外推为所有网络/设备时延保证。

停止任务终态 remote_controlled_stop，停止任务回读被 revoked_credential 拒绝；另一个成功执行轮次实际生成21-byte产物并回读，摘要 c200a28baca7f4428dd9d1c7f3b8cf4a8d6cdba30f7319bc6ae6cef2d351b6e8。
没有伪造停止任务的成功产物，没有改变状态机。

## 7. 明确未验证/未完成

- Android 真机/模拟器 UI、NsdManager 实际组播发现、真实 App 派单全链、截图：未验证。本环境仅有 GitHub API/CI 执行通道。
- 真正模型/provider CLI 登录与执行、现场 Tailnet 网络：未验证；Host worker 是真实执行的确定性测试程序。
- Windows/macOS 服务安装、launchd/systemd/scheduled task 自更新与重启：未在设备验证；当前测试隔离进程退出与 supervisor 行为。
- WACF 实现和 webhook 完整派单接线按既定裁定不做、不冒充完成。
- webhook 现有发送器的公网 DNS/重定向全安全审计不在已通过结论中；本轮验证默认 HTTPS/IP 前置门禁与真实本地签名发送，不宣称完整公网 SSRF 闭环。
- Muse 尚未出具 S3c/T4 人工验收，未称已批准、未合并或发布。
- T6 没有在本任务定义新对外 warning/result 字段；后续需遵循 T6/Muse 批准方案。

可执行的开发验证已继续完成，没有因缺少真机停下其他工作。原始 T4 设备验收仍需 Muse 在测试设备完成，并以本文 source 版本、测试命令和剩余条件核验。
