package dev.rivo.relay.data

internal const val SPEECH_CONTEXT_LIMIT = 400
internal const val SPEECH_VOCABULARY_LIMIT = 395 // Reserve the 固定词表： label.
internal const val DEFAULT_SPEECH_VOCABULARY = "Merge、MergeDemo、棋子、生成器、棋子链、物品链、活动、笑脸订单、订单、等级、GH、Gossip Harbor、Tasty Travels。"

/** The vocabulary stays intact; remaining space retains the end of the draft. */
internal fun speechContext(vocabulary: String, draft: String): String {
    val words = vocabulary.lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString("、")
    require(words.length <= SPEECH_VOCABULARY_LIMIT)
    if (words.isEmpty()) return draft.takeLast(SPEECH_CONTEXT_LIMIT)
    val hint = "固定词表：$words"
    val remaining = SPEECH_CONTEXT_LIMIT - hint.length - 1
    return if (draft.isBlank() || remaining <= 0) hint else "$hint\n${draft.takeLast(remaining)}"
}
