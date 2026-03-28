package agent.memory

import agent.core.AgentTokenStats
import agent.memory.model.ConversationSummary
import agent.memory.model.MemoryMetadata
import agent.memory.model.MemoryState
import agent.memory.summarizer.ConversationSummarizer
import agent.memory.summarizer.SimpleConversationSummarizer
import agent.storage.JsonConversationStore
import agent.storage.mapper.ChatMessageConversationMapper
import agent.storage.model.ConversationMemoryState
import agent.storage.model.StoredMemoryMetadata
import agent.storage.model.StoredSummary
import java.nio.file.Path
import llm.core.LanguageModel
import llm.core.model.ChatMessage
import llm.core.model.ChatRole

class DefaultMemoryManager(
    private val languageModel: LanguageModel,
    private val systemPrompt: String,
    private val conversationStore: JsonConversationStore = JsonConversationStore.forLanguageModel(languageModel),
    private val memoryStrategy: MemoryStrategy = NoCompressionMemoryStrategy(),
    private val summarizer: ConversationSummarizer = SimpleConversationSummarizer()
) : MemoryManager {
    private val conversationMapper = ChatMessageConversationMapper()
    private var memoryState = loadMemoryState()

    override fun currentConversation(): List<ChatMessage> = memoryState.messages.toList()

    override fun previewTokenStats(userPrompt: String): AgentTokenStats {
        val effectiveConversation = effectiveConversation()
        val historyTokens = languageModel.tokenCounter?.countMessages(effectiveConversation)
        val userPromptTokens = languageModel.tokenCounter?.countText(userPrompt)
        val promptTokensLocal = languageModel.tokenCounter?.countMessages(
            effectiveConversationWithUserPrompt(userPrompt)
        )

        return AgentTokenStats(
            historyTokens = historyTokens,
            promptTokensLocal = promptTokensLocal,
            userPromptTokens = userPromptTokens
        )
    }

    override fun appendUserMessage(userPrompt: String): List<ChatMessage> {
        memoryState = memoryStrategy.refreshState(
            memoryState.copy(
                messages = memoryState.messages + ChatMessage(role = ChatRole.USER, content = userPrompt)
            ),
            summarizer
        )
        saveState()
        return effectiveConversation()
    }

    override fun appendAssistantMessage(content: String) {
        memoryState = memoryStrategy.refreshState(
            memoryState.copy(
                messages = memoryState.messages + ChatMessage(role = ChatRole.ASSISTANT, content = content)
            ),
            summarizer
        )
        saveState()
    }

    override fun clear() {
        memoryState = MemoryState(
            messages = listOf(createSystemMessage()),
            metadata = MemoryMetadata(strategyId = memoryStrategy.id)
        )
        saveState()
    }

    override fun replaceContextFromFile(sourcePath: Path) {
        val importedState = JsonConversationStore(sourcePath).loadState().toMemoryState()
        require(importedState.messages.isNotEmpty()) {
            "Файл истории $sourcePath пустой или не содержит сообщений."
        }

        memoryState = memoryStrategy.refreshState(
            importedState.copy(
                metadata = importedState.metadata.copy(strategyId = memoryStrategy.id)
            ),
            summarizer
        )
        saveState()
    }

    private fun loadMemoryState(): MemoryState {
        val savedState = conversationStore.loadState().toMemoryState()
        if (savedState.messages.isNotEmpty()) {
            return memoryStrategy.refreshState(
                savedState.copy(
                    metadata = savedState.metadata.copy(strategyId = memoryStrategy.id)
                ),
                summarizer
            )
        }

        val initialState = MemoryState(
            messages = listOf(createSystemMessage()),
            metadata = MemoryMetadata(strategyId = memoryStrategy.id)
        )
        saveState(initialState)
        return initialState
    }

    private fun saveState() {
        saveState(memoryState)
    }

    private fun saveState(state: MemoryState) {
        memoryState = state.copy(
            metadata = state.metadata.copy(strategyId = memoryStrategy.id)
        )
        conversationStore.saveState(memoryState.toStoredState())
    }

    private fun createSystemMessage(): ChatMessage =
        ChatMessage(
            role = ChatRole.SYSTEM,
            content = systemPrompt
        )

    private fun effectiveConversation(): List<ChatMessage> =
        memoryStrategy.effectiveContext(memoryState)

    private fun effectiveConversationWithUserPrompt(userPrompt: String): List<ChatMessage> =
        memoryStrategy.effectiveContext(
            memoryStrategy.refreshState(
                memoryState.copy(
                    messages = memoryState.messages + ChatMessage(role = ChatRole.USER, content = userPrompt)
                ),
                summarizer
            )
        )

    private fun ConversationMemoryState.toMemoryState(): MemoryState =
        MemoryState(
            messages = messages.map(conversationMapper::fromStoredMessage),
            summaries = summaries.map { summary ->
                ConversationSummary(
                    content = summary.content,
                    coveredMessagesCount = summary.coveredMessagesCount
                )
            },
            metadata = MemoryMetadata(
                strategyId = metadata.strategyId,
                compressedMessagesCount = metadata.compressedMessagesCount
            )
        )

    private fun MemoryState.toStoredState(): ConversationMemoryState =
        ConversationMemoryState(
            messages = messages.map(conversationMapper::toStoredMessage),
            summaries = summaries.map { summary ->
                StoredSummary(
                    content = summary.content,
                    coveredMessagesCount = summary.coveredMessagesCount
                )
            },
            metadata = StoredMemoryMetadata(
                strategyId = metadata.strategyId,
                compressedMessagesCount = metadata.compressedMessagesCount
            )
        )
}
