package agent.memory

import agent.memory.model.ConversationSummary
import agent.memory.model.MemoryMetadata
import agent.memory.model.MemoryState
import agent.memory.summarizer.ConversationSummarizer
import kotlin.test.Test
import kotlin.test.assertEquals
import llm.core.model.ChatMessage
import llm.core.model.ChatRole

class SummaryCompressionMemoryStrategyTest {
    @Test
    fun `returns original messages when summaries are absent`() {
        val strategy = SummaryCompressionMemoryStrategy(
            recentMessagesCount = 2,
            summaryBatchSize = 2
        )
        val messages = listOf(
            ChatMessage(role = ChatRole.SYSTEM, content = "system"),
            ChatMessage(role = ChatRole.USER, content = "u1"),
            ChatMessage(role = ChatRole.ASSISTANT, content = "a1")
        )

        assertEquals(
            messages,
            strategy.effectiveContext(MemoryState(messages = messages))
        )
    }

    @Test
    fun `builds system summary and remaining dialog context`() {
        val strategy = SummaryCompressionMemoryStrategy(
            recentMessagesCount = 2,
            summaryBatchSize = 2
        )
        val state = MemoryState(
            messages = listOf(
                ChatMessage(role = ChatRole.SYSTEM, content = "system"),
                ChatMessage(role = ChatRole.USER, content = "u2"),
                ChatMessage(role = ChatRole.ASSISTANT, content = "a2"),
                ChatMessage(role = ChatRole.USER, content = "u3")
            ),
            summaries = listOf(
                ConversationSummary(
                    content = "Пользователь уже рассказал о прошлой задаче.",
                    coveredMessagesCount = 2
                )
            ),
            metadata = MemoryMetadata(compressedMessagesCount = 2)
        )

        assertEquals(
            listOf(
                ChatMessage(role = ChatRole.SYSTEM, content = "system"),
                ChatMessage(
                    role = ChatRole.SYSTEM,
                    content = "Краткое резюме предыдущего диалога:\nПользователь уже рассказал о прошлой задаче."
                ),
                ChatMessage(role = ChatRole.USER, content = "u2"),
                ChatMessage(role = ChatRole.ASSISTANT, content = "a2"),
                ChatMessage(role = ChatRole.USER, content = "u3")
            ),
            strategy.effectiveContext(state)
        )
    }

    @Test
    fun `refreshState compresses eligible batch removes source messages and tracks compressed count`() {
        val strategy = SummaryCompressionMemoryStrategy(
            recentMessagesCount = 2,
            summaryBatchSize = 2
        )
        val messages = listOf(
            ChatMessage(role = ChatRole.SYSTEM, content = "system"),
            ChatMessage(role = ChatRole.USER, content = "u1"),
            ChatMessage(role = ChatRole.ASSISTANT, content = "a1"),
            ChatMessage(role = ChatRole.USER, content = "u2"),
            ChatMessage(role = ChatRole.ASSISTANT, content = "a2"),
            ChatMessage(role = ChatRole.USER, content = "u3")
        )

        val refreshedState = strategy.refreshState(
            state = MemoryState(messages = messages),
            summarizer = RecordingConversationSummarizer()
        )

        assertEquals(1, refreshedState.summaries.size)
        assertEquals("Пользователь: u1\nАссистент: a1", refreshedState.summaries.single().content)
        assertEquals(2, refreshedState.metadata.compressedMessagesCount)
        assertEquals(
            listOf(
                ChatMessage(role = ChatRole.SYSTEM, content = "system"),
                ChatMessage(role = ChatRole.USER, content = "u2"),
                ChatMessage(role = ChatRole.ASSISTANT, content = "a2"),
                ChatMessage(role = ChatRole.USER, content = "u3")
            ),
            refreshedState.messages
        )
    }

    @Test
    fun `refreshState does not compress when there are not enough old messages outside recent tail`() {
        val strategy = SummaryCompressionMemoryStrategy(
            recentMessagesCount = 2,
            summaryBatchSize = 2
        )
        val state = MemoryState(
            messages = listOf(
                ChatMessage(role = ChatRole.SYSTEM, content = "system"),
                ChatMessage(role = ChatRole.USER, content = "u2"),
                ChatMessage(role = ChatRole.ASSISTANT, content = "a2"),
                ChatMessage(role = ChatRole.USER, content = "u3")
            ),
            summaries = listOf(
                ConversationSummary(
                    content = "Пользователь: u1\nАссистент: a1",
                    coveredMessagesCount = 2
                )
            ),
            metadata = MemoryMetadata(compressedMessagesCount = 2)
        )

        val refreshedState = strategy.refreshState(
            state = state,
            summarizer = FixedSummaryConversationSummarizer("Не должен использоваться")
        )

        assertEquals(state, refreshedState)
    }
}

private class RecordingConversationSummarizer : ConversationSummarizer {
    override fun summarize(messages: List<ChatMessage>): String =
        messages.joinToString(separator = "\n") { message ->
            val role = when (message.role) {
                ChatRole.SYSTEM -> "Система"
                ChatRole.USER -> "Пользователь"
                ChatRole.ASSISTANT -> "Ассистент"
            }
            "$role: ${message.content}"
        }
}

private class FixedSummaryConversationSummarizer(
    private val summary: String
) : ConversationSummarizer {
    override fun summarize(messages: List<ChatMessage>): String = summary
}
