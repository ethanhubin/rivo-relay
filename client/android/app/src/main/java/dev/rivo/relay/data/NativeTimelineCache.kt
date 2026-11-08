package dev.rivo.relay.data

/** Session-owned cache. Only plain final-row replacement bypasses structural projection. */
class NativeTimelineCache {
    private var previous = emptyList<NativeMessage>()
    private var settings = emptyList<NativeTurnSettings>()
    private var active: String? = null
    private var rows = emptyList<NativeTimelineRow>()

    fun project(messages: List<NativeMessage>, activeTurnId: String?, turnSettings: List<NativeTurnSettings>): List<NativeTimelineRow> {
        val old = previous.lastOrNull()
        val next = messages.lastOrNull()
        val last = rows.lastOrNull() as? NativeTimelineRow.Message
        val tailOnly = old != null && next != null && last?.message === old &&
            messages.size == previous.size && active == activeTurnId && settings == turnSettings &&
            old.id == next.id && old.turnId == next.turnId && old.type == next.type &&
            old.activity == null && next.activity == null && next.type != "reasoning" &&
            (0 until messages.lastIndex).all { messages[it] === previous[it] }
        rows = if (tailOnly) rows.dropLast(1) + NativeTimelineRow.Message(next!!)
            else nativeTimelineRows(messages, activeTurnId, turnSettings)
        previous = messages.toList()
        settings = turnSettings.toList()
        active = activeTurnId
        return rows
    }
}
