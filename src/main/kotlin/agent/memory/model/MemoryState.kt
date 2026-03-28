package agent.memory.model

import llm.core.model.ChatMessage

data class MemoryState(
    val messages: List<ChatMessage> = emptyList(),
    val summaries: List<ConversationSummary> = emptyList(),
    val metadata: MemoryMetadata = MemoryMetadata()
)
