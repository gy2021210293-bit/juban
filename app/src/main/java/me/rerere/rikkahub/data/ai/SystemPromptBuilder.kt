package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository

/**
 * Builds the stable system prompt shared by normal conversations and triggered tasks.
 * Dynamic runtime context should not be placed here because it invalidates upstream
 * prompt-prefix caching.
 */
object SystemPromptBuilder {
    fun build(
        assistant: Assistant,
        conversationSystemPrompt: String?,
        memories: List<AssistantMemory>,
        tools: List<Tool>,
        model: Model,
        settings: Settings,
        conversationRepository: ConversationRepository,
        staticPluginPromptInjections: List<String> = emptyList(),
        dynamicPluginEnabled: Boolean = false,
        allowSkipReply: Boolean = assistant.allowSkipReply,
        splitBubbleByLine: Boolean = assistant.splitBubbleByLine,
    ): String = buildString {
        val effectiveSystemPrompt =
            if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                conversationSystemPrompt
            } else {
                assistant.systemPrompt
            }

        if (!effectiveSystemPrompt.isNullOrBlank()) {
            append(effectiveSystemPrompt)
        }

        if (assistant.enableMemory) {
            appendLine()
            append(buildMemoryPrompt(memories))
        }

        appendLine()
        append(buildCodeBlockPrompt())

        tools.forEach { tool ->
            appendLine()
            append(tool.systemPrompt(model, emptyList()))
        }

        staticPluginPromptInjections.forEach { injection ->
            appendLine()
            appendLine()
            append(injection)
        }

        if (settings.systemToolsSetting.dynamicContextEnabled) {
            appendLine()
            appendLine()
            append(DYNAMIC_CONTEXT_SYSTEM_POLICY)
        }

        if (dynamicPluginEnabled) {
            appendLine()
            appendLine()
            append(PLUGIN_CONTEXT_SYSTEM_POLICY)
        }

        if (allowSkipReply) {
            appendLine()
            appendLine("## Skip Reply")
        }

        if (splitBubbleByLine) {
            appendLine()
            appendLine("## Message Bubbles")
        }
    }
}
