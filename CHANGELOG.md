# 更新日志

本文件是版本更新内容的唯一来源：发布时会被抽取成 `update.json` 的 `releaseNotes`，
既显示在 GitHub Release 说明里，也显示在应用内“发现新版本”的弹窗中。

发布前请把 `Unreleased` 段改名为 `## [x.y.z]`（与 `app/build.gradle` 的 `versionName` 一致），
发布流程会按版本号抽取对应段落；若找不到该版本段落，会退回到 `Unreleased`，两者都没有则发布失败。

## [1.4.1]

### 新增

- 单元测试基础设施与单元测试：账号目录增删改查与旧偏好迁移、Deadlock API 字段兼容层、胜负判定与英雄统计聚合、更新清单校验、SteamID64 换算。
- 新增 CI 工作流（push 与 PR 均运行单元测试并构建 debug APK）；发布流程在打包前也会先跑单元测试。
- `update.json` 的生成与校验统一到同一份契约：本地 PowerShell 脚本与 CI 两个生产者使用同一套规则，并有测试用例钉住“生产者产物能被应用解析”。
- 网络层缓存：API 响应走内存 TTL 缓存（玩家数据 60 秒、英雄资源 12 小时），英雄资源同时落盘缓存，断网冷启动仍能显示英雄名称与图标。
- 自动检查更新加了频率限制：默认 6 小时内最多请求一次 `update.json`，应用内手动“检查更新”不受限制。

### 修复

- `DeadlockApi` 与 `SteamProfileResolver` 请求头中的 User-Agent 版本号此前分别写死为 `1.0` 与 `1.4`，现在与 `UpdateClient` 一致，统一取自 `BuildConfig.VERSION_NAME`。
- 发布流程新增校验：标签必须与 `versionName` 一致、`versionCode` 必须大于上一个发布标签的值，避免产出用户永远收不到的更新。
- 自动更新检查此前每次启动都会请求一次 `update.json`，现在受频率限制约束；手动刷新战绩会绕过响应缓存，保证拿到最新数据。
