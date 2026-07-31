/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveToolAuthorizationTest {

    @Test
    fun nonSensitiveToolAlwaysExecutes() {
        assertTrue(
            canExecuteProactiveTool(
                needsApproval = false,
                allowAllToolsAndPlugins = false,
                globallyAutoApproved = false,
            )
        )
    }

    @Test
    fun sensitiveToolRequiresScopedOrGlobalAuthorization() {
        assertFalse(
            canExecuteProactiveTool(
                needsApproval = true,
                allowAllToolsAndPlugins = false,
                globallyAutoApproved = false,
            )
        )
        assertTrue(
            canExecuteProactiveTool(
                needsApproval = true,
                allowAllToolsAndPlugins = true,
                globallyAutoApproved = false,
            )
        )
        assertTrue(
            canExecuteProactiveTool(
                needsApproval = true,
                allowAllToolsAndPlugins = false,
                globallyAutoApproved = true,
            )
        )
    }

    @Test
    fun workflowWakeInstructionRequiresCustomPromptExecution() {
        val instruction = buildWorkflowWakeUserInstruction(
            "自定义处理要求：调用通知工具提醒用户",
        )

        assertTrue(instruction.contains("立即执行"))
        assertTrue(instruction.contains("调用通知工具提醒用户"))
        assertTrue(instruction.contains("不要自行改为 SKIP 或 PASS"))
    }

    @Test
    fun silentReplyRecognizesPassAndSkipWithoutHidingNormalText() {
        assertTrue(isSilentProactiveReply("[PASS]"))
        assertTrue(isSilentProactiveReply("skip"))
        assertTrue(isSilentProactiveReply("[SKIP]。"))
        assertFalse(isSilentProactiveReply("Do not skip this workflow task"))
    }

    @Test
    fun timeReminderDoesNotReplaceWorkflowWakeInstruction() {
        val reminder = UIMessage.user("<time_reminder>Current time</time_reminder>")
        val workflowTask = UIMessage.user("自定义处理要求：执行工作流任务")

        val messages = buildInitialProactiveMessages(
            systemPrompt = "system",
            historyMessages = emptyList(),
            processedUserMessages = listOf(reminder, workflowTask),
        )

        assertEquals(3, messages.size)
        val finalText = messages.last().parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
        assertTrue(finalText.contains("执行工作流任务"))
    }

    @Test
    fun dynamicContextPrecedesCurrentProactiveRequest() {
        val messages = buildInitialProactiveMessages(
            systemPrompt = "system",
            historyMessages = listOf(UIMessage.assistant("history")),
            dynamicContext = "<dynamic_context>snapshot</dynamic_context>",
            processedUserMessages = listOf(UIMessage.user("current request")),
        )

        assertEquals(
            listOf("system", "history", "<dynamic_context>snapshot</dynamic_context>", "current request"),
            messages.map { message ->
                message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
            },
        )
    }
}
