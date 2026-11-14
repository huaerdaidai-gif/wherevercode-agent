# T4 S3a 进行中：防漂移红灯及测试清单更正

S2 070f0ca 已经 Muse 通过。附带条件：S3a 就接入 hand.yml 并取得真实红/绿证据；bundled 自动授权要审计，S4 明确测试 standalone 新发现目录不得自动授权，须先 grant。

## 分支隔离

重新核实集成头 2f5e8a87bac61d0b7507d17f00aa7ddb67f33d77；相对原基线 bd0e11ff 仅 ios/WhereverCodeAgent/Resources/Info.plist 改动。任务分支继续从 070f0ca 前进，不合入该 iOS 提交。

## 已有证据

提交：[3e5ddcc](https://github.com/huaerdaidai-gif/wherevercode-agent/commit/3e5ddcc0e3e884718623e50cc6692593a99ee911)
仅增加：
- scripts/sync_hand_server.py：默认或 --check 只读逐字节检查；显式 --write 原子生成。
- .github/workflows/hand.yml：push/PR/workflow_dispatch，先检查已提交文件，再在临时副本验证漂移/缺文件拒绝及重新生成。

真实 GitHub Actions：
- [Run 43628523648](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43628523648)
- [Job 132549713874](https://github.com/huaerdaidai-gif/wherevercode-agent/actions/runs/43628523648/job/132549713874)
- Verify committed bytes (read-only) 失败，退出码 1，错误为 Hand asset differs。
- source SHA-256：2897190482f31fb0220084fc9891d213ba3c28b231260b04bc7a7264037858cf
- asset SHA-256：8d2217aca1d8e49bd0d60ccf94f16be22ec6f523a16410c558d2b821bf9a9783
- 临时副本正反测试因前一步失败而跳过；不得宣称它们已通过。
- 没有忽略失败或自动写文件掩盖漂移。

S3a 尚未完成：没有绿灯，尚未生成统一资产；两份实现当前均未覆盖，避免丢功能。

## S1 测试判断更正

当前基线 server/test_hand_server.py 已存在 load_bundled_hand_server()，动态导入真实 assets/install/hand_server.py。以下测试类覆盖了 B 的部分行为：
- HandDeadAttemptCleanupTest：A/B keepalive、旧轮次、暂停/恢复、断连清理。
- HandArtifactIntegrityTest：A/B 产物完整性和写重试。
- BundledHandAuthorizationTest：授权矩阵、Android fixtures、WS upgrade 门禁、未授权目录拒绝。
- BundledHandOutputTest：内置 runner stdout 排空。

因此任务背景中的“内置版没有任何测试”以及据此形成的绝对判断需更正为“已有部分内置版测试；不能替代配对码、mDNS、本机 MCP、注册表等增量的完整覆盖”。S1 中“test_embedded_python_control.py 不是 B 的测试”仍成立，但不应据该单个文件推断整个仓库无 B 测试。本轮仅静态核对，尚未运行 Hand 功能测试。

## 待裁定的迁移顺序

S2 原顺序为 S3a 建生成关系，S3b–S3f 才迁入 B 功能。若在 S3a 就对当前 A 执行 --write，会删除 B 尚未迁入的功能；若保留 B，则真实逐字节检查必红。不能靠自动覆盖、跳过检查、临时白名单制造绿灯。

已向 Muse 提交两种具体调整：
1. 在 S3a 阶段先完整保留 B 实现并补齐 A 差异，达到并集和字节一致再转绿；后续分组落实配置策略、回归和加固。涉及原功能分组顺序调整。
2. 允许 S3b–S3f 分组迁移期间持续真实红灯，全部迁入后同步转绿；这会放宽“之后每组提交”前需绿灯的条件。

在收到顺序裁定前不做会丢功能的同步，也不将 S3a 标为完成。后续仍须保留所有 S2 取舍、两项登记例外、bundled 审计与 standalone 授权测试条件。
