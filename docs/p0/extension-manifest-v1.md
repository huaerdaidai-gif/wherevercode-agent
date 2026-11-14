# 扩展清单 v1

原生入口：抽屉 → MCP / 插件 → 扩展清单 v1 → 导入 JSON → 预览 → 保存。

```json
{
  "schemaVersion": 1,
  "id": "example-agent",
  "kind": "agent",
  "name": "Example Agent",
  "version": "1.0.0",
  "permissions": ["workspace.read", "task.create"]
}
```

`kind` 精确取值：`agent` / `model` / `harness` / `mcp` / `skills` / `automation` / `network` / `device` / `ui_extension`。

声明权限白名单：`workspace.read`、`workspace.write`、`process.execute`、`network.connect`、`device.pair`、`task.create`、`task.control`、`ui.render`。不得出现重复或未知权限。未知 schema、未知字段、非字符串权限、不合法 id、超长名称及超过 32768 字符均拒绝。

`id` 在 kind 内唯一，禁止悄悄覆盖。版本为三段数字。数据保存在本 App 私有 `extension-manifests-v1` preferences，目录无 credentials、command 或 endpoint 字段；这些设置继续走现有 Keystore/配对/MCP 配置。清单与既有 ExtensionRegistry 七类运行时目录分开，避免把声明误当已可用执行器。

此版清单是最小可用元数据管理，不提供任意代码加载。后续激活适配器必须把声明交给现有 ApprovalGate、WorkspaceGuard、配对 scope 和任务 epoch 检查；登记状态不能被用作授权。启用 Harness/Automation/Network/UI Extension 执行器仍为 MISSING，不能仅凭导入成功就展示在线或开始工作。
