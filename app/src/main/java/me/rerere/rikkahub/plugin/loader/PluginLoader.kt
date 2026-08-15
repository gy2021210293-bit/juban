/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.loader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.service.MemoryBankService
import me.rerere.rikkahub.data.service.ProactiveMessageService
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.model.PluginInfo
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import kotlin.uuid.Uuid

data class PluginPromptInjection(
    val pluginId: String,
    val text: String,
    val priority: Int = 0,
)

data class PluginScheduledHook(
    val pluginId: String,
    val handler: String,
    val schedule: String,
)

/**
 * 插件加载器。
 * 在原有 tool/hook 能力之上补充生命周期、统一事件、动态提示词、定时任务和宿主动作。
 */
class PluginLoader(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val memoryBankService: MemoryBankService? = null,
    private val settingsStore: SettingsStore? = null
) {
    companion object {
        private const val TAG = "PluginLoader"
        private const val HOOK_TIMEOUT_MS = 16_500L
        private const val DEFAULT_DAILY_CRON = "0 3 * * *"
        private const val MIN_AI_WAKE_INTERVAL_MS = 30_000L
        private const val MAX_HOST_PROMPT_LENGTH = 8_000

        const val PERMISSION_PROMPT_INJECT = "prompt_inject"
        const val PERMISSION_AI_CHAT = "ai_chat"
        const val PERMISSION_AI_TOOLS = "ai_tools"
    }

    private val pluginDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "plugin-quickjs").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val loadedPlugins = mutableMapOf<String, LoadedPlugin>()
    private val lastAiWakeAtByPlugin = mutableMapOf<String, Long>()

    suspend fun loadPlugin(pluginInfo: PluginInfo): Result<LoadedPlugin> = withContext(pluginDispatcher) {
        try {
            if (loadedPlugins.containsKey(pluginInfo.manifest.id)) {
                doUnloadPlugin(pluginInfo.manifest.id)
            }

            if (!pluginInfo.isEnabled) {
                return@withContext Result.failure(IllegalStateException("Plugin is disabled"))
            }

            val entryFile = pluginInfo.getEntryFile()
            if (!entryFile.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Entry file not found: ${pluginInfo.manifest.entry}")
                )
            }

            val dataStore = PluginDataStore(context, pluginInfo.manifest.id)
            val sandbox = PluginSandbox(context, okHttpClient, memoryBankService, dataStore)
            sandbox.allowedHosts = pluginInfo.manifest.allowedHosts
            sandbox.initialize()

            val resolvedConfig = resolveModelConfig(pluginInfo)
            sandbox.injectConfig(resolvedConfig)
            sandbox.evaluateFile(entryFile)

            val loadedPlugin = LoadedPlugin(info = pluginInfo, sandbox = sandbox)
            val exportedNames = sandbox.getExportedFunctionNames()
            Log.i(TAG, "Plugin ${pluginInfo.manifest.id} exported functions: $exportedNames")

            pluginInfo.manifest.tools.forEach { tool ->
                if (!sandbox.hasFunction(tool.name)) {
                    Log.w(TAG, "Tool '${tool.name}' declared in manifest but not found in exports (available: $exportedNames)")
                } else {
                    Log.i(TAG, "Tool '${tool.name}' registered successfully")
                }
            }

            loadedPlugins[pluginInfo.manifest.id] = loadedPlugin

            val loadContext = buildLifecycleContext(loadedPlugin, "plugin.loaded")
            invokeOptionalFunction(loadedPlugin, "onLoad", loadContext)
            dispatchGenericEvent(loadedPlugin, "plugin.loaded", loadContext)

            val enableContext = buildLifecycleContext(loadedPlugin, "plugin.enabled")
            invokeOptionalFunction(loadedPlugin, "onEnable", enableContext)
            dispatchGenericEvent(loadedPlugin, "plugin.enabled", enableContext)

            Result.success(loadedPlugin)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin ${pluginInfo.manifest.id}", e)
            loadedPlugins.remove(pluginInfo.manifest.id)?.sandbox?.destroy()
            Result.failure(e)
        }
    }

    private suspend fun doUnloadPlugin(pluginId: String) {
        val plugin = loadedPlugins[pluginId] ?: return

        val disableContext = buildLifecycleContext(plugin, "plugin.disabled")
        invokeOptionalFunction(plugin, "onDisable", disableContext)
        dispatchGenericEvent(plugin, "plugin.disabled", disableContext)

        val unloadContext = buildLifecycleContext(plugin, "plugin.unloaded")
        invokeOptionalFunction(plugin, "onUnload", unloadContext)
        dispatchGenericEvent(plugin, "plugin.unloaded", unloadContext)

        loadedPlugins.remove(pluginId)
        lastAiWakeAtByPlugin.remove(pluginId)
        plugin.sandbox.destroy()
        Log.d(TAG, "Unloaded plugin: $pluginId")
    }

    suspend fun unloadPlugin(pluginId: String) = withContext(pluginDispatcher) {
        doUnloadPlugin(pluginId)
    }

    suspend fun reloadPlugin(pluginInfo: PluginInfo): Result<LoadedPlugin> = loadPlugin(pluginInfo)

    fun getLoadedPlugin(pluginId: String): LoadedPlugin? = loadedPlugins[pluginId]
    fun getAllLoadedPlugins(): List<LoadedPlugin> = loadedPlugins.values.toList()
    fun getEnabledPlugins(): List<LoadedPlugin> = loadedPlugins.values.filter { it.info.isEnabled }

    suspend fun callTool(pluginId: String, toolName: String, params: JsonElement): Result<JsonElement> {
        return withContext(pluginDispatcher) {
            try {
                val plugin = loadedPlugins[pluginId]
                    ?: return@withContext Result.failure(IllegalStateException("Plugin not loaded: $pluginId"))

                if (!plugin.hasTool(toolName)) {
                    return@withContext Result.failure(IllegalArgumentException("Tool not found: $toolName"))
                }

                Result.success(plugin.sandbox.callFunction(toolName, params))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call tool=$toolName in plugin=$pluginId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 触发插件事件。
     * 旧 manifest.hooks 继续生效；新版插件可只导出 onEvent(event) 统一监听。
     */
    suspend fun callEvent(event: String, params: JsonElement) {
        withContext(pluginDispatcher) {
            for (plugin in loadedPlugins.values.toList()) {
                if (!plugin.info.isEnabled) continue

                val matchingHooks = plugin.info.manifest.hooks.filter { it.event == event }
                for (hook in matchingHooks) {
                    invokeOptionalFunction(
                        plugin = plugin,
                        functionName = hook.handler,
                        params = params,
                        logLabel = "event='$event'"
                    )
                }

                if (matchingHooks.none { it.handler == "onEvent" }) {
                    val canonicalType = canonicalEventName(event)
                    val envelope = buildJsonObject {
                        put("type", canonicalType)
                        put("legacyType", event)
                        put("timestamp", System.currentTimeMillis())
                        put("pluginId", plugin.id)
                        put("data", params)
                    }
                    dispatchGenericEvent(plugin, canonicalType, envelope)
                }
            }
        }
    }

    /**
     * 只触发一个已经声明的定时 hook。
     * 通用 Cron 调度器使用该入口，避免某个插件到点时把全部 daily_cron 插件一起唤醒。
     */
    suspend fun callScheduledHook(pluginId: String, handler: String, params: JsonElement) {
        withContext(pluginDispatcher) {
            val plugin = loadedPlugins[pluginId] ?: return@withContext
            if (!plugin.info.isEnabled) return@withContext

            val declared = plugin.info.manifest.hooks.any {
                it.event == "daily_cron" && it.handler == handler
            }
            if (!declared) {
                Log.w(TAG, "Ignoring undeclared scheduled hook: plugin=$pluginId, handler=$handler")
                return@withContext
            }

            invokeOptionalFunction(
                plugin = plugin,
                functionName = handler,
                params = params,
                logLabel = "scheduled daily_cron"
            )

            if (handler != "onEvent" && plugin.sandbox.hasFunction("onEvent")) {
                val envelope = buildJsonObject {
                    put("type", "scheduler.daily_cron")
                    put("legacyType", "daily_cron")
                    put("timestamp", System.currentTimeMillis())
                    put("pluginId", plugin.id)
                    put("data", params)
                }
                dispatchGenericEvent(plugin, "scheduler.daily_cron", envelope)
            }
        }
    }

    /**
     * 每次上游模型请求前重新调用 providePrompt(ctx)。
     * 需要 manifest.permissions 包含 prompt_inject。
     */
    suspend fun getDynamicPromptInjections(): List<PluginPromptInjection> = withContext(pluginDispatcher) {
        val injections = mutableListOf<PluginPromptInjection>()

        for (plugin in loadedPlugins.values.toList()) {
            if (!plugin.info.isEnabled) continue
            if (PERMISSION_PROMPT_INJECT !in plugin.info.manifest.permissions) continue
            if (!plugin.sandbox.hasFunction("providePrompt")) continue

            val promptContext = buildJsonObject {
                put("pluginId", plugin.id)
                put("pluginName", plugin.name)
                put("timestamp", System.currentTimeMillis())
            }

            val result = withTimeoutOrNull(HOOK_TIMEOUT_MS) {
                plugin.sandbox.callFunction("providePrompt", promptContext)
            }

            if (result == null) {
                Log.w(TAG, "Dynamic prompt timed out: plugin=${plugin.id}")
                continue
            }

            parsePromptInjection(plugin.id, result)?.let(injections::add)
        }

        injections.sortedByDescending { it.priority }
    }

    fun getScheduledHooks(): List<PluginScheduledHook> {
        return loadedPlugins.values
            .filter { it.info.isEnabled }
            .flatMap { plugin ->
                plugin.info.manifest.hooks
                    .filter { it.event == "daily_cron" }
                    .map { hook ->
                        PluginScheduledHook(
                            pluginId = plugin.id,
                            handler = hook.handler,
                            schedule = hook.schedule?.takeIf { it.isNotBlank() } ?: DEFAULT_DAILY_CRON,
                        )
                    }
            }
    }

    fun getPluginsWithDailyCron(): List<Pair<LoadedPlugin, String>> {
        return loadedPlugins.values.filter { it.info.isEnabled }.flatMap { plugin ->
            plugin.info.manifest.hooks
                .filter { it.event == "daily_cron" }
                .map { hook -> plugin to hook.handler }
        }
    }

    /**
     * 执行可选插件回调，并解释其返回的宿主动作。
     *
     * 支持：
     * { "hostAction": "ai.wake", "prompt": "...", "assistantId": "...", "allowTools": false }
     * 也支持返回上述对象的数组。ai.wake 需要 ai_chat 权限；allowTools=true 还需要 ai_tools 权限。
     */
    private suspend fun invokeOptionalFunction(
        plugin: LoadedPlugin,
        functionName: String,
        params: JsonElement,
        logLabel: String = functionName,
    ): JsonElement? {
        if (!plugin.sandbox.hasFunction(functionName)) return null

        return try {
            val completed = withTimeoutOrNull(HOOK_TIMEOUT_MS) {
                plugin.sandbox.callFunction(functionName, params)
            }
            if (completed == null) {
                Log.w(TAG, "Plugin callback timed out: plugin=${plugin.id}, function='$functionName', $logLabel")
                null
            } else {
                Log.d(TAG, "Plugin callback completed: plugin=${plugin.id}, function=$functionName, $logLabel")
                handleHostActionResult(plugin, completed)
                completed
            }
        } catch (e: Exception) {
            Log.e(TAG, "Plugin callback failed: plugin=${plugin.id}, function=$functionName, $logLabel", e)
            null
        }
    }

    private suspend fun dispatchGenericEvent(
        plugin: LoadedPlugin,
        eventType: String,
        data: JsonElement,
    ) {
        if (!plugin.sandbox.hasFunction("onEvent")) return

        val envelope = if (data is JsonObject && data.containsKey("type")) {
            data
        } else {
            buildJsonObject {
                put("type", eventType)
                put("timestamp", System.currentTimeMillis())
                put("pluginId", plugin.id)
                put("data", data)
            }
        }
        invokeOptionalFunction(plugin, "onEvent", envelope, "type='$eventType'")
    }

    /**
     * Host Action Bridge：事件函数通过返回结构化对象请求宿主执行 App 能力。
     * 这样无需把 Android 对象直接暴露进 QuickJS，同时所有敏感动作都经过 manifest 权限检查。
     */
    private fun handleHostActionResult(plugin: LoadedPlugin, result: JsonElement) {
        when (result) {
            is JsonObject -> handleHostAction(plugin, result)
            is JsonArray -> result.forEach { item ->
                if (item is JsonObject) handleHostAction(plugin, item)
            }
            else -> Unit
        }
    }

    private fun handleHostAction(plugin: LoadedPlugin, action: JsonObject) {
        val actionName = (action["hostAction"] as? JsonPrimitive)?.contentOrNull ?: return

        when (actionName) {
            "ai.wake" -> {
                if (PERMISSION_AI_CHAT !in plugin.info.manifest.permissions) {
                    Log.w(TAG, "Blocked ai.wake without ai_chat permission: plugin=${plugin.id}")
                    return
                }

                val now = System.currentTimeMillis()
                val lastWake = lastAiWakeAtByPlugin[plugin.id] ?: 0L
                if (now - lastWake < MIN_AI_WAKE_INTERVAL_MS) {
                    Log.w(TAG, "Rate-limited ai.wake: plugin=${plugin.id}")
                    return
                }

                val prompt = (action["prompt"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
                    .take(MAX_HOST_PROMPT_LENGTH)
                if (prompt.isBlank()) {
                    Log.w(TAG, "Ignored ai.wake with empty prompt: plugin=${plugin.id}")
                    return
                }

                val assistantId = (action["assistantId"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
                val requestedTools = (action["allowTools"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.toBooleanStrictOrNull()
                    ?: false
                val allowTools = requestedTools && PERMISSION_AI_TOOLS in plugin.info.manifest.permissions

                val launchResult = ProactiveMessageService.triggerFromWorkflow(
                    context = context,
                    assistantId = assistantId,
                    workflowName = "插件唤醒：${plugin.name}",
                    prompt = prompt,
                    actionOutput = null,
                    allowAllToolsAndPlugins = allowTools,
                )

                launchResult.onSuccess {
                    lastAiWakeAtByPlugin[plugin.id] = now
                    Log.i(TAG, "Plugin AI wake started: plugin=${plugin.id}, allowTools=$allowTools")
                }.onFailure { error ->
                    Log.e(TAG, "Plugin AI wake failed: plugin=${plugin.id}", error)
                }
            }
            else -> Log.w(TAG, "Unknown plugin host action '$actionName' from ${plugin.id}")
        }
    }

    private fun buildLifecycleContext(plugin: LoadedPlugin, eventType: String): JsonObject {
        return buildJsonObject {
            put("type", eventType)
            put("timestamp", System.currentTimeMillis())
            put("pluginId", plugin.id)
            put("pluginName", plugin.name)
            put("version", plugin.info.manifest.version)
        }
    }

    private fun canonicalEventName(event: String): String {
        return when (event) {
            "message_sent" -> "chat.user_message"
            "message_received" -> "chat.assistant_message"
            "daily_cron" -> "scheduler.daily_cron"
            "app_started" -> "app.started"
            "app_foreground" -> "app.foreground"
            "app_background" -> "app.background"
            else -> event.replace('_', '.')
        }
    }

    private fun parsePromptInjection(pluginId: String, result: JsonElement): PluginPromptInjection? {
        return when (result) {
            is JsonPrimitive -> {
                val text = result.contentOrNull?.trim().orEmpty()
                text.takeIf { it.isNotEmpty() }?.let {
                    PluginPromptInjection(pluginId = pluginId, text = it)
                }
            }
            is JsonObject -> {
                val text = (result["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) return null
                val priority = (result["priority"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.toIntOrNull()
                    ?: 0
                PluginPromptInjection(pluginId = pluginId, text = text, priority = priority)
            }
            else -> null
        }
    }

    private fun resolveModelConfig(pluginInfo: PluginInfo): Map<String, JsonElement> {
        val config = pluginInfo.config.toMutableMap()
        val store = settingsStore ?: return config
        val settings = store.settingsFlow.value

        pluginInfo.manifest.config.forEach { field ->
            if (field.type == "model") {
                val modelUuidStr = (config[field.name] as? JsonPrimitive)?.contentOrNull
                if (modelUuidStr.isNullOrBlank()) return@forEach
                try {
                    val modelUuid = Uuid.parse(modelUuidStr)
                    val model = settings.findModelById(modelUuid) ?: return@forEach
                    val provider = model.findProvider(settings.providers) ?: return@forEach

                    val baseUrl = when (provider) {
                        is ProviderSetting.OpenAI -> provider.baseUrl
                        is ProviderSetting.Google -> provider.baseUrl
                        is ProviderSetting.Claude -> provider.baseUrl
                    }
                    val apiKey = when (provider) {
                        is ProviderSetting.OpenAI -> provider.apiKey
                        is ProviderSetting.Google -> provider.apiKey
                        is ProviderSetting.Claude -> provider.apiKey
                    }

                    config[field.name] = JsonPrimitive(model.modelId)
                    config["${field.name}_base_url"] = JsonPrimitive(baseUrl)
                    config["${field.name}_api_key"] = JsonPrimitive(apiKey)
                    Log.d(TAG, "Resolved model config '${field.name}': modelId=${model.modelId}, baseUrl=$baseUrl")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve model config '${field.name}': ${e.message}")
                }
            }
        }
        return config
    }

    suspend fun unloadAll() = withContext(pluginDispatcher) {
        loadedPlugins.keys.toList().forEach { doUnloadPlugin(it) }
    }
}