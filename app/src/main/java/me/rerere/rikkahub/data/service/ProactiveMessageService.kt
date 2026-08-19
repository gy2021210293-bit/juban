/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.app.NotificationCompat
import android.os.Build
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.DYNAMIC_CONTEXT_SYSTEM_POLICY
import me.rerere.rikkahub.data.ai.buildCodeBlockPrompt
import me.rerere.rikkahub.data.ai.buildMemoryPrompt
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.SystemTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.data.ai.tools.buildWriteFilesTool
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.plugin.provider.PluginToolProvider
import me.rerere.rikkahub.data.repository.MemoryRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.sendNotification
import java.time.Instant
import kotlin.uuid.Uuid
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ProactiveMessageService : KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val dynamicContextProvider: DynamicContextProvider by inject()

    companion object {
        const val TAG = "ProactiveMessageService"
        const val ACTION_PROACTIVE_MESSAGE = "me.rerere.orangechat.PROACTIVE_MESSAGE"
        private const val REQUEST_CODE = 10001

        internal const val PREFS_NAME = "proactive_message_prefs"
        private const val KEY_NEXT_TRIGGER_TIME = "next_trigger_time"

        fun scheduleNext(context: Context, setting: ProactiveMessageSetting) {
            if (!setting.enabled) {
                cancel(context)
                return
            }

            val minMinutes = setting.minIntervalMinutes.coerceAtLeast(1)
            val maxMinutes = setting.maxIntervalMinutes.coerceAtLeast(minMinutes)
            val delayMinutes = Random.nextInt(minMinutes, maxMinutes + 1)
            val triggerTime = java.lang.System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes.toLong())

            // 保存下次触发时间到SharedPreferences
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_NEXT_TRIGGER_TIME, triggerTime)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ProactiveMessageReceiver::class.java).apply {
                action = ACTION_PROACTIVE_MESSAGE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                // Android 12+ needs canScheduleExactAlarms() check
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                    } else {
                        // Fallback: use inexact alarm if exact alarm permission not granted
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        Log.w(TAG, "Exact alarm permission not granted, using inexact alarm")
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }

            Log.d(TAG, "Scheduled proactive message in $delayMinutes minutes, trigger at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(triggerTime))}")

            // Also schedule via WorkManager as a more reliable fallback
            ProactiveMessageWorker.scheduleNext(context, setting)
        }

        fun getNextTriggerTime(context: Context): Long? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val triggerTime = prefs.getLong(KEY_NEXT_TRIGGER_TIME, 0L)
            return if (triggerTime > 0) triggerTime else null
        }

        fun cancel(context: Context) {
            // 清除保存的触发时间
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_NEXT_TRIGGER_TIME)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ProactiveMessageReceiver::class.java).apply {
                action = ACTION_PROACTIVE_MESSAGE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                alarmManager.cancel(it)
                Log.d(TAG, "Cancelled proactive message alarm")
            }

            // Also cancel WorkManager fallback
            ProactiveMessageWorker.cancel(context)
        }

        fun resetTimer(context: Context, setting: ProactiveMessageSetting) {
            scheduleNext(context, setting)
        }

        fun triggerNow(context: Context, setting: ProactiveMessageSetting) {
            // 先安排下一次（写入SP让UI立即显示），再立即触发
            scheduleNext(context, setting)
            // 立即触发：直接启动TriggerService
            val serviceIntent = Intent(context, ProactiveMessageTriggerService::class.java)
            context.startForegroundService(serviceIntent)
        }

        /**
         * Dispatch a one-off workflow result back into the assistant generation chain.
         * This path is independent of the recurring proactive-message timer.
         */
        fun triggerFromWorkflow(
            context: Context,
            assistantId: String,
            workflowName: String,
            prompt: String,
            actionOutput: String?,
            allowAllToolsAndPlugins: Boolean,
        ): Result<Unit> = runCatching {
            val wakeContext = buildString {
                appendLine("## 工作流数据回流")
                appendLine("工作流：$workflowName")
                appendLine("自定义处理要求：$prompt")
                if (!actionOutput.isNullOrBlank()) {
                    appendLine()
                    appendLine("### 动作输出")
                    append(actionOutput)
                }
            }
            val serviceIntent = Intent(context, ProactiveMessageTriggerService::class.java).apply {
                putExtra(
                    ProactiveMessageTriggerService.EXTRA_WORKFLOW_WAKE_CONTEXT,
                    wakeContext,
                )
                putExtra(ProactiveMessageTriggerService.EXTRA_ASSISTANT_ID, assistantId)
                putExtra(
                    ProactiveMessageTriggerService.EXTRA_ALLOW_ALL_TOOLS_AND_PLUGINS,
                    allowAllToolsAndPlugins,
                )
                putExtra(ProactiveMessageTriggerService.EXTRA_FORCE_TRIGGER, true)
            }
            context.startForegroundService(serviceIntent)
        }
    }

    suspend fun buildProactiveContext(context: Context, settings: Settings): String {
        val sb = StringBuilder()
        sb.appendLine("[主动消息上下文]")

        // Time since last chat
        try {
            val lastMessageTime = getLastMessageTime()
            if (lastMessageTime != null) {
                val nowMs = java.lang.System.currentTimeMillis()
                val lastMs = lastMessageTime.toEpochMilliseconds()
                val diffMs = nowMs - lastMs
                val duration = diffMs.milliseconds
                val minutesAgo = duration.inWholeMinutes
                val hoursAgo = duration.inWholeHours
                when {
                    hoursAgo > 24 -> sb.appendLine("距离上次聊天: ${hoursAgo / 24}天${hoursAgo % 24}小时")
                    hoursAgo > 0 -> sb.appendLine("距离上次聊天: ${hoursAgo}小时${minutesAgo % 60}分钟")
                    else -> sb.appendLine("距离上次聊天: ${minutesAgo}分钟")
                }
            } else {
                sb.appendLine("距离上次聊天: 很久没有聊天了")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last message time", e)
        }

        // Current time
        val currentTime = java.lang.System.currentTimeMillis()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        sb.appendLine("当前时间: ${sdf.format(java.util.Date(currentTime))}")

        sb.appendLine()
        sb.appendLine("请根据以上上下文，以自然、关心、有趣的方式主动给用户发一条消息。")
        sb.appendLine()
        sb.appendLine("重要规则：")
        sb.appendLine("- 绝对不要复述上一轮的对话内容，要发新的话题或新的关心")
        sb.appendLine("- 如果上一轮已经说过类似的话，这次换一个完全不同的角度")
        sb.appendLine("- 不要提及你是在定时发消息，要像自然想起对方一样")
        sb.appendLine("- 绝对不要提及任何数据来源、工具使用、传感器数据、位置服务、应用使用统计等技术细节")
        sb.appendLine("- 不要说\"根据xxx\"、\"我注意到xxx数据\"之类暴露信息来源的话")
        sb.appendLine("- 直接以朋友聊天的语气开口，就像你突然想到了什么想跟对方说")
        sb.appendLine("- 不要使用任何XML标签、思考标记或特殊格式，只输出纯文本的消息内容")
        sb.appendLine("- 不要调用任何工具或函数，只输出纯文本回复")
        sb.appendLine("- 不要输出思考过程、推理过程或内部独白，只输出你想对用户说的话")
        return sb.toString()
    }


    suspend fun getLastMessageTimeMs(): Long {
        return try { getLastMessageTime()?.toEpochMilliseconds() ?: 0L } catch (e: Exception) { 0L }
    }
    private suspend fun getLastMessageTime(): kotlinx.datetime.Instant? {
        return try {
            val settings = settingsStore.settingsFlow.first()
            val assistantId = settings.assistantId
            val recentConversations = conversationRepository.getRecentConversations(assistantId, limit = 1)
            if (recentConversations.isNotEmpty()) {
                val conv = recentConversations.first()
                val fullConv = conversationRepository.getConversationById(conv.id)
                val localDateTime: LocalDateTime? = fullConv?.messageNodes?.lastOrNull()?.messages?.lastOrNull()?.createdAt
                localDateTime?.toInstant(TimeZone.currentSystemDefault())
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last message time", e)
            null
        }
    }
}

class ProactiveMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(ProactiveMessageService.TAG, "=== onReceive triggered at ${System.currentTimeMillis()}, action=${intent.action} ===")
        when (intent.action) {
            ProactiveMessageService.ACTION_PROACTIVE_MESSAGE -> {
                Log.d(ProactiveMessageService.TAG, "Starting ProactiveMessageTriggerService...")
                val serviceIntent = Intent(context, ProactiveMessageTriggerService::class.java)
                context.startForegroundService(serviceIntent)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(ProactiveMessageService.TAG, "Boot completed, rescheduling proactive message")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settingsStore = org.koin.core.context.GlobalContext.get().get<SettingsStore>()
                        val settings = settingsStore.settingsFlow.first()
                        val proactiveSetting = settings.proactiveMessageSetting
                        if (proactiveSetting.enabled) {
                            ProactiveMessageService.scheduleNext(context, proactiveSetting)
                        }
                    } catch (e: Exception) {
                        Log.e(ProactiveMessageService.TAG, "Failed to reschedule after boot", e)
                    }
                }
            }
        }
    }
}

class ProactiveMessageTriggerService : android.app.Service(), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val providerManager: ProviderManager by inject()
    private val templateTransformer: TemplateTransformer by inject()
    private val localTools: LocalTools by inject()
    private val skillManager: SkillManager by inject()
    private val mcpManager: McpManager by inject()
    private val pluginToolProvider: PluginToolProvider by inject()
    private val toolSurfaceBuilder: me.rerere.rikkahub.data.ai.tools.ToolSurfaceBuilder by inject()
    private val json: Json by inject()
    private val chatService: ChatService by inject()
    private val dynamicContextProvider: DynamicContextProvider by inject()
    private val proactiveMessageService = ProactiveMessageService()

    companion object {
        private const val TAG = "ProactiveMessageTrigger"
        private const val MAX_TOOL_STEPS = 5 // 主动消息最大工具调用步数
        // 外部触发（网关轮询）时跳过内部 minInterval 去重
        const val EXTRA_FORCE_TRIGGER = "force_trigger"
        // 激进模式设备事件上下文（由 DeviceEventAiTriggerService 传入）
        const val EXTRA_DEVICE_EVENT_CONTEXT = "device_event_context"
        // 工作流动作完成后回流给 AI 的上下文与授权。
        const val EXTRA_WORKFLOW_WAKE_CONTEXT = "workflow_wake_context"
        const val EXTRA_ASSISTANT_ID = "assistant_id"
        const val EXTRA_ALLOW_ALL_TOOLS_AND_PLUGINS = "allow_all_tools_and_plugins"

        // 保护 last_triggered_time 的 check-then-act 竞态（防止 AlarmManager 与 WorkManager
        // 前后脚触发导致"最小间隔"被砍半）。纯同步 SharedPreferences 读写，无挂起点，用对象锁即可。
        private val prefsLock = Any()
    }

    // 输入转换器（与 ChatService 保持一致）
    private val inputTransformers by lazy {
        listOf(
            TimeReminderTransformer,
            PromptInjectionTransformer,
            PlaceholderTransformer,
            DocumentAsPromptTransformer,
            OcrTransformer,
        )
    }

    // 输出转换器（与 ChatService 保持一致）
    private val outputTransformers by lazy {
        listOf(
            ThinkTagTransformer,
            Base64ImageToLocalFileTransformer,
            RegexOutputTransformer,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "=== TriggerService onStartCommand ===")
        val workflowWakeContext = intent?.getStringExtra(EXTRA_WORKFLOW_WAKE_CONTEXT)
        val isWorkflowWake = !workflowWakeContext.isNullOrBlank()
        // 外部触发（网关轮询/激进模式设备事件）时跳过内部 minInterval 去重
        val isForceTrigger = isWorkflowWake ||
            (intent?.getBooleanExtra(EXTRA_FORCE_TRIGGER, false) ?: false)
        // 激进模式设备事件上下文（由 DeviceEventAiTriggerService 传入）
        val deviceEventContext = intent?.getStringExtra(EXTRA_DEVICE_EVENT_CONTEXT)
        val isFromDeviceEvent = deviceEventContext != null
        if (isForceTrigger) {
            Log.d(TAG, "Force trigger${if (isFromDeviceEvent) " from device event" else " from gateway poll"}, will skip min interval check")
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("正在思考...")
            .setSmallIcon(me.rerere.rikkahub.R.drawable.small_icon)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(20001, notification)

        CoroutineScope(Dispatchers.IO).launch {
            var conversationId: kotlin.uuid.Uuid? = null
            try {
                val settings = settingsStore.settingsFlow.first()
                val proactiveSetting = settings.proactiveMessageSetting

                // 激进模式设备事件触发时，不检查主动消息开关（可独立工作）
                if (!proactiveSetting.enabled && !isFromDeviceEvent && !isWorkflowWake) {
                    stopSelf()
                    return@launch
                }

                val prefs = getSharedPreferences(ProactiveMessageService.PREFS_NAME, Context.MODE_PRIVATE)

                // 去重判断：防止 AlarmManager 和 WorkManager 在同一窗口内重复触发。
                // 外部触发（网关轮询/激进模式设备事件）跳过此检查，因为这是独立信号源，不受内部闹钟链约束。
                // 注意：isForceTrigger 跳过的是"时间间隔节流"（两回事），不跳过后面 tryClaimGeneration 的并发安全检查。
                // 把"读取 last_triggered_time -> 判断 -> 写入"整段放在同步块里，修复 check-then-act 竞态。
                if (isWorkflowWake) {
                    Log.d(TAG, "Workflow wake bypasses proactive timer deduplication")
                } else if (!isForceTrigger) {
                    val skipDueToInterval = synchronized(prefsLock) {
                        val lastTriggeredTime = prefs.getLong("last_triggered_time", 0L)
                        val minIntervalMs = proactiveSetting.minIntervalMinutes.coerceAtLeast(1) * 60 * 1000L
                        if (System.currentTimeMillis() - lastTriggeredTime < minIntervalMs) {
                            true
                        } else {
                            // 立即写入触发时间，防止并发重复
                            prefs.edit().putLong("last_triggered_time", System.currentTimeMillis()).apply()
                            false
                        }
                    }
                    if (skipDueToInterval) {
                        Log.d(TAG, "Duplicate trigger within min interval, skipping")
                        ProactiveMessageService.scheduleNext(this@ProactiveMessageTriggerService, proactiveSetting)
                        stopSelf()
                        return@launch
                    }
                } else {
                    // 强制触发也写入时间戳，保持与常规触发一致的状态记录
                    synchronized(prefsLock) {
                        prefs.edit().putLong("last_triggered_time", System.currentTimeMillis()).apply()
                    }
                }

                // 获取助手
                val requestedAssistantId = intent?.getStringExtra(EXTRA_ASSISTANT_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: proactiveSetting.assistantId
                val assistant = settings.assistants.find { it.id.toString() == requestedAssistantId }
                    ?: settings.getCurrentAssistant()
                val assistantUuid = assistant.id
                val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)

                if (model == null) {
                    Log.e(ProactiveMessageService.TAG, "No model found for proactive message")
                    if (!isWorkflowWake && !isFromDeviceEvent) {
                        ProactiveMessageService.scheduleNext(
                            this@ProactiveMessageTriggerService,
                            proactiveSetting,
                        )
                    }
                    stopSelf()
                    return@launch
                }

                // 找到最近的对话
                val recentConversations = conversationRepository.getRecentConversations(assistantUuid, limit = 1)
                val conversation = if (recentConversations.isNotEmpty()) {
                    conversationRepository.getConversationById(recentConversations.first().id)
                } else null

                val activeConversationId = conversation?.id ?: kotlin.uuid.Uuid.random()
                conversationId = activeConversationId
                val conversationId = activeConversationId

                // 持有会话引用，防止生成期间 session 被 idle 清除（finally 块会对应 release）。
                // 放在 claim 之前：即使 claim 失败提前返回，引用也能在 finally 里被正确释放，保持计数平衡。
                // 同时把数据库里的完整对话同步到 session，防止流式更新时 conv 是空状态导致覆盖历史。
                chatService.addConversationReference(conversationId)
                if (conversation != null) {
                    chatService.updateConversationState(conversationId) { _ -> conversation }
                }

                // 抢占生成权：尝试把当前协程的 Job 注册进 ConversationSession。
                // 这一步对所有触发源（含 isForceTrigger / 激进模式设备事件）一视同仁，是并发安全的核心。
                // 如果当前已有生成在跑（正常聊天或另一路主动消息），直接放弃本次触发，不排队等待、不重试。
                // 理由：等对方生成结束后，上下文（用户可能已在聊别的话题）大概率已过时，硬等没有意义。
                val myJob = coroutineContext[Job]
                if (myJob == null || !chatService.getOrCreateSession(conversationId).tryClaimGeneration(myJob)) {
                    Log.d(
                        TAG,
                        "Skip proactive trigger: session $conversationId already generating " +
                            "(normal chat or another proactive trigger in progress)"
                    )
                    // 必须走到 finally 块的"安排下一次触发"逻辑，不能绕过定时链收尾。
                    // 用 stopSelf + return@launch 退出主流程，finally 会正常执行（scheduleNext 已用 NonCancellable 保护）。
                    stopSelf()
                    return@launch
                }

                // 构建上下文
                val idleMinutes = runCatching { val last = proactiveMessageService.getLastMessageTimeMs(); if (last > 0) ((System.currentTimeMillis() - last) / 60000L).toInt() else Int.MAX_VALUE }.getOrDefault(Int.MAX_VALUE)

                // 如果有设备事件上下文（激进模式），使用它替代常规上下文；否则使用常规上下文
                val baseContext = if (isWorkflowWake) {
                    workflowWakeContext.orEmpty()
                } else if (deviceEventContext != null) {
                    deviceEventContext
                } else {
                    proactiveMessageService.buildProactiveContext(
                        this@ProactiveMessageTriggerService, settings
                    )
                }
                val dynamicContext = dynamicContextProvider.build(settings)
                val contextStr = listOf(
                    baseContext.takeUnless { isWorkflowWake }.orEmpty(),
                    dynamicContext,
                )
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")

                // 获取历史消息（先过滤掉悬空的工具调用消息，避免 tool_use 结构不完整触发 400）
                val historyMessages = filterInvalidToolMessages(
                    conversation?.currentMessages?.limitContext(assistant.contextMessageSize)
                        ?: emptyList()
                )

                // 构建工具列表（与 ChatService 保持一致）——需在系统提示词之前准备好，
                // 因为系统提示词里的工具说明要基于实际启用/传给模型的工具生成
                val allowAllToolsAndPlugins = if (isWorkflowWake) {
                    intent?.getBooleanExtra(EXTRA_ALLOW_ALL_TOOLS_AND_PLUGINS, false) ?: false
                } else {
                    proactiveSetting.allowAllToolsAndPlugins
                }
                val tools = buildTools(
                    settings = settings,
                    assistant = assistant,
                    model = model,
                    allowAllToolsAndPlugins = allowAllToolsAndPlugins,
                    recentMessages = historyMessages,
                    workspaceCwd = conversation?.workspaceCwd,
                    conversationId = conversationId,
                )

                // 构建系统提示词（固定骨架与普通消息对齐，触发指令放在最末尾，
                // 让普通消息↔主动/工作流/设备事件共享尽可能长的固定前缀，利于 prefix cache）
                val systemPrompt = buildSystemPrompt(
                    assistant = assistant,
                    settings = settings,
                    model = model,
                    tools = tools,
                    historyMessages = historyMessages,
                    jumpThreshold = proactiveSetting.jumpIdleThresholdMinutes,
                    isFromDeviceEvent = isFromDeviceEvent,
                    workflowWakeContext = workflowWakeContext,
                )

                // The actual request stays after the ephemeral context message.
                val userMessage = UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(
                        if (isWorkflowWake) {
                            buildWorkflowWakeUserInstruction(workflowWakeContext.orEmpty())
                        } else if (isFromDeviceEvent) {
                            "当前由用户手机动向触发，距离用户上次回复已过去 $idleMinutes 分钟。" +
                                "请根据以上用户动向决定是否发消息。没什么好说的就回复 [PASS]。"
                        } else {
                            "当前为定时主动消息，距离用户上次回复已过去 $idleMinutes 分钟。" +
                                "请根据以上上下文决定是否发消息。没什么好说的就回复 [PASS] 即可，不要强行找话题。"
                        }
                    ))
                )

                // 应用输入转换器
                val processedUserMessages = listOf(userMessage).transforms(
                    transformers = inputTransformers + templateTransformer,
                    context = this@ProactiveMessageTriggerService,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )

                // 组合完整消息列表：System + History + User Context
                // 合并相邻同角色消息（包括 history 末尾与合成 User 消息之间可能出现的 USER-USER 相邻），避免 400
                val messages = mergeAdjacentSameRoleMessages(
                    buildInitialProactiveMessages(
                        systemPrompt = systemPrompt,
                        historyMessages = historyMessages,
                        dynamicContext = contextStr,
                        processedUserMessages = processedUserMessages,
                    )
                )

                // 直接调用 AI API 生成消息
                val providerSetting = model.findProvider(settings.providers)
                if (providerSetting == null) {
                    Log.e(ProactiveMessageService.TAG, "No provider found for proactive message")
                    if (!isWorkflowWake && !isFromDeviceEvent) {
                        ProactiveMessageService.scheduleNext(
                            this@ProactiveMessageTriggerService,
                            proactiveSetting,
                        )
                    }
                    stopSelf()
                    return@launch
                }

                val providerImpl = providerManager.getProviderByType(providerSetting)

                // 主动消息场景：支持工具调用，但限制最大步数
                // temperature 不强制默认 0.8f，保持与 GenerationHandler 一致（assistant.temperature 为 null 时不传），
                // 否则对智谱 GLM 等 thinking 模型会同时下发 temperature + thinking，触发 "Invalid request body" 400。
                val params = TextGenerationParams(
                    model = model,
                    temperature = assistant.temperature,
                    topP = assistant.topP,
                    maxTokens = assistant.maxTokens,
                    tools = tools,
                    reasoningLevel = assistant.reasoningLevel,
                    customHeaders = buildList {
                        addAll(assistant.customHeaders)
                        addAll(model.customHeaders)
                    },
                    customBody = buildList {
                        addAll(assistant.customBodies)
                        addAll(model.customBodies)
                    }
                )

                Log.d(TAG, "Calling AI API for proactive message with ${historyMessages.size} history messages, ${tools.size} tools (reasoning=${assistant.reasoningLevel}, model=${model.modelId}, provider=${providerSetting::class.simpleName})...")
                // 诊断: 列出工具及其 parameters 是否为 null, 便于定位 "Invalid request body"
                tools.forEach { t ->
                    val hasSchema = t.parameters() != null
                    if (!hasSchema) Log.w(TAG, "Tool '${t.name}' has NULL parameters schema — may cause API rejection")
                }

                // 执行生成，支持工具调用
                val (finalMessages, hasToolCalls, hasJumpFlag) = generateWithTools(
                    conversationId = conversationId,
                    providerImpl = providerImpl,
                    providerSetting = providerSetting,
                    initialMessages = messages,
                    params = params,
                    tools = tools,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                    allowAllToolsAndPlugins = allowAllToolsAndPlugins,
                )

                // 提取AI消息
                val aiMessage = finalMessages.lastOrNull() ?: UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = emptyList()
                )

                // 解析 [JUMP] 标记（AI总是可以跳转，不需要开关）
                val rawText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }.trim()
                val replyText = rawText.replace("\\[JUMP]".toRegex(RegexOption.IGNORE_CASE), "").trim()
                // AI总是可以跳转，不需要allowForceJump开关
                val shouldJump = hasJumpFlag

                // 若移除了标记，同步更新 session 里 aiMessage 的文本 parts
                if (rawText != replyText) {
                    val cleanedAiMessage = aiMessage.copy(
                        parts = aiMessage.parts.map { part ->
                            if (part is UIMessagePart.Text) {
                                UIMessagePart.Text(part.text.replace("\\[JUMP]".toRegex(RegexOption.IGNORE_CASE), "").trim())
                            } else {
                                part
                            }
                        }
                    )
                    updateOrAppendAiMessage(conversationId, cleanedAiMessage)
                }

                Log.d(TAG, "Proactive message generated: '${replyText.take(100)}...' (${replyText.length} chars), hasToolCalls=$hasToolCalls, shouldJump=$shouldJump")

                if (isSilentProactiveReply(rawText)) {
                    // AI 选择跳过，移除本次生成的 aiMessage node（基于 id 匹配，不误删历史）
                    Log.d(ProactiveMessageService.TAG, "AI chose to skip proactive message")
                    val aiId = aiMessage.id
                    val session = chatService.getOrCreateSession(conversationId)
                    session.saveMutex.withLock {
                        val conv = chatService.getConversationFlow(conversationId).value
                        chatService.updateConversation(
                            conversationId,
                            conv.copy(
                                messageNodes = conv.messageNodes.filterNot { node ->
                                    node.messages.any { it.id == aiId }
                                }
                            )
                        )
                        chatService.saveConversation(conversationId, chatService.getConversationFlow(conversationId).value)
                    }
                } else {
                    // 有效回复：session 里已有 aiMessage（流式过程已追加），持久化并发通知
                    saveProactiveMessage(
                        settings, assistant, conversationId, conversation
                    )
                    // 同步保存 AI 主动消息 / 激进模式回复到外置记忆库（Supabase）
                    // 保证日记总结（DiarySummaryService 只读 Supabase chat_messages 表）和记忆召回能看到这部分内容
                    try {
                        val externalMemoryConfigs = settings.externalMemories.filter {
                            it.enabled && it.id in assistant.externalMemoryIds && it.autoSaveMessages
                        }
                        if (externalMemoryConfigs.isNotEmpty() && replyText.isNotBlank()) {
                            kotlinx.coroutines.coroutineScope {
                                externalMemoryConfigs.forEach { config ->
                                    launch {
                                        runCatching {
                                            val service = ExternalMemoryService(config)
                                            service.saveMessage(
                                                assistantId = assistant.id.toString(),
                                                conversationId = conversationId.toString(),
                                                role = "assistant",
                                                content = replyText,
                                            )
                                        }.onFailure {
                                            Log.w(
                                                ProactiveMessageService.TAG,
                                                "Failed to save proactive message to external memory ${config.name}",
                                                it
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(ProactiveMessageService.TAG, "Failed to save proactive message to external memory", e)
                    }
                    showProactiveNotification(conversationId, assistant.name.ifBlank { "AI" }, replyText)
                    // 悬浮球提醒（作为通知之上的增强层；无 overlay 权限时由 FloatingBubbleService 兜底跳过）
                    if (proactiveSetting.floatingBubbleEnabled) {
                        FloatingBubbleService.show(
                            context = this@ProactiveMessageTriggerService,
                            conversationId = conversationId.toString(),
                            senderName = assistant.name.ifBlank { "AI" },
                            avatar = assistant.avatar,
                        )
                    }
                    // 强制跳转屏幕到聊天界面（方案 A：普通拉起前台）
                    if (shouldJump) {
                        try {
                            val jumpIntent = Intent(this@ProactiveMessageTriggerService, RouteActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                                putExtra("conversationId", conversationId.toString())
                            }
                            startActivity(jumpIntent)
                            Log.d(TAG, "Force jump to conversation $conversationId")
                        } catch (e: Exception) {
                            Log.e(TAG, "Force jump failed", e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 协程被取消（通常是用户发了新消息，sendMessage 里 session.getJob()?.cancel() 触发），
                // 这是正常情况，不应当成错误。打印 debug 日志并重新抛出，遵循 Kotlin 协程取消传播语义。
                // 注意 catch 顺序：CancellationException 必须在 Exception 之前，否则被泛化分支提前吃掉。
                // 重新抛出后 finally 块仍会正常执行（scheduleNext 已用 NonCancellable 保护）。
                Log.d(
                    ProactiveMessageService.TAG,
                    "Proactive generation cancelled (likely user started a new message), " +
                        "conversationId=$conversationId"
                )
                throw e
            } catch (e: Exception) {
                Log.e(ProactiveMessageService.TAG, "Failed to trigger proactive message", e)
                // 如果是 API 返回的 HTTP 错误, 把原始错误体也打出来便于定位
                val cause = e.cause
                if (cause != null) {
                    Log.e(ProactiveMessageService.TAG, "Underlying cause: ${cause::class.simpleName}: ${cause.message}", cause)
                }
                // 清理本次触发中流式写入的不完整/错误 AI 消息, 防止它们污染历史导致下一轮请求失败
                conversationId?.let { cid ->
                    try {
                        val session = chatService.getOrCreateSession(cid)
                        session.saveMutex.withLock {
                            val conv = chatService.getConversationFlow(cid).value
                            // 移除包含网关错误信息的消息 (防止污染下一轮请求)
                            val updatedNodes = conv.messageNodes.filterNot { node ->
                                node.messages.any { msg ->
                                    msg.parts.any { part ->
                                        (part is UIMessagePart.Text && (
                                            part.text.contains("呼叫大模型时被拒绝了") ||
                                            part.text.contains("Invalid request body")
                                        ))
                                    }
                                }
                            }
                            if (updatedNodes.size != conv.messageNodes.size) {
                                chatService.updateConversation(cid, conv.copy(messageNodes = updatedNodes))
                                chatService.saveConversation(cid, chatService.getConversationFlow(cid).value)
                                Log.d(ProactiveMessageService.TAG, "Cleaned up error messages from conversation history")
                            }
                        }
                    } catch (cleanupErr: Exception) {
                        Log.w(ProactiveMessageService.TAG, "Failed to cleanup error messages", cleanupErr)
                    }
                }
            } finally {
                // 确保无论成功/失败/取消都安排下一次，避免一次 API 错误或用户打断永久中断定时链。
                // 激进模式设备事件触发时不需要安排下一次定时主动消息（由 DeviceEventAiTriggerService 自己驱动）。
                // 用 NonCancellable 包裹：协程被取消后处于已取消状态，finally 里的挂起点
                // (settingsFlow.first()) 会立刻抛 CancellationException，导致 scheduleNext 被跳过、
                // 定时链断裂。NonCancellable 保证这段收尾逻辑跑完。
                if (!isFromDeviceEvent && !isWorkflowWake) {
                    withContext(NonCancellable) {
                        try {
                            val currentSettings = settingsStore.settingsFlow.first()
                            ProactiveMessageService.scheduleNext(
                                this@ProactiveMessageTriggerService,
                                currentSettings.proactiveMessageSetting
                            )
                        } catch (e: Exception) {
                            Log.e(ProactiveMessageService.TAG, "Failed to reschedule after completion/error/cancellation", e)
                        }
                    }
                }
                conversationId?.let { chatService.removeConversationReference(it) }
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    /**
     * 构建系统提示词，包含记忆等内容
     * isFromDeviceEvent: 是否由激进模式设备事件触发
     */
    private suspend fun buildSystemPrompt(
        assistant: Assistant,
        settings: Settings,
        model: Model,
        tools: List<Tool> = emptyList(),
        historyMessages: List<UIMessage> = emptyList(),
        jumpThreshold: Int = 120,
        isFromDeviceEvent: Boolean = false,
        workflowWakeContext: String? = null,
    ): String {
        return buildString {
            // 基础系统提示词
            if (assistant.systemPrompt.isNotBlank()) {
                append(assistant.systemPrompt)
            }

            // 记忆：与普通消息路径 (GenerationHandler.buildMemoryPrompt) 保持逐字一致，
            // 这样记忆内容相同时两边的 system prompt 前缀可命中 prefix cache
            if (assistant.enableMemory) {
                val memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                }
                if (memories.isNotEmpty()) {
                    appendLine()
                    append(buildMemoryPrompt(memories))
                }
            }

            // 代码块规则：与普通消息路径保持一致
            appendLine()
            append(buildCodeBlockPrompt())

            // 工具说明：基于实际启用/传给模型的工具逐个追加（与普通消息一致）
            tools.forEach { tool ->
                appendLine()
                append(tool.systemPrompt(model, historyMessages))
            }

            if (settings.systemToolsSetting.dynamicContextEnabled) {
                appendLine()
                appendLine()
                append(DYNAMIC_CONTEXT_SYSTEM_POLICY)
            }

            // 允许跳过回复（与普通消息路径一致）
            if (assistant.allowSkipReply) {
                appendLine()
                appendLine()
                appendLine("## Skip Reply")
                appendLine("If you determine that no reply is needed (e.g., the user's message doesn't require a response, or you have nothing meaningful to add), you may reply with exactly `[SKIP]` (without any other text). This message will be hidden from the user. Use this sparingly and only when truly appropriate.")
            }

            // 屏幕跳转能力（与普通消息路径一致，AI 总是可以跳转）
            appendLine()
            appendLine()
            appendLine("## 屏幕跳转能力")
            appendLine("你可以在回复末尾追加 [JUMP] 标记（单独一行）来把聊天界面拉到用户屏幕最前面。")
            appendLine("适用场景：")
            appendLine("- 用户说要去别的应用，你觉得需要把用户拉回来时")
            appendLine("- 你觉得接下来的内容需要用户立即看到时")
            appendLine("不适用场景：")
            appendLine("- 一般闲聊不需要跳转")
            appendLine("- 用户正在跟你正常对话时不需要跳转")
            appendLine("[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。")

            // 分气泡（与普通消息路径一致）
            if (assistant.splitBubbleByLine) {
                appendLine()
                appendLine()
                appendLine("## Message Bubbles")
                appendLine("Your reply will be automatically split into separate chat bubbles at every line break (\\n) you write, similar to how a person sends several short texts in a row instead of one long message. You are fully in control of this: write a line break whenever you want the previous thought/sentence to appear as its own bubble, and keep things on the same line when they belong together. Do not insert blank lines purely for spacing — every line break becomes a new bubble, so use them intentionally. Exception: line breaks inside fenced code blocks (```) and Markdown tables are preserved as-is and will NOT create new bubbles, since those must stay intact as a single block.")
            }

            // 触发指令段：始终放在 system prompt 的最末尾，
            // 保证普通消息↔主动/工作流/设备事件之间的固定前缀不被触发指令打断，利于 prefix cache
            if (!workflowWakeContext.isNullOrBlank()) {
                appendLine()
                appendLine()
                appendLine("## 工作流唤醒")
                appendLine("这是工作流动作完成后的数据回流，不是用户新发的消息。")
                appendLine("必须优先执行用户消息中的“自定义处理要求”，不得自行判断任务不重要而跳过。")
                appendLine("只有自定义处理要求明确允许在当前条件下保持静默时，才可以只回复 [PASS]。")
                appendLine("不要用 SKIP、忽略任务或泛化的主动消息策略替代自定义处理要求。")
                appendLine("[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。")
            } else if (isFromDeviceEvent) {
                appendLine()
                appendLine()
                appendLine("## ⚠️ 当前触发原因：用户手机动向（设备事件触发）")
                appendLine("你是因为检测到用户的手机操作动向（切换应用/亮屏锁屏/回桌面）而被触发的。")
                appendLine("请特别注意：这是设备事件触发，不是定时主动消息。根据用户的手机操作动向来决定是否发消息。")
                appendLine("绝对不要复述上一轮的对话内容，要发新的话题或新的关心。")
                appendLine("请根据用户的动向，自然地决定是否主动发一条消息。")
                appendLine("如果你觉得现在没什么好说的，或者没什么有趣的话题，请只回复 [PASS] 即可。")
                appendLine("[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。")
            } else {
                appendLine()
                appendLine()
                appendLine("## 主动消息触发（定时触发）")
                appendLine("这是定时触发的主动消息，不是设备事件触发。")
                appendLine("绝对不要复述上一轮的对话内容，要发新的话题或新的关心。")
                appendLine("如果你觉得现在没什么好说的，或者没什么有趣的话题，请只回复 [PASS] 即可。")
                appendLine("[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。")
            }
        }
    }

    /**
     * 保存主动消息到对话历史
     * 同时保存用户上下文消息和AI回复，以便AI下次触发时能看到之前的上下文
     */
    private suspend fun saveProactiveMessage(
        settings: Settings,
        assistant: Assistant,
        conversationId: Uuid,
        existingConversation: Conversation?
    ): Uuid {
        val assistantUuid = assistant.id

        // 确保对话存在于数据库（新建时 insert）。
        // 若 session 为空且没有已存对话，先 insert 一条空对话占位，避免后续 saveConversation 跳过。
        if (existingConversation == null) {
            val session = chatService.getOrCreateSession(conversationId)
            session.saveMutex.withLock {
                val exists = conversationRepository.existsConversationById(conversationId)
                if (!exists) {
                    val newConversation = Conversation(
                        id = conversationId,
                        assistantId = assistantUuid,
                        title = "",
                        messageNodes = emptyList()
                    )
                    conversationRepository.insertConversation(newConversation)
                }
            }
        }

        // 流式过程中已实时追加 aiMessage 到 session，这里直接持久化当前 session 状态。
        // 加 saveMutex 防止与用户发送消息/其他主动消息并发覆盖。
        val session = chatService.getOrCreateSession(conversationId)
        session.saveMutex.withLock {
            chatService.saveConversation(
                conversationId,
                chatService.getConversationFlow(conversationId).value
            )
        }

        Log.d(TAG, "Saved proactive message to conversation $conversationId")
        return conversationId
    }

    private fun showProactiveNotification(
        conversationId: kotlin.uuid.Uuid,
        senderName: String,
        message: String
    ) {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 20002
        ) {
            title = senderName
            content = message.take(100)
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = pendingIntent
            useBigTextStyle = true
        }
    }

    /**
     * 默认保留原来的主动消息精简工具面，避免无意扩大后台权限和请求体。
     * 用户显式授权后改用 ToolSurfaceBuilder 的完整工具面。
     * 两个分支都额外带上 write_files，使 system prompt 中与普通消息一致的
     * Code Block Rules 工具说明与实际可用工具保持一致。
     */
    private suspend fun buildTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        allowAllToolsAndPlugins: Boolean,
        recentMessages: List<UIMessage>,
        workspaceCwd: String?,
        conversationId: Uuid,
    ): List<Tool> {
        if (allowAllToolsAndPlugins) {
            // 完整工具面之上额外补充 write_files，使 system prompt 中与普通消息一致的
            // Code Block Rules 工具说明与实际可用工具保持一致（避免说明有工具、实际却没有）
            return buildList {
                add(buildWriteFilesTool(conversationId.toString()))
                addAll(
                    toolSurfaceBuilder.build(
                        assistant = assistant,
                        settings = settings,
                        invocationContext = me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                            callerAssistantId = assistant.id.toString(),
                            callerConversationId = conversationId.toString(),
                            isHeadless = true,
                            modelCanSeeImages = model.inputModalities.contains(
                                me.rerere.ai.provider.Modality.IMAGE,
                            ),
                        ),
                        recentMessages = recentMessages,
                        workspaceCwd = workspaceCwd,
                    )
                )
            }
        }
        return buildList {
            // 文件写入工具 - 与普通消息路径 (GenerationHandler) 保持一致，
            // 使 Code Block Rules 说明与实际可用工具一致
            add(buildWriteFilesTool(conversationId.toString()))

            // 本地工具（助手已启用的）
            addAll(
                localTools.getTools(
                    assistant.localTools,
                    me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                        callerAssistantId = assistant.id.toString(),
                        callerConversationId = conversationId.toString(),
                        callerAssistantName = assistant.name,
                        userPatAction = settings.displaySetting.userPatAction,
                        userPatSuffix = settings.displaySetting.userPatSuffix,
                        isHeadless = true,
                        modelCanSeeImages = model.inputModalities.contains(
                            me.rerere.ai.provider.Modality.IMAGE,
                        ),
                    ),
                )
            )

            // 系统工具（位置、通知、日历、闹钟、相机）
            val systemToolsOptions = settings.systemToolsSetting.getEnabledOptions()
            if (systemToolsOptions.isNotEmpty()) {
                val systemTools = SystemTools(this@ProactiveMessageTriggerService, settings)
                addAll(systemTools.getTools(systemToolsOptions))
            }

            // MCP 工具
            mcpManager.getAllAvailableTools().forEach { (serverId, tool) ->
                add(
                    Tool(
                        name = ToolNaming.buildMcpToolName(serverId, tool.name),
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = tool.needsApproval,
                        execute = {
                            mcpManager.callTool(serverId, tool.name, it.jsonObject)
                        },
                    )
                )
            }

            // 插件工具
            addAll(pluginToolProvider.getTools())
        }
    }

    /**
     * 基于 AI 消息 id 在对话里就地更新（保留 MessageNode.id，避免 Compose 重建/状态丢失）
     * 或追加新 node。不使用 toMessageNode() 生成随机新 id，也不用 dropLast(1) 盲目删除。
     *
     * 注意：这里必须走 saveMutex 保护，因为流式更新与 ChatService.sendMessage/addProactiveMessage
     * 可能并发修改同一会话，read-modify-write 不加锁会导致后写入者覆盖前者。
     */
    private suspend fun updateOrAppendAiMessage(
        conversationId: Uuid,
        aiMessage: UIMessage
    ) {
        val session = chatService.getOrCreateSession(conversationId)
        session.saveMutex.withLock {
            val conv = chatService.getConversationFlow(conversationId).value
            val existingNodeIndex = conv.messageNodes.indexOfFirst { node ->
                node.messages.any { it.id == aiMessage.id }
            }
            val updated = if (existingNodeIndex >= 0) {
                // 已存在该 id 的 node：保留 node id，只更新其 messages
                val oldNode = conv.messageNodes[existingNodeIndex]
                val updatedNode = oldNode.copy(
                    messages = oldNode.messages.map {
                        if (it.id == aiMessage.id) aiMessage else it
                    }
                )
                conv.copy(
                    messageNodes = conv.messageNodes.toMutableList().apply {
                        this[existingNodeIndex] = updatedNode
                    }
                )
            } else {
                // 本次生成的 node 还没有：追加（首次调用时才创建新 node）
                conv.copy(messageNodes = conv.messageNodes + aiMessage.toMessageNode())
            }
            chatService.updateConversation(conversationId, updated)
            chatService.saveConversation(conversationId, updated)
        }
    }

    /**
     * 过滤历史消息中"悬空"的工具调用：
     * 若某条消息存在未执行(isExecuted=false)且不可恢复(approvalState.canResumeToolExecution()==false)的工具调用，
     * 说明这条消息的工具调用链没有走完（如上次生成被中断、或需要审批但用户一直没确认），
     * 直接把整条消息从历史里剔除，避免把结构不完整的 tool_use 发给 API 触发 400。
     *
     * 判断逻辑与 ChatService.checkInvalidMessages 保持一致：
     * 只要该消息里存在"至少一个可恢复的待处理工具"，就保留整条消息不做删除；
     * 只有当所有待处理工具都不可恢复时，才整条移除。
     */
    private fun filterInvalidToolMessages(messages: List<UIMessage>): List<UIMessage> {
        return messages.filterNot { message ->
            val tools = message.getTools()
            val hasPendingTools = tools.any { !it.isExecuted }
            if (!hasPendingTools) return@filterNot false
            val hasResumableTool = tools.any { !it.isExecuted && it.approvalState.canResumeToolExecution() }
            !hasResumableTool
        }
    }

    /**
     * 生成消息，支持工具调用
     * 返回最终消息列表和是否发生了工具调用
     */
    private suspend fun generateWithTools(
        conversationId: Uuid,
        providerImpl: me.rerere.ai.provider.Provider<ProviderSetting>,
        providerSetting: ProviderSetting,
        initialMessages: List<UIMessage>,
        params: TextGenerationParams,
        tools: List<Tool>,
        model: Model,
        assistant: Assistant,
        settings: Settings,
        allowAllToolsAndPlugins: Boolean,
    ): Triple<List<UIMessage>, Boolean, Boolean> {
        var messages = initialMessages.toMutableList()
        var hasToolCalls = false
        var hasJumpFlag = false // AI 原始输出是否含 [JUMP] 标记（在输出转换器处理前检测）

        for (step in 0 until MAX_TOOL_STEPS) {
            Log.d(TAG, "generateWithTools: step $step/${MAX_TOOL_STEPS}")

            // 防御性：每轮调用前合并相邻同角色（尤其 assistant）消息，
            // 避免工具调用多步生成产生相邻 assistant 消息触发 API 400
            messages = mergeAdjacentSameRoleMessages(messages).toMutableList()

            // 流式调用 AI（替代非流式 generateText，兼容 thinking 模型）
            var streamMessages = messages.toList()
            providerImpl.streamText(
                providerSetting = providerSetting,
                messages = messages,
                params = params
            ).collect { chunk ->
                streamMessages = streamMessages.handleMessageChunk(chunk = chunk, model = model)

                // 实时更新 session 状态，让打开的聊天界面能看到消息生成
                val currentAiMessage = streamMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                if (currentAiMessage != null) {
                    // 用 id 匹配就地更新（保留 node id，避免思考链闪烁 / 覆盖上一条 assistant）
                    updateOrAppendAiMessage(conversationId, currentAiMessage)
                }
            }

            // 流式结束，更新 messages
            messages = streamMessages.toMutableList()
            val aiMessage = streamMessages.lastOrNull() ?: run {
                Log.w(TAG, "No message in AI response")
                break
            }


            // 在输出转换器处理前，检测 AI 原始输出是否含 [JUMP] 标记
            val rawAiText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            if (rawAiText.contains("[JUMP]")) {
                hasJumpFlag = true
                Log.d(TAG, "[JUMP] flag detected in raw AI output")
            }
            // 应用输出转换器
            val processedMessage = listOf(aiMessage).transforms(
                transformers = outputTransformers,
                context = this@ProactiveMessageTriggerService,
                model = model,
                assistant = assistant,
                settings = settings
            ).first()
            messages[messages.lastIndex] = processedMessage

            // 检查是否有工具调用
            val toolCalls = processedMessage.getTools().filter { !it.isExecuted }

            if (toolCalls.isEmpty()) {
                // 没有工具调用，生成完成
                // 设置 Reasoning 的 finishedAt，否则UI会一直显示"思考中"
                val now = kotlin.time.Clock.System.now()
                val finalMessage = processedMessage.copy(
                    parts = processedMessage.parts.map { part ->
                        if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                            part.copy(finishedAt = now)
                        } else {
                            part
                        }
                    }
                )
                messages[messages.lastIndex] = finalMessage
                // 最终更新 session 状态（用 id 匹配就地更新）
                updateOrAppendAiMessage(conversationId, finalMessage)
                break
            }

            // 有工具调用
            hasToolCalls = true
            Log.d(TAG, "Tool calls detected: ${toolCalls.size}")

            // 执行工具（后台模式下自动执行，不需要用户审批）
            val executedTools = mutableListOf<UIMessagePart.Tool>()
            for (toolCall in toolCalls) {
                val toolDef = tools.find { it.name == toolCall.toolName }
                if (toolDef == null) {
                    Log.w(TAG, "Tool ${toolCall.toolName} not found")
                    executedTools.add(toolCall.copy(
                        output = listOf(UIMessagePart.Text("""{"error":"Tool not found"}"""))
                    ))
                    continue
                }

                // 检查是否需要审批
                if (!canExecuteProactiveTool(
                        needsApproval = toolDef.needsApproval,
                        allowAllToolsAndPlugins = allowAllToolsAndPlugins,
                        globallyAutoApproved = settings.autoApproveAllTools,
                    )
                ) {
                    // 后台模式下，需要审批的工具自动拒绝
                    Log.w(TAG, "Tool ${toolCall.toolName} needs approval, auto-denying in proactive mode")
                    executedTools.add(toolCall.copy(
                        output = listOf(UIMessagePart.Text("""{"error":"Tool execution denied: requires user approval in proactive mode"}""")),
                        approvalState = ToolApprovalState.Denied("Proactive mode: requires approval")
                    ))
                } else {
                    // 执行工具
                    try {
                        val args = try {
                            json.parseToJsonElement(toolCall.input.ifBlank { "{}" })
                        } catch (e: Exception) {
                            // toolCall.input 可能因为流式截断而是不完整的 JSON, 回退为空对象
                            Log.w(TAG, "Tool ${toolCall.toolName} input JSON is incomplete, falling back to empty object: ${toolCall.input.take(200)}")
                            JsonObject(emptyMap())
                        }
                        Log.d(TAG, "Executing tool ${toolDef.name} with args: $args")
                        val result = toolDef.execute(args)
                        executedTools.add(toolCall.copy(
                            output = result,
                            approvalState = if (toolDef.needsApproval) {
                                ToolApprovalState.Approved
                            } else {
                                toolCall.approvalState
                            },
                        ))
                    } catch (e: Exception) {
                        Log.e(TAG, "Tool execution failed: ${toolCall.toolName}, args=${toolCall.input}", e)
                        executedTools.add(toolCall.copy(
                            output = listOf(UIMessagePart.Text("""{"error":"${e.message}"}"""))
                        ))
                    }
                }
            }

            // 更新消息中的工具状态
            val updatedParts = processedMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else {
                    part
                }
            }
            val updatedMessage = processedMessage.copy(parts = updatedParts)
            messages[messages.lastIndex] = updatedMessage
            // 更新 session 状态（带工具结果的消息，用 id 匹配就地更新）
            updateOrAppendAiMessage(conversationId, updatedMessage)
        }

        return Triple(messages, hasToolCalls, hasJumpFlag)
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null
}

internal fun canExecuteProactiveTool(
    needsApproval: Boolean,
    allowAllToolsAndPlugins: Boolean,
    globallyAutoApproved: Boolean,
): Boolean = !needsApproval || allowAllToolsAndPlugins || globallyAutoApproved

/**
 * Merge adjacent roles for providers that require alternation, while keeping a dynamic snapshot
 * separate from the real request so its metadata remains a valid gateway boundary.
 */
internal fun mergeAdjacentSameRoleMessages(messages: List<UIMessage>): List<UIMessage> {
    if (messages.size < 2) return messages
    return messages.fold(emptyList()) { acc, msg ->
        val prev = acc.lastOrNull()
        if (
            prev != null &&
            prev.role == msg.role &&
            !prev.isDynamicEnvironmentSnapshot() &&
            !msg.isDynamicEnvironmentSnapshot()
        ) {
            acc.dropLast(1) + prev.copy(
                parts = prev.parts + msg.parts,
                metadata = prev.metadata ?: msg.metadata,
            )
        } else {
            acc + msg
        }
    }
}

private fun UIMessage.isDynamicEnvironmentSnapshot(): Boolean =
    metadata?.get("dynamic_environment")?.toString() == "true"

internal fun buildWorkflowWakeUserInstruction(workflowWakeContext: String): String = buildString {
    appendLine("这是用户已批准执行的工作流任务。")
    appendLine("请立即执行下面的“自定义处理要求”，不要自行改为 SKIP 或 PASS。")
    appendLine("只有自定义处理要求明确允许在当前条件下保持静默时，才可以只回复 [PASS]。")
    appendLine()
    append(workflowWakeContext)
}

internal fun isSilentProactiveReply(rawText: String): Boolean {
    if (rawText.isBlank() || rawText.contains("[PASS]", ignoreCase = true)) {
        return true
    }
    return rawText.matches(
        Regex("""^\s*\[?(?:PASS|SKIP)\]?\s*[.!。！]?\s*$""", RegexOption.IGNORE_CASE),
    )
}

internal fun buildInitialProactiveMessages(
    systemPrompt: String,
    historyMessages: List<UIMessage>,
    dynamicContext: String = "",
    processedUserMessages: List<UIMessage>,
): List<UIMessage> = buildList {
    add(
        UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text(systemPrompt)),
        )
    )
    addAll(historyMessages)
    if (dynamicContext.isNotBlank()) {
        add(
            UIMessage.user(dynamicContext).copy(
                metadata = dynamicEnvironmentMetadata(dynamicContext)
            )
        )
    }
    // TimeReminderTransformer may prepend a separate reminder. Keep every transformed
    // message so the actual workflow/proactive instruction is never discarded.
    addAll(processedUserMessages)
}
