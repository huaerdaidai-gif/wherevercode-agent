# WhereverCode V5 Android/iOS UI 组装基线 v2

## 已交付
- 原样保留用户上传的 70 路由 Kotlin Compose、SwiftUI、HTML、原验收表。
- 四父级菜单交互 HTML：对话 / 工作台 / 控制台 / 设置；首页保持极简。
- 70 路由接线清单、双端验收 CSV、UI 状态语义 JSON。

## 重要边界
这是设计与接线准备包，**不是已接通 API 的 APK/IPA**。没有读取最新 GitHub 实际代码，没有真实端点映射、鉴权测试、编译或真机验收。原生源码来自上传包，不能未经适配直接覆盖现有 App。

## 组装顺序
1. 对照 GitHub wherevercode-agent 当前分支，确定 Activity/SwiftUI App 入口与真实 API。
2. 按 route_id 把 UI 接口接到现有 CapabilityProvider，真实服务缺失时保持禁用/待开发状态。
3. 状态机区分任务状态、暂停状态、交付回执；权限必须服务端校验。
4. Android 和 iOS 共用路由 ID、文字、行为和错误态规范。
5. 完成构建、签名、真机截图、端到端回归后逐项更新 route_acceptance.csv。

## 原始包说明
参见 original_70_screen_source/README.md。
