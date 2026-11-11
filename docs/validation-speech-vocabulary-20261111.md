# App 可配置语音词表验证（2026-11-11）

基于 `origin/main` 的 `9257ed8`，在 `codex/configurable-speech-vocabulary` 独立工作树开发。

## 行为

设置页新增“语音输入 → 识别词表”。原服务端词表迁为 App 可编辑初始值，按设备保存，切换节点不丢失；支持保存、清空和恢复默认，输入超过 395 字符时禁止保存。每次录音开始时读取并固定当时的配置，以现有 `context` 参数发送；词表优先保留，剩余空间附加草稿尾部，总长不超过 400 字符。录音期间修改配置不影响该次识别。

ASR 不再追加任何固定词表。每个流独立保存请求上下文，实时和最终识别及回显过滤均使用该次请求数据；清空词表不会继续使用原词。配置为单个词时，正常说出该词不再因与整个词表相同而被当作回显删除。

## 检查结果

- Android：VoiceTranscriptionTest 10 项、SpeechVocabularyTest 3 项、SpeechRepositoryTest 4 项、GatewaySpeechClientTest 5 项，共 22 项通过；debug Kotlin 与 AndroidTest Kotlin 编译通过。
- ASR：适配器 HTTP 与词表/流隔离回归共 10 项通过。使用模拟模型对象，不加载真实模型；覆盖请求参数、缺省/空上下文、非法长度、文件转写、不同流交错的实时与最终识别，以及回显过滤。
- 模拟器：独立只读 `medium_phone`（Android 16）执行 SpeechSettingsUiTest 1 项通过，验证编辑保存、重新打开后读取、清空后读取空值及超长输入不能保存。测试后关闭本轮启动的模拟器。
- `git diff --check` 通过。未运行 lint，未进行真机麦克风录音或真实模型识别质量验证；生成的 debug APK 仅用于模拟器测试，不作为发布包。

上述验证阶段未提交、推送或部署。生效需同时更新 App 和 ASR 适配器；App 单独更新无法关闭旧 ASR 服务写死的词表。无需修改模型或重新配对，不重启 Codex 原生运行时。
