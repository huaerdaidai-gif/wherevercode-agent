# V5 Android 组装交付（首批，未达到全量产品验收）

本批已完成上传包审计、四父级原生导航与已有能力桥接，**不是 70 个完整业务功能全部接通**。没有配置真实模型/账号/远端设备，不能声称真实后端联测通过。

- 分支：`android/v5-ui-assembly`
- Android 集成基线：`5f123cc`（比 main 更新的现有 Android 70 路由分支）
- APK 源码提交：`67c1102`；后续提交仅记录证据时不重建 APK。
- 主线审计、原包差异：[AUDIT.md](AUDIT.md)；原包逐文件大小与 SHA：[baseline-inventory.json](baseline-inventory.json)。
- 开发前 70 项差距：[audit-70.csv](audit-70.csv)：27 可复用但需接线、28 需要开发、15 存在阻塞。
- 当前 70 路由接线：[wiring-70.csv](wiring-70.csv)、[JSON](wiring-70.json)：33 个局部已有能力适配、37 个诚实缺口页。功能入口可达不能替代业务验收。
- 具体请求/响应、权限和控制回执：[actions.csv](actions.csv)；实际客户端源码证据：[api-evidence.txt](api-evidence.txt)。没有虚构 HTTP 端点。
- 未完成链路、接口缺口与 iOS 差异：[BLOCKERS-AND-IOS.md](BLOCKERS-AND-IOS.md)。

## 代码行为

在原 MainActivity 中嵌入 Compose 四父级抽屉和分组/搜索/详情导航。首页只留两个顶栏按钮、星芒问候与原有输入区。保留 XML 业务页面、ChatViewModel、任务执行器、审批门、鉴权、数据表与旧抽屉偏好；没有替换整份 Activity 或改动 iOS、server、共享 API、CI 和生产配置。变更范围按 `5f123cc..android/v5-ui-assembly` 审查；与 main 比较会同时显示基线原有历史。

继续会话不再触发新建；任务创建直接调用原表单；未配置能力没有伪造响应；历史设备使用描述不再标为当前构建 READY_VERIFIED。列表落点必须先选实体，终端仅现有日志，Diff 仅保存前文本差异，均有明确说明。设置保留主题和帮助入口。

原生实测发现并修复了任务详情停留在创建时状态的问题：可见且 resumed 时每 2 秒读真实快照，离页/后台停止；丢弃过期响应，保留滚动和展开区块。控制台打开详情时将详情层置顶。原生回归要求可见状态与持久化终态一致后才截图。

## 构建与 JVM 回归

环境：Linux、JDK 17、Gradle 8.0、Android SDK 34、Build Tools 33.0.1；工具安装于本次临时环境，不写入仓库。

```bash
python scripts/export-v5-wiring.py
python -m unittest server.test_hand_server -q
gradle -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  -PandroidAbis=arm64-v8a,x86_64 --console=plain
```

- Hand 服务本地测试：23 通过。
- JVM：2,736 项，2,735 通过、1 跳过、0 失败；310 suites。跳过项是 `KeystoreSiwcTokenStoreTest` 的真实 Keystore round-trip，不将其算作通过。
- 首轮两项设备刷新测试依赖旧抽屉 View tag，已迁移至受支持的 V5 深链，保留 3 秒刷新与滚动位置断言；保留首轮失败证据。
- APK 与测试 APK 构建成功，应用 APK 通过 apksigner v2 验证。
- 完整记录：[build-and-jvm-tests.txt](evidence/build-and-jvm-tests.txt)、[JVM 分套件统计](evidence/jvm-summary.json)、[APK 摘要](evidence/apk-manifest.json)。
- APK：`android/app/build/outputs/apk/debug/app-debug.apk`，30.9 MiB，ARM64 + x86_64，包名 `wherevercode.agent.ci`，本机 Debug 签名。签名私钥未提交。

原生导航 70/70、任务创建/领取/结果与可见状态同步、控制台任务钻取均已验证；结果与截图见 [device-results.md](device-results.md)。

## 风险与回退

未提供原始 PNG/JPEG 首页截图，不能声明像素级通过。37 个路由仍是缺口详情页，33 个适配也多为已有能力的一部分；完整自动化/同步/成本/插件安装/预览服务等仍受阻。无真实模型与设备配置，远端控制、SSE/WS 重连、跨设备权限和数据迁移未进行真实环境联测。

四父级导航忽略旧版抽屉隐藏/收藏呈现，但原数据表保留；新增 Compose 与原 View 并存，需继续验证旋转恢复、无障碍和不同屏幕/深色主题。

无需任何生产回滚。本批仅独立开发分支与侧装 `.ci` 包；代码可切回 `5f123cc` 或在集成分支逐提交 revert，原应用包名及数据未覆盖。卸载测试包会删除其本机测试数据，勿用于替代备份。

原始上传包按字节保留（包括 CSV 的 CRLF 与原格式）；代码及本批生成文件的空白检查与原包快照分开执行。
