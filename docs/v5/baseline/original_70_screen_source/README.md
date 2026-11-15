# WhereverCode V5 原生双端 UI 源码（70 路由）

**这是可集成的 UI 源码，不是已编译的 APK/IPA。**

- `android/src/main/java/wherevercode/agent/ui/flagship/FlagshipApp.kt`: Jetpack Compose 70 路由、抽屉、搜索、五大主导航、控制台状态、待开发页。
- `ios/WhereverCodeAgent/FlagshipUI/FlagshipApp.swift`: SwiftUI 同路由和状态规则。
- `routes.json`: 70 个页面的唯一清单。
- `acceptance.csv`: 原逐屏验收矩阵。
- `reference.html`: 70 屏设计基线。

## 集成方式

1. 先确认现有 Android Gradle、Compose 依赖、包名和入口 Activity。将 Kotlin 文件合入现有模块并在 Activity 的 `setContent { FlagshipApp(provider = existingAdapter) }` 中调用；不要直接覆盖已有 Activity。
2. iOS 在**确认现有 Xcode 工程**后将 Swift 文件加入 target，根视图调用 `FlagshipApp(capabilities: existingAdapter)`；若无工程，先建立真实工程和签名配置。
3. `FlagshipCapabilityProvider` 是接入点。未接线默认 `PlannedCapabilities`，所有操作按钮禁用；接入真实服务、留有设备验收证据才可标 `READY_VERIFIED`。
4. 不要用 UI 代码替代聊天、文件、任务、模型、权限等业务实现。必须由现有服务/本地实现接线，逐项验收。
5. 此包没有更改 GitHub 仓库，没有执行编译、签名或真机测试。

## 边界

不包含凭据、签名文件、APK/IPA。禁止在没有旧版签名兼容性证据时原位覆盖安装。
