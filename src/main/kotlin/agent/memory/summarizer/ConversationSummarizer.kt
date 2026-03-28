package agent.memory.summarizer

import llm.core.model.ChatMessage

interface ConversationSummarizer {
    /**
     * Создаёт краткое резюме для набора сообщений диалога.
     *
     * @param messages сообщения, которые нужно сжать в summary
     * @return текст summary, пригодный для последующей подстановки в контекст
     */
    fun summarize(messages: List<ChatMessage>): String
}
