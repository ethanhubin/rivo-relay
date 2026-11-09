# Rivo Relay Android

Android 客户端使用 Kotlin、Compose、StateFlow 和 OkHttp，单个 `:app` 模块，包名 `dev.rivo.relay`，最低支持 Android API 26。

## 构建

需要 JDK 17、Android SDK 37，配置 `JAVA_HOME` 和 `ANDROID_HOME` 后执行：

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

APK 位于 `app/build/outputs/apk/debug/`。设备测试包使用 `:app:assembleDebugAndroidTest` 构建，实际执行范围见[验证记录](../../docs/validation.md)。

## 模块职责

- `connection`：扫码与手动配对、Android Keystore 凭据存储。
- `data`：HTTP、SSE 和原生数据投影。
- `codex`：项目、会话订阅、历史分页与提交结果核对。
- `ui`：项目、聊天、审批、待办和文件交互。
- `AppState` / `App`：节点生命周期和导航。

## 会话与附件

首次发送时创建会话，草稿按节点保存。写入使用提交 ID，断线后核对原提交结果。会话权限根据节点最新状态显示，外部占用时可预览接管影响范围。

图片支持 JPEG、PNG、WebP，最多 10 张，单张 20 MiB、合计 40 MiB；可裁剪和标注。手机文件通过系统选择器上传，最多 16 个，单文件上限 1 GiB。工作区文件可直接浏览并引用。

文本预览上限 4 MiB，界面最多显示 200,000 个字符；完整文件可保存到手机。语音输入使用节点配置的 ASR。

## 权限与更新

新会话默认使用节点项目配置。权限菜单提供“请求审批”“自动审批”“完全访问”，所选预设作用于会话。项目自定义权限在节点 `.codex/config.toml` 中维护。

“设置 → 检查更新”使用主节点更新源，下载后校验大小与 SHA-256，再打开系统安装界面。发布流程见[节点更新](../../docs/node-updates.md)。
