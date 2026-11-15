# T4 init-agents 与回执键序独立分析（供 Muse 审查）

基于任务分支 S3f 16756cf8184b7c5e6d72d6d00fd8297f95dedd82；未改 T6、Kotlin、状态机、旧测试断言或 App。

## 1. init-agents 不是键序冲突

两份 main 的 init-agents 都写 agents.json，display_name/kind/capabilities/folder/command/env 顺序一致，已有文件都不覆盖。差异是默认 command：
- A：/workspace/example-project/run-grok.sh {prompt}
- B：/usr/local/bin/grok -p {prompt} --always-approve --sandbox workspace --output-format plain

它是后续执行配置，不能当作无关空白去覆盖。最小兼容方案：同一字典中根据显式 config-policy 选择 command；standalone 保留 A，bundled 保留 B。两种初始化都不覆盖已有配置。新增 CLI subprocess 测试两种策略各自的内容、字段顺序及不覆盖旧文件。注册表已有 ID 时的 command 优先级继续服从 S2 已批准策略，不新引入 wrapper 或执行器。

## 2. 授权回执的原始字节顺序确实不同

A _do_authorize_project_folder：authorized → requested_folder → agent_id → already → folder。
B：authorized → agent_id → requested_folder → folder → already。
值相同，json.dumps 的原始输出不同。两个来源还有私有方法参数位置和审计 event 名称差异；最终要保留实际策略语义，而不是依赖模块/文件路径分叉。

实际证据：
- server/test_hand_server.py::test_authorization_receipts_match_android_kotlin_fixtures 为 source/bundled 各固定两条原始字符串（首次与重复授权），assertEqual 不得删除或改值。
- android/.../core/project/ProjectFolderAuthorizationPathTest.kt 同时接受 SOURCE_HAND 和 BUNDLED_HAND，按字段检查身份、requested_folder、canonical folder、already；不按 JSON 键位置取值。
- ProjectFolderAuthorizationReceiptStore.kt 使用 JSONObject 及解析后的字段，保存 sha256(rawAck) 作为证据摘要；没有把该摘要当统一固定常量来校验。修改原始字节仍会改变摘要，因此不能只以“JSON 无序”略过已有兼容证据。

## 3. 最小保留方案

- 统一一个授权函数；按 config-policy 保留上述两种字节顺序和各自审计事件名。
- 共用目录验证、grant 存储及客户端隔离；不按 __file__、__name__ 或资产路径选协议。
- 生产默认仍为 standalone；安装脚本明确 bundled（S3b 已实现）。
- 旧内置版测试装载器应显式注入 bundled 默认策略，模拟 App 安装入口；用真实 HandServer 的 functools.partial，调用方显式 config_policy 参数仍可覆盖。只调整测试 setup，不改任何旧 expected 字符串或断言。
- 新增参数化测试：同一个物理源码在 standalone/bundled 各回完全相同的旧字节，首次/重复授权均覆盖；再对两种策略各做越权拒绝。
- 直接导入与 stdio/CLI smoke 测试验证物理资产默认仍为 standalone，防止测试 fixture 悄悄改变生产默认值。

此方案保持原有两种部署入口的外部原始字节，不新增对外字段、端点或结果状态。已向 Muse 提交此方案的协调问题；若判定任一原有合法调用会发生破坏性变化，暂停该部分，继续其他不相关测试/迁移。不宣称 Muse 已验收。

## 4. log_path 信任与 T6 说明

log_template 是设备本机 extensions.json / 内置注册表配置；adapter_log_path 对 task_id 做 SHA-256；远端和本机 MCP 请求没有直接传 log_path 的参数入口。已知内置模板位于工作目录中。当前仓库未提供现网自定义日志目录的证据，不能宣称已验证所有设备自定义路径。

S3d 当前实现仅允许 cwd 内 task hash 绑定新文件，不跟随目录/目标符号链接、不覆盖已有文件，0600；失败写 logging WARNING 和可选 state.audit 内部事件 hand_log_copy_failed，不改变执行 tuple、返回帧或任务状态。自定义模板越界/缺 hash/目录缺失会明确警告而非静默成功。不存在此前真实写出的日志功能可据以推断外部路径依赖；如设备给出此依赖证据，提交具体兼容裁定再扩范围。

T6 branch/PR 搜索未找到已批准的专用字段方案，故没有增加对外 wire 字段。后续统一接线所需概念：执行状态、日志附加写入状态、非致命警告 code/message。字段名称与位置留给 T6/Muse，不自行定第二套协议。
