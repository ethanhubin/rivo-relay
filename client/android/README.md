# Rivo Relay Android

单个 `:app` 模块，包名 `dev.rivo.relay`，不会覆盖 QiGuang。采用 Kotlin、Compose、Moonlight 主题、StateFlow 和 OkHttp。QiGuang 的来源与调整记录见 [migration-sources.json](migration-sources.json)。

## 构建

需要 JDK 17、Android SDK 37。设置 `JAVA_HOME` 和 `ANDROID_HOME` 后，在本目录执行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

应用位于 `app/build/outputs/apk/debug/app-debug.apk`；测试包位于 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。Release 配对要求 HTTPS；Debug 只允许 localhost、127.0.0.1、10.0.2.2 的 HTTP，适用于本机模拟器联调。

## 职责

- `connection`：扫码/手动共用配对解析，Android Keystore AES-GCM 凭据存储。
- `data`：无写入重试的可取消 HTTP、SSE 和原生数据投影。
- `codex`：项目目录、会话订阅与历史分页、提交身份与结果核对。
- `ui`：登录、项目、会话、聊天、原生审批/提问、待办；系统选图和节点文件只读预览。
- `AppState`/`App`：节点生命周期和导航组合，不负责原生协议业务。

扫码使用 ZXing 的真实相机帧解码。系统 Photo Picker 的图片草稿只在内存中保留，经方向修正和压缩后以原生 `image` 输入发送；没有额外媒体上传服务。未知原生消息保留内容，无法处理的请求明确提示在 Desktop 继续。

写入先保存提交 ID，再发送一次。断线后只核对原 ID，不自动重发。SSE 用来触发原生快照读取；暂停应用时保留会话身份与草稿，恢复后重新核对。文件引用通过节点原生文件接口读取，不将节点路径交给手机浏览器。

聊天展示沿用 QiGuang 的消息规则：问答协议包装显示为问题和回答，同轮连续工具活动合并为可展开摘要，过程消息与最终回复保持顺序。输入区随草稿增长至三行，图片、手机文件和节点工作区文件从＋菜单添加；移除手填路径的“引用”入口。迁移来源、严格解析边界及保留差异见[消息展示对照](../../docs/qiguang-message-parity.md)。

活动项目沿用 QiGuang 的环绕扫光，由节点返回的原生活动计数驱动；首页可见期间每 5 秒更新，离开或进入后台停止轮询。计数规则和动画来源见[项目扫光对照](../../docs/qiguang-project-activity.md)。

待核对时冻结原草稿，核对成功才完成原提交。若长期无法确认，可先查看原会话，再明确确认“结束核对”：只解除本机等待，不取消、重发或删除节点记录；旧成功回调同时失效。`404` 仍表示暂未查询到记录，不能自动推断原请求未执行。

## 验证

`ClientUiTest` 是独立 UI/Keystore 验证，可直接运行整个类。`MessageComposerUiTest` 检查输入区高度、三行增长和窄屏操作；`ChatRenderingUiTest` 使用原生历史样例检查问答解析、命令分组和键盘布局，不连接节点。`LiveSmokeTest` 会连接真实节点和调用真实 Codex；各方法按前置状态单独执行：

1. `qrCameraPairing`：未登录，节点二维码已摆在真实或模拟器相机前，验证扫码至项目列表。
2. `nativeConversationRoundTrip`：已配对，节点存在 `Rivo Relay Validation` 项目，创建会话并等待真实回复。
3. `todoCrudAndRealDispatch`：已配对，同一隔离项目，验证待办增改、交付、真实回复、完成与删除。
4. `manualPairingFromInstrumentationArguments`：未登录，通过临时 `nodeAddress`、`pairingCode` 参数验证备用入口。
5. `nativeFilePreview`：已配对，通过原生文件引用打开节点文件并断言真实内容；默认使用经原生接口确认的隔离项目 `preview.txt` 和 `RIVO_NATIVE_FILE_PREVIEW`，可用 `previewPath` / `previewExpected` 替换。
6. `systemPhotoPickerAndNativeImageRoundTrip`：创建测试红图到 MediaStore，启动真实系统选图器，通过可见缩略图点选，验证待发预览并发送原生图片输入，等待 `RED` 回复；结束后删除本测试创建的媒体。
7. `offlineHomeKeepsLogin`：宿主先停止隔离 Relay，再冷启动验证项目首页及离线提示，登录凭据保留。
8. `revokedConnectionReturnsToLogin`：宿主先撤销该测试连接，再冷启动验证回到扫码登录及授权失效提示。
9. `existingTodoAssociationAfterRestart`：宿主先重启自己的 Relay，通过 `todoTitle` 指定已有隔离关联待办；冷启动搜索并查看历史 `RIVO_TODO_OK`，不创建新模型任务。

`CameraDeniedTest` 不使用自动权限授予规则。宿主先撤销测试包的相机权限，测试点击真实系统权限弹窗的拒绝按钮，验证错误提示及手动入口的输入回调；宿主随后恢复权限。

