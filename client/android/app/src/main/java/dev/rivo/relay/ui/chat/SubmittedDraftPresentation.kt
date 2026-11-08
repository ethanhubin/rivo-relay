package dev.rivo.relay.ui.chat

import dev.rivo.relay.data.PendingNativeMessage
import org.json.JSONArray
import org.json.JSONObject

/** A presentation-only mask: the durable draft remains available on definitive failure. */
internal fun hideSubmittedDraft(
    locked: Boolean,
    unchangedSubmission: Boolean,
    input: JSONArray,
    pending: List<PendingNativeMessage>,
): Boolean = locked && (unchangedSubmission || pending.any {
    it.delivery in setOf("submitting", "unknown", "accepted") && sameInput(input, JSONArray(it.input))
})

private fun sameInput(left: Any?, right: Any?): Boolean = when {
    left is JSONObject && right is JSONObject -> {
        val keys = left.keys().asSequence().toSet()
        keys == right.keys().asSequence().toSet() && keys.all { sameInput(left.opt(it), right.opt(it)) }
    }
    left is JSONArray && right is JSONArray -> left.length() == right.length() &&
        (0 until left.length()).all { sameInput(left.opt(it), right.opt(it)) }
    else -> left == right
}
