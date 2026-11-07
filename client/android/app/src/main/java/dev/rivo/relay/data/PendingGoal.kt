package dev.rivo.relay.data

import org.json.JSONObject

/** Delivery checkpoint only; this never substitutes for native goal state. */
internal fun pendingGoalAction(desired: String?, applied: String?): JSONObject? {
    if (desired == applied) return null
    if (desired != null) return JSONObject(desired)
    if (applied != null && JSONObject(applied).string("operation") != "clear")
        return JSONObject().put("operation", "clear")
    return null
}