`projectName` 可覆盖隔离项目名称；配对码不得写进源码或测试结果。二维码等待上限 300 秒，用于 headless 模拟器相机姿态调整；它没有接收预解码结果的测试入口。

真实流程成功后，测试将截图保存到设备的 `/sdcard/Android/data/dev.rivo.relay/files/verification/`：项目、隔离测试会话、原生文件、系统选图器、离线状态及不含配对码的授权失效页面；不截图或输出节点令牌。截图不能代替原生端的共享会话与数据证据。

## 当前协议边界

已提供原生命令/文件修改审批、权限请求、密码遮蔽的问题输入、MCP 简单类型表单和 URL 交互。复杂嵌套 MCP / OpenAI 表单及 `openai/userVerification` 需要在 Desktop 完成；界面明确提示，不让用户填写协议 JSON。未知原生请求与未知历史 item 不会被当成已处理。

文件预览通过原生节点 FS，只读，预览响应上限 4 MiB；独立下载支持 1 GiB，不受预览限制。手机显示文本前 200,000 个字符，并说明截断；不支持的二进制格式明确提示在 Desktop 查看。原生 `text`、`image`、`mention` 输入有正常 UI；历史 `skill` 输入仍可查看；音频输入尚未提供采集 UI。图片草稿最多三张，单张压缩为不超过 1 MiB 的 JPEG。

模拟器真实扫码、文本会话和待办交付已由实际节点执行并获得回复；测试是否整例通过及最终截图以根目录验收记录为准，不能把测试包成功构建记作流程已通过。

## 附件更新（2026-10-29）

待发送图片点击缩略图进入 QiGuang 编辑器，支持裁剪、箭头、矩形、圆形与颜色选择；完成替换草稿并更新缩略图，取消不修改原图。编辑仍在内存中完成，发送图片沿用 1 MiB JPEG 限制。

手机文件使用系统文件选择器，支持任意文件类型，单个最多 1 GiB、最多 16 个文件附件。选择后通过认证接口上传至当前原生会话工作区 `.rivo-attachments/<随机 ID>/<文件名>`，不覆盖同名项目文件。文件以原生 mention 输入提交，不把二进制伪装成文本；实际解析能力取决于模型与节点工具。节点工作区文件直接浏览目录并选择，无需上传或手填路径。

手机文件上传使用原生 `fs/createDirectory` / `fs/writeFile`，包括远端 daemon；不写 Relay 宿主来冒充节点文件。已上传文件保留以供历史和队列读取，移除草稿附件不会删除节点副本。上传不自动重试，失败可重新选择。节点接口需与客户端一并更新。

新增 `AttachmentEditorUiTest` 和 `WorkspaceFilePickerUiTest` 覆盖编辑取消/完成、输出尺寸与目录选择。这次无连接设备，UI 测试仅编译，尚未执行真机验收。

## 文件传输修正（0.1.9）

文件弹窗增加“保存到手机”，使用系统保存位置选择器和带认证的流式下载，显示进度并在失败时清理不完整文件。APK、压缩包直接提供保存入口，不再尝试内联预览。其他文件预览超过 4 MiB 时提示保存，不再误报上传限制。

手机文件经有界临时缓存流式上传，完成或失败清理缓存，单文件最大 100 MiB。节点文件下载最大 100 MiB。Relay 到原生主机的大文件上传以 4 MiB 分块写入随机附件目录，再通过原生 command/exec 在同一工作区合并；路径作为独立 argv 传入，无 shell 插值，无 sandbox 或 permission profile 覆盖。合并后清除分块，失败清理本次上传目录。Windows 的 PowerShell 合并分支未在设备验收，macOS 已实测 100 MiB。

通过认证 POST /v1/download-links 可签发单文件、1 小时有效的浏览器下载链接，随机令牌仅授权该文件，不携带节点登录令牌。链接在服务重启或到期后失效，授权本身独立于签发时的登录会话；不应分享给无关人员。用于旧客户端更新的 APK 已验证可直接在浏览器下载。

## 0.1.10：1 GiB 文件传输

当前单文件上传、下载上限统一为 1 GiB（1,073,741,824 字节）。手机端采用缓存文件/输出流，节点 HTTP 上传流按块交给原生文件接口；不再先读完整请求和生成整份 Base64。下载通过原生默认权限的命令读取文件长度及 4 MiB 范围，再逐块发送 HTTP，预览也先检查大小，不会为预览小段内容读取整个大文件。POSIX 参数通过 argv 传入；Windows 路径经过 PowerShell 字符串转义并使用 EncodedCommand。未覆盖权限设置。Windows 分支暂无真实设备验证。

取消传输总时长 10 分钟限制，保留连接及读写空闲超时，支持慢速持续传输。文件失败不自动重试；临时上传分块由现有清理逻辑回收。真实本机节点经 HTTPS 完成整整 1 GiB 上传/下载并校验 SHA-256 一致。Android 的手机文件选择和保存交互仍未在设备上实测。
