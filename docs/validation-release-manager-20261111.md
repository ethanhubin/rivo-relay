# 管理节点初始化验证（2026-11-11）

范围：一次性补齐本机发布环境，复用原发布 CLI；新发布从最新 `origin/main` 的独立工作区构建，恢复沿用原提交。未发布新版、未重启节点、未提交或推送 Git。

- `test_release_cli`、`test_release_asr`、`test_release_management` 共 47 项通过；审查补充用例先复现构建文件可夹带非版本修改的问题，收紧检查后通过全部相关回归。
- 本机 `setup-manager` 完成，独立 Python、Android SDK、JDK、已有签名通过检查；VPS SSH、证书固定校验及本机 ASR 检查通过。
- `:app:assembleDebug` 成功，使用原有 Gradle 缓存配置，未运行 lint。干净 SDK 的 Android 37.0 平台需要 AGP 9.1.1，因此仅从 9.1.0 更新该补丁版本，依据 [Android 官方兼容表](https://developer.android.com/build/releases/about-agp#api-level-support)。
- 本次环境验证 APK 为 `dev.rivo.relay` / `0.2.108` / versionCode `120`，大小 19,625,639 字节，SHA-256 为 `c7a11b70e8bb28e5ea6c3108107f066397d1fb47f8031478335839f6f0d5a8a8`；与 VPS 上一版 `0.2.116` 的签名一致。该 APK 仅验证构建环境，未上传，也未递增发布版本。
- `git diff --check` 通过。未进行真机或模拟器验证，未演练真实 VPS 写入；来源锁定、恢复、兼容镜像和失败处理使用隔离回归测试验证。

本机入口为 `~/.local/share/rivo-release-manager/bin/rivo-release`。配置、签名、安装检查及 APK 验证记录保存在忽略目录，不提交 Git。本功能合并到 `main` 后，才能按新的来源规则准备正式发布；日常发布不重复执行整套环境诊断。
