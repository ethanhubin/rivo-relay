# 发布约定

- 用户说“发布”“发布新版”时，默认只发布到主节点 Tailscale HTTP 更新源 `http://100.124.150.1:18767`，不创建 GitHub Release、不推送远端或 Git tag。
- 仅当用户明确要求“发布 Release / GitHub Release”时，才同步到 GitHub。用户单独明确要求提交或推送时，按其具体指令执行。
- 默认发布使用 `scripts/publish-update.py`，更新源位于 Relay workspace 的 `updates/`；先发布以 SHA-256 命名的 APK，再原子更新 `latest.json`。不覆盖同 versionCode 的不同 APK，不降级。
- 发布前递增 Android versionName / versionCode，运行相关回归测试、构建和 lint，确认签名与上一版一致。需要服务端改动时更新主节点并验证 HTTP 清单与 APK；不重启 Codex 原生运行时。
- GitHub 发布只在显式授权时使用 `--github-release`。发布说明标明真机/模拟器验证范围。
