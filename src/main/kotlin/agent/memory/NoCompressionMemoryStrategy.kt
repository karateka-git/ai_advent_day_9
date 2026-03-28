package agent.memory

import agent.memory.model.MemoryState
import llm.core.model.ChatMessage

class NoCompressionMemoryStrategy : MemoryStrategy {
    override val id: String = "no_compression"

    override fun effectiveContext(state: MemoryState): List<ChatMessage> =
        state.messages.toList()
}
