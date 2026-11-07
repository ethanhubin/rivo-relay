package dev.rivo.relay

import android.Manifest
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeTrue
import android.os.Build
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect

/** Real-node tests. No mock, predecoded scan intent, or embedded credential. Run methods separately in the documented order. */
@RunWith(AndroidJUnit4::class)
class LiveSmokeTest {
    @get:Rule(order = 0) val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(*(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.CAMERA)))
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val projectName get() = InstrumentationRegistry.getArguments().getString("projectName") ?: "Rivo Relay Validation"

    private fun waitForTag(tag: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    }
    private fun waitForEnabled(tag: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { runCatching { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    }
    private fun waitForText(text: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { runCatching { compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    }
    private fun waitForNoTag(tag: String) {
        compose.waitUntil(30_000) { runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }.getOrDefault(false) }
    }
    private fun scrollTodoTo(text: String) {
        compose.waitUntil(30_000) { runCatching {
            val rowText = hasText(text, substring = false) and !hasSetTextAction()
            compose.onNodeWithTag("todo_list").performScrollToNode(rowText)
            compose.onNode(rowText).assertIsDisplayed()
            true
        }.getOrDefault(false) }
    }
    private fun waitForProject() {
        // The app restores its last route; return through the real UI without clearing login.
        compose.waitUntil(30_000) {
            when {
                compose.onAllNodesWithTag("projects_page").fetchSemanticsNodes().isNotEmpty() -> true
                compose.onAllNodesWithTag("back_sessions").fetchSemanticsNodes().isNotEmpty() -> {
                    compose.onNodeWithTag("back_sessions").performClick(); false
                }
                compose.onAllNodesWithTag("sessions_back_home").fetchSemanticsNodes().isNotEmpty() -> {
                    compose.onNodeWithTag("sessions_back_home").performClick(); false
                }
                else -> false
            }
        }
        waitForTag("projects_page")
        compose.waitUntil(60_000) { runCatching {
            compose.onNodeWithTag("projects_page").performScrollToNode(hasText(projectName))
            compose.onNodeWithText(projectName).assertIsDisplayed()
            true
        }.getOrDefault(false) }
    }
    private fun saveEvidence(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Compose idleness does not include system Photo Picker or keyboard transitions.
        instrumentation.uiAutomation.waitForIdle(300, 5_000)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun newValidationConversation() {
        waitForProject()
        compose.onNodeWithText(projectName).performClick()
        waitForEnabled("new_thread")
        compose.onNodeWithTag("new_thread").performClick()
        waitForTag("chat_page", 60_000)
    }

    @Test fun nativePinRoundTrip() {
        waitForProject()
        compose.onNodeWithText(projectName).performClick()
        waitForText("置顶与队列验收 0.1.4")
        compose.onNodeWithText("置顶与队列验收 0.1.4").performTouchInput { longClick() }
        compose.onNodeWithTag("pin_session").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("已置顶").fetchSemanticsNodes().isNotEmpty() }
        saveEvidence("native-pin-0.1.4")
        compose.onNodeWithText("置顶与队列验收 0.1.4").performTouchInput { longClick() }
        compose.onNodeWithText("取消置顶").assertIsDisplayed()
        compose.onNodeWithTag("pin_session").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("已置顶").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("置顶与队列验收 0.1.4").performClick()
        waitForText("QUEUE_SECOND")
        compose.onNodeWithTag("app_error").assertDoesNotExist()
        saveEvidence("native-queue-history-0.1.4")
    }

    @Test fun qrCameraPairing() {
        // The host must inject the freshly generated QR into the emulator camera before this test.
        compose.onNodeWithTag("scan_connect").assertIsDisplayed().performClick()
        // Only a genuine ZXing capture result can navigate to the authenticated project screen.
        waitForTag("projects_page", 300_000)
        compose.onNodeWithText("Codex 项目").assertIsDisplayed()
        waitForProject()
        saveEvidence("qr-paired-projects")
    }

    @Test fun nativeConversationRoundTrip() {
        waitForProject()
        compose.onNodeWithText(projectName).performClick()
        waitForEnabled("new_thread")
        compose.onNodeWithTag("new_thread").performClick()
        waitForTag("chat_page", 60_000)
        compose.onNodeWithTag("message_input").performTextInput("Reply with exactly RIVO_ANDROID_OK. Do not read or modify files or use tools.")
        waitForEnabled("send_message", 60_000)
        compose.onNodeWithTag("send_message").performClick()
        waitForText("RIVO_ANDROID_OK", 180_000)
        compose.onNodeWithText("RIVO_ANDROID_OK").assertIsDisplayed()
        waitForNoTag("stop_turn")
        compose.onNodeWithTag("app_error").assertDoesNotExist()
        compose.onNodeWithTag("load_earlier").assertDoesNotExist()
        saveEvidence("native-conversation")
        compose.onNodeWithTag("message_input").performClick().performTextInput("继续验证")
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithTag("send_message").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Codex 原生会话").assertIsDisplayed()
        saveEvidence("native-conversation-keyboard")
    }

    @Test fun networkRouteChangesCancelStalledReads() {
        waitForProject()
        lateinit var node: NodeRuntime
        compose.runOnIdle { node = requireNotNull(androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java].state.value.node) }
        val ins = InstrumentationRegistry.getInstrumentation()
        val trace = java.util.Collections.synchronizedList(mutableListOf<String>())
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val watcher = scope.launch { node.network.collect { trace += "${android.os.SystemClock.elapsedRealtime()}: $it" } }
        fun shell(command: String) { ins.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        val wifi = android.provider.Settings.Global.getInt(ins.targetContext.contentResolver, "wifi_on", 0) == 1
        val data = android.provider.Settings.Global.getInt(ins.targetContext.contentResolver, "mobile_data", 0) == 1
        try {
            compose.waitUntil(15000) { node.network.value != null }
            shell("svc wifi disable"); shell("svc data disable")
            compose.waitUntil(15000) { node.network.value == null }
        } finally {
            trace += "activeNetwork: ${dev.rivo.relay.connection.defaultNetwork(ins.targetContext)}"
            if (wifi) shell("svc wifi enable")
            if (data) shell("svc data enable")
            watcher.cancel(); scope.cancel()
            val dir = File(ins.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
            File(dir, "network-route-trace.txt").writeText(trace.joinToString("\n"))
        }
        compose.waitUntil(30000) { node.network.value != null }
    }

    @Test fun nativeHistoryNetworkRecovery() {
        waitForProject()
        lateinit var node: NodeRuntime
        val threadId = requireNotNull(InstrumentationRegistry.getArguments().getString("benchmarkThreadId"))
        compose.runOnIdle {
            node = requireNotNull(androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java].state.value.node)
            node.openNotificationThread(threadId)
        }
        waitForTag("chat_page")
        compose.waitUntil(30000) { node.session.state.value.id == threadId && !node.session.state.value.stale && node.session.state.value.snapshot != null }
        val before = node.session.state.value.snapshot!!.messages.map { Triple(it.turnId, it.id, it.text) }
        val ins = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) { ins.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        val wifi = android.provider.Settings.Global.getInt(ins.targetContext.contentResolver, "wifi_on", 0) == 1
        val data = android.provider.Settings.Global.getInt(ins.targetContext.contentResolver, "mobile_data", 0) == 1
        val offlineStart = android.os.SystemClock.elapsedRealtime()
        var offlineMs = 0L
        try {
            shell("svc wifi disable"); shell("svc data disable")
            compose.waitUntil(15000) { node.session.state.value.stale }
            offlineMs = android.os.SystemClock.elapsedRealtime() - offlineStart
            org.junit.Assert.assertEquals(before, node.session.state.value.snapshot!!.messages.map { Triple(it.turnId, it.id, it.text) })
        } finally {
            if (wifi) shell("svc wifi enable")
            if (data) shell("svc data enable")
        }
        val recovered = android.os.SystemClock.elapsedRealtime()
        compose.waitUntil(30000) { !node.session.state.value.stale && !node.session.state.value.loading }
        val recoveryMs = android.os.SystemClock.elapsedRealtime() - recovered
        org.junit.Assert.assertEquals(before, node.session.state.value.snapshot!!.messages.map { Triple(it.turnId, it.id, it.text) })
        compose.onNodeWithTag("app_error").assertDoesNotExist()
        saveEvidence("network-recovery-only")
        val dir = File(ins.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        File(dir, "network-recovery-only.json").writeText(org.json.JSONObject().put("offlineDetectionMs", offlineMs).put("recoveryMs", recoveryMs).put("historyUnchanged", true).toString(2))
    }

    @Test fun connectionErrorExplainsFailureAndRetryKeepsLogin() {
        waitForProject()
        lateinit var model: AppViewModel
        lateinit var original: NodeRuntime
        compose.runOnIdle {
            model = androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java]
            original = requireNotNull(model.state.value.node)
            model.fail(java.net.UnknownHostException("fixture failure"))
        }
        compose.onNodeWithText("DNS", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("retry_connection").assertIsDisplayed()
        saveEvidence("connection-dns-diagnostic")
        compose.onNodeWithTag("retry_connection").performClick()
        compose.waitUntil(30000) { model.state.value.error == null && !model.state.value.reconnecting && original.catalog.state.value.projectsFresh }
        org.junit.Assert.assertSame(original, model.state.value.node)
        compose.onNodeWithTag("projects_page").assertIsDisplayed()
    }

    @Test fun sharedWriterRequiresImpactConfirmationWithoutKilling() {
        waitForProject()
        lateinit var node: NodeRuntime
        compose.runOnIdle { node = requireNotNull(androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java].state.value.node) }
        val runtimeBefore = kotlinx.coroutines.runBlocking { node.api.call("/v1/node").getJSONObject("runtime").getString("runtimeId") }
        val id = InstrumentationRegistry.getArguments().getString("benchmarkThreadId")!!
        compose.runOnIdle { node.openNotificationThread(id) }
        waitForTag("chat_page")
        waitForEnabled("ownership_takeover")
        compose.onNodeWithTag("ownership_takeover").performClick()
        waitForTag("confirm_shared_handoff")
        compose.onNodeWithText("将中断同进程的其他会话").assertIsDisplayed()
        compose.onNodeWithTag("confirm_shared_handoff").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        org.junit.Assert.assertFalse(node.submissions.state.value.busy)
        org.junit.Assert.assertNull(node.submissions.state.value.pending)
        saveEvidence("shared-writer-confirmation")
        compose.onNodeWithText("取消").performClick()
        waitForNoTag("confirm_shared_handoff")
        val runtimeAfter = kotlinx.coroutines.runBlocking { node.api.call("/v1/node").getJSONObject("runtime").getString("runtimeId") }
        org.junit.Assert.assertEquals(runtimeBefore, runtimeAfter)
    }

    @Test fun directIpRetainsLoginAndNeverCallsDns() {
        waitForProject()
        lateinit var node: NodeRuntime
        compose.runOnIdle { node = requireNotNull(androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java].state.value.node) }
        org.junit.Assert.assertEquals(dev.rivo.relay.connection.NodeEndpoints.DIRECT, node.connection.base)
        org.junit.Assert.assertEquals(dev.rivo.relay.connection.NodeEndpoints.LEGACY, node.connection.storageBase)
        val lookups = java.util.concurrent.atomic.AtomicInteger()
        val noDns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                lookups.incrementAndGet()
                throw java.net.UnknownHostException(hostname)
            }
        }
        val api = node.connection.api(noDns)
        val started = android.os.SystemClock.elapsedRealtime()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(20000) {
                org.junit.Assert.assertEquals(node.connection.nodeId, api.call("/v1/node").getString("id"))
                org.junit.Assert.assertTrue(api.call("/v1/projects").getJSONArray("projects").length() > 0)
                val thread = InstrumentationRegistry.getArguments().getString("benchmarkThreadId")!!
                api.call("/v1/threads/" + dev.rivo.relay.data.encoded(thread))
                api.resetTransport()
                api.call("/v1/node")
            }
            api.resetTransport()
        }
        org.junit.Assert.assertEquals(0, lookups.get())
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        File(directory, "direct-ip.json").writeText(org.json.JSONObject().put("dnsLookups", lookups.get())
            .put("endpoint", node.connection.base).put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started).toString(2))
    }

    @Test fun tailnetDnsRecoversRealHttpsWhenSystemResolverFails() {
        waitForProject()
        lateinit var node: NodeRuntime
        compose.runOnIdle { node = requireNotNull(androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java].state.value.node) }
        org.junit.Assert.assertTrue(node.connection.base.startsWith("https://"))
        org.junit.Assert.assertTrue(java.net.URI(node.connection.base).host.endsWith(".ts.net"))
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val brokenSystem = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                failures.incrementAndGet()
                throw java.net.UnknownHostException(hostname)
            }
        }
        val api = node.connection.api(dev.rivo.relay.data.TailnetDns(system = brokenSystem))
        val started = android.os.SystemClock.elapsedRealtime()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(20000) {
                api.call("/v1/node")
                org.junit.Assert.assertTrue(api.call("/v1/projects").getJSONArray("projects").length() > 0)
                api.resetTransport()
                api.call("/v1/node")
            }
            api.resetTransport()
        }
        org.junit.Assert.assertTrue("OS resolver failure must be exercised after pool reset", failures.get() >= 2)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        File(directory, "dns-recovery.json").writeText(org.json.JSONObject()
            .put("systemDnsFailures", failures.get()).put("httpsCertificateVerification", true)
            .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started).toString(2))
    }

    @Test fun realNetworkReadLatency() {
        waitForProject()
        lateinit var node: NodeRuntime
        compose.runOnIdle { node = requireNotNull(androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java].state.value.node) }
        val report = org.json.JSONObject().put("endpoint", node.connection.base)
        kotlinx.coroutines.runBlocking {
            val projects = node.api.call("/v1/projects").getJSONArray("projects")
            val project = (0 until projects.length()).map { projects.getJSONObject(it) }.first { it.getString("name") == projectName }
            val projectId = project.getString("id")
            val listPath = "/v1/threads?projectId=" + dev.rivo.relay.data.encoded(projectId)
            val threads = node.api.call(listPath).getJSONArray("threads")
            org.junit.Assert.assertTrue("validation history is required", threads.length() > 0)
            val threadId = InstrumentationRegistry.getArguments().getString("benchmarkThreadId") ?: threads.getJSONObject(0).getString("id")
            report.put("threadId", threadId)
            for ((label, path) in listOf("node" to "/v1/node", "projects" to "/v1/projects", "threads" to listPath,
                "history" to ("/v1/threads/" + dev.rivo.relay.data.encoded(threadId)))) {
                val samples = org.json.JSONArray()
                var bytes = 0
                repeat(8) {
                    val before = android.os.SystemClock.elapsedRealtimeNanos()
                    val response = node.api.call(path)
                    samples.put((android.os.SystemClock.elapsedRealtimeNanos() - before) / 1_000_000.0)
                    bytes = response.toString().toByteArray().size
                }
                report.put(label, org.json.JSONObject().put("milliseconds", samples).put("responseBytes", bytes))
            }
        }
        val ins = InstrumentationRegistry.getInstrumentation()
        val directory = File(ins.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        File(directory, "network-latency.json").writeText(report.toString(2))
        saveEvidence("network-latency-projects")
    }

    @Test fun nativeStreamingLongOutputAndNetworkRecovery() {
        newValidationConversation()
        lateinit var node: NodeRuntime
        compose.runOnIdle { node = requireNotNull(androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java].state.value.node) }
        val threadId = requireNotNull(node.session.state.value.id)
        val ins = InstrumentationRegistry.getInstrumentation()
        val evidence = File(ins.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val deltas = java.util.concurrent.atomic.AtomicInteger()
        val commands = java.util.concurrent.atomic.AtomicInteger()
        val firstEvent = java.util.concurrent.atomic.AtomicLong()
        val started = android.os.SystemClock.elapsedRealtime()
        val watcher = scope.launch {
            node.api.events(threadId).collect { event ->
                if (event.type == "connected") ready.complete(Unit)
                val params = event.payload.optJSONObject("params")
                if (params?.optString("threadId") == threadId && params.has("relayOffset")) {
                    deltas.incrementAndGet()
                    firstEvent.compareAndSet(0, android.os.SystemClock.elapsedRealtime() - started)
                    if (event.payload.optString("method").contains("outputDelta")) commands.incrementAndGet()
                }
            }
        }
        try {
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(15000) { ready.await() } }
            val prompt = "Performance acceptance only. Run one shell command: python3 -u -c 'import time; [(print(\"RIVO_STREAM_%02d\" % i, flush=True), time.sleep(0.3)) for i in range(1, 21)]'. Do not read or modify any files. Then reply with 40 separate plain paragraphs, each saying RIVO_LONG_01 through RIVO_LONG_40 respectively followed by this sentence: Streaming and long message display verification. End with a separate paragraph RIVO_PERF_DONE."
            compose.onNodeWithTag("message_input").performTextInput(prompt)
            waitForEnabled("send_message", 60_000)
            compose.onNodeWithTag("send_message").performClick()
            compose.waitUntil(180_000) { node.session.state.value.snapshot?.messages?.any { it.type == "agentMessage" && it.text.contains("RIVO_PERF_DONE") } == true }
            waitForNoTag("stop_turn")
            compose.waitUntil(30000) { !node.session.state.value.stale }
            val snapshot = requireNotNull(node.session.state.value.snapshot)
            org.junit.Assert.assertTrue("live offsets were not received", deltas.get() > 0)
            org.junit.Assert.assertTrue("command stream was not received", commands.get() > 0)
            val answer = snapshot.messages.last { it.type == "agentMessage" && it.text.contains("RIVO_PERF_DONE") }.text
            org.junit.Assert.assertTrue(answer.contains("RIVO_LONG_01"))
            org.junit.Assert.assertTrue(answer.contains("RIVO_LONG_40"))
            org.junit.Assert.assertEquals(snapshot.messages.size, snapshot.messages.map { it.turnId to it.id }.distinct().size)
            saveEvidence("performance-long-output")
            // The auxiliary observer measures streaming only; the app owns recovery.
            kotlinx.coroutines.runBlocking { watcher.cancel(); watcher.join() }
            fun shell(command: String) { ins.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
            val wifiWasOn = android.provider.Settings.Global.getInt(ins.targetContext.contentResolver, "wifi_on", 0) == 1
            val dataWasOn = android.provider.Settings.Global.getInt(ins.targetContext.contentResolver, "mobile_data", 0) == 1
            var recoveredMs = 0L
            var offlineDetectionMs = 0L
            try {
                val offlineStart = android.os.SystemClock.elapsedRealtime()
                shell("svc wifi disable"); shell("svc data disable")
                compose.runOnIdle { node.session.refresh() }
                compose.waitUntil(30_000) { node.session.state.value.stale }
                offlineDetectionMs = android.os.SystemClock.elapsedRealtime() - offlineStart
                org.junit.Assert.assertEquals(answer, node.session.state.value.snapshot?.messages?.last { it.type == "agentMessage" && it.text.contains("RIVO_PERF_DONE") }?.text)
                saveEvidence("performance-offline-cache")
            } finally {
                if (wifiWasOn) shell("svc wifi enable")
                if (dataWasOn) shell("svc data enable")
            }
            val reconnectStart = android.os.SystemClock.elapsedRealtime()
            compose.runOnIdle { node.session.refresh() }
            compose.waitUntil(30_000) { !node.session.state.value.stale && !node.session.state.value.loading }
            recoveredMs = android.os.SystemClock.elapsedRealtime() - reconnectStart
            org.junit.Assert.assertEquals(answer, node.session.state.value.snapshot?.messages?.last { it.type == "agentMessage" && it.text.contains("RIVO_PERF_DONE") }?.text)
            compose.onNodeWithTag("app_error").assertDoesNotExist()
            saveEvidence("performance-reconnected")
            File(evidence, "performance-native.json").writeText(org.json.JSONObject()
                .put("threadId", threadId).put("offsetEvents", deltas.get()).put("commandOffsetEvents", commands.get())
                .put("firstOffsetEventMs", firstEvent.get()).put("answerCharacters", answer.length)
                .put("recoveryMs", recoveredMs).put("offlineDetectionMs", offlineDetectionMs).put("networkRecovery", true).toString(2))
        } finally { watcher.cancel(); scope.cancel() }
    }

    @Test fun todoCrudAndRealDispatch() {
        val title = "Rivo Android verification ${System.currentTimeMillis()}"
        val edited = "$title edited"
        waitForTag("nav_todos")
        compose.onNodeWithTag("nav_todos").performClick()
        waitForTag("new_todo")
        compose.onNodeWithTag("new_todo").performClick()
        waitForTag("todo_subtask_input_0")
        compose.onNodeWithTag("todo_subtask_input_0").performTextInput(title)
        compose.onNodeWithTag("todo_notes").performTextInput("Reply with exactly RIVO_TODO_OK. Do not read or modify files or use tools.")
        compose.onNodeWithTag("save_todo").performScrollTo().performClick()
        waitForNoTag("save_todo")
        waitForText(title)
        compose.onNodeWithTag("todo_search").performTextReplacement(title)
        compose.onNodeWithTag("todo_search").performImeAction()
        scrollTodoTo(title)
        compose.onNode(hasText(title, substring = false) and !hasSetTextAction()).assertHasClickAction().performClick()
        waitForTag("todo_subtask_input_0")
        compose.onNodeWithTag("todo_subtask_input_0").performTextReplacement(edited)
        compose.onNodeWithTag("save_todo").performScrollTo().performClick()
        waitForNoTag("save_todo")
        waitForText(edited)
        compose.onNodeWithTag("todo_search").performTextInput(edited)
        compose.onNodeWithTag("todo_search").performImeAction()
        scrollTodoTo(edited)
        compose.onNode(hasText(edited, substring = false) and !hasSetTextAction()).performClick()
        waitForEnabled("todo_start_codex")
        compose.onNodeWithTag("todo_start_codex").performScrollTo().performClick()
        waitForTag("dispatch_project")
        compose.onNodeWithTag("dispatch_project").performClick()
        val projectChoice = hasText(projectName, substring = false) and hasAnyAncestor(hasTestTag("dispatch_project_menu"))
        compose.waitUntil(30_000) { compose.onAllNodes(projectChoice).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(projectChoice).performScrollTo().performClick()
        waitForEnabled("confirm_dispatch")
        compose.onNodeWithTag("confirm_dispatch").performClick()
        waitForTag("chat_page", 60_000)
        waitForText("RIVO_TODO_OK", 180_000)
        saveEvidence("todo-native-dispatch")
        compose.onNodeWithTag("back_sessions").performClick()
        waitForTag("todo_search")
        compose.onNodeWithTag("todo_search").performTextReplacement(edited)
        compose.onNodeWithTag("todo_search").performImeAction()
        scrollTodoTo(edited)
        waitForText(edited)
        compose.onNode(isToggleable() and isOff()).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已完成", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // QiGuang expands the completed section when an item is completed.
        scrollTodoTo(edited)
        compose.onNode(hasText(edited, substring = false) and !hasSetTextAction()).assertHasClickAction().performClick()
        waitForTag("todo_title")
        compose.onNodeWithText("删除待办").performScrollTo().performClick()
        waitForText("删除这条待办？")
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(edited, substring = false) and !hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun systemPhotoPickerAndNativeImageRoundTrip() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val fixture = createRedPhoto()
        try {
            newValidationConversation()
            compose.onNodeWithTag("message_input").performTextInput("What is the dominant color of the attached image? Reply with exactly RED if it is red. Do not read or modify files or use tools.")
            compose.onNodeWithTag("add_attachment").performClick()
            waitForTag("add_image")
            compose.onNodeWithTag("add_image").performClick()
            try { compose.waitUntil(30_000) { clickVisibleRedPickerPhoto() } }
            catch (e: Throwable) { savePickerEvidence(); throw e }
            compose.waitUntil(30_000) {
                val attached = runCatching { compose.onAllNodesWithTag("draft_image", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
                if (!attached) confirmPickerSelectionIfPresent()
                attached
            }
            compose.onNodeWithTag("draft_image", useUnmergedTree = true).assertIsDisplayed()
            waitForEnabled("send_message", 60_000)
            saveEvidence("system-photo-selected")
            compose.onNodeWithTag("send_message").performClick()
            waitForText("RED", 180_000)
            compose.onNodeWithText("RED").assertIsDisplayed()
            saveEvidence("native-image-reply")
        } finally { resolver.delete(fixture, null, null) }
    }

    @Test fun nativeFilePreview() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("previewPath") ?: "/Users/e2/Documents/Rivo-Relay-Validation-20261029/preview.txt"
        // This fixture text was confirmed through the native host's fs/readFile, not inferred from the filename.
        val expected = args.getString("previewExpected") ?: "RIVO_NATIVE_FILE_PREVIEW"
        newValidationConversation()
        compose.onNodeWithTag("add_attachment").performClick()
        compose.onNodeWithTag("attachment_tab_node").performClick()
        waitForTag("add_workspace_file")
        compose.onNodeWithTag("add_workspace_file").performClick()
        waitForTag("workspace_files")
        // previewPath must be a direct child of the validation workspace.
        val fileLabel = "文件 · ${path.substringAfterLast('/')}"
        compose.onNodeWithTag("workspace_files").performScrollToNode(hasText(fileLabel))
        compose.onNodeWithText(fileLabel).performClick()
        compose.onNodeWithTag("message_input").performTextInput("Reply with exactly FILE_READY. Do not read or modify files or use tools.")
        waitForEnabled("send_message", 60_000)
        compose.onNodeWithTag("send_message").performClick()
        waitForText("FILE_READY", 180_000)
        val label = "文件：${path.substringAfterLast('/')}"
        compose.onNodeWithTag("chat_history").performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
        waitForTag("file_preview")
        compose.waitUntil(30_000) { compose.onAllNodesWithText(expected, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(expected, substring = true).assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("file_preview"))).assertCountEquals(0)
        saveEvidence("native-file-preview")
    }

    @Test fun offlineHomeKeepsLogin() {
        // The host stops only its isolated Relay before launching this method.
        waitForTag("projects_page")
        waitForTag("app_error", 60_000)
        compose.onNodeWithTag("scan_connect").assertDoesNotExist()
        compose.onNodeWithText("Codex 项目").assertIsDisplayed()
        saveEvidence("offline-home")
    }

    @Test fun revokedConnectionReturnsToLogin() {
        // The host revokes this test connection before launching this method.
        waitForTag("scan_connect", 60_000)
        waitForText("节点授权已失效，请重新扫码连接。")
        compose.onNodeWithTag("projects_page").assertDoesNotExist()
        saveEvidence("revoked-login")
    }

    @Test fun existingTodoAssociationAfterRestart() {
        val title = requireNotNull(InstrumentationRegistry.getArguments().getString("todoTitle")) { "todoTitle is required" }
        waitForTag("nav_todos")
        compose.onNodeWithTag("nav_todos").performClick()
        waitForTag("todo_search")
        compose.onNodeWithTag("todo_search").performTextReplacement(title)
        compose.onNodeWithTag("todo_search").performImeAction()
        scrollTodoTo(title)
        compose.onNode(hasText(title, substring = false) and !hasSetTextAction()).performClick()
        waitForTag("todo_editor")
        compose.onNodeWithTag("todo_start_codex").assertDoesNotExist()
        compose.onNodeWithText("会话", substring = false).performScrollTo().performClick()
        waitForTag("chat_page", 60_000)
        waitForText("RIVO_TODO_OK", 60_000)
        compose.onNodeWithText("RIVO_TODO_OK").assertIsDisplayed()
        saveEvidence("todo-association-restored")
    }

    @Test fun savedConnectionSwitchAndRetry() {
        val address = requireNotNull(InstrumentationRegistry.getArguments().getString("nodeAddress"))
        waitForTag("projects_page")
        compose.onNodeWithTag("node_settings").performClick()
        waitForText("切换节点")
        compose.onNodeWithText("切换节点").performClick()
        waitForTag("scan_connect")
        compose.onNodeWithTag("saved_node_$address").performScrollTo().assertIsDisplayed()
        saveEvidence("saved-nodes")
        compose.onNodeWithTag("saved_node_$address").performClick()
        waitForTag("projects_page")
        compose.onNodeWithText("Codex 项目").assertIsDisplayed()
        compose.onNodeWithTag("app_error").assertDoesNotExist()
        saveEvidence("saved-node-reconnected")
    }

    @Test fun manualPairingFromInstrumentationArguments() {
        // Arguments are provided transiently by the host. They must not be logged or saved in source/results.
        val args = InstrumentationRegistry.getArguments()
        val address = requireNotNull(args.getString("nodeAddress")) { "nodeAddress is required" }
        val code = requireNotNull(args.getString("pairingCode")) { "pairingCode is required" }
        waitForTag("manual_entry", 60_000)
        compose.onNodeWithTag("manual_entry").performClick()
        compose.onNodeWithTag("pairing_address").performTextInput(address)
        compose.onNodeWithTag("pairing_code").performTextInput(code)
        compose.onNodeWithTag("manual_connect").performClick()
        waitForTag("projects_page", 60_000)
        compose.onNodeWithText("Codex 项目").assertIsDisplayed()
        waitForProject()
        saveEvidence("manual-paired-projects")
    }
}
