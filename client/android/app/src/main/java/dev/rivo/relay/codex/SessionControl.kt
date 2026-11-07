package dev.rivo.relay.codex

import dev.rivo.relay.data.string

enum class ControlState { WRITABLE, EXTERNAL, RELEASED, WORKSPACE_UNAVAILABLE, UNKNOWN }
data class SessionControl(val state: ControlState, val action: String? = null, val reason: String = "") {
    val canOperate get() = state == ControlState.WRITABLE
}

/** One projection for both the status notice and native mutation controls. */
fun SessionState.control(): SessionControl {
    val snapshot = snapshot ?: return SessionControl(ControlState.UNKNOWN)
    if (stale || loading) return SessionControl(ControlState.UNKNOWN)
    val wire = snapshot.control
    val state = if (wire != null) when (wire.string("state")) {
        "writable" -> if (wire.optBoolean("canOperate") && !snapshot.readOnly) ControlState.WRITABLE else ControlState.UNKNOWN
        "external" -> ControlState.EXTERNAL
        "released" -> ControlState.RELEASED
        "workspace_unavailable" -> ControlState.WORKSPACE_UNAVAILABLE
        else -> ControlState.UNKNOWN
    } else when {
        snapshot.workspaceUnavailable -> ControlState.WORKSPACE_UNAVAILABLE
        snapshot.readOnly && snapshot.writerOwner == "none" -> ControlState.RELEASED
        snapshot.readOnly -> ControlState.EXTERNAL
        else -> ControlState.WRITABLE
    }
    val action = if (wire != null) wire.string("action").takeIf { it in setOf("resume", "release", "takeover") }
        else when (state) {
            ControlState.WRITABLE -> "release"
            ControlState.EXTERNAL, ControlState.WORKSPACE_UNAVAILABLE -> "takeover".takeIf { snapshot.nativeRuntime?.optBoolean("handoffAvailable", true) != false }
            else -> null // Older nodes do not implement ordinary resume.
        }
    return SessionControl(state, action.takeUnless { state == ControlState.UNKNOWN }, wire?.string("unavailableReason").orEmpty())
}
