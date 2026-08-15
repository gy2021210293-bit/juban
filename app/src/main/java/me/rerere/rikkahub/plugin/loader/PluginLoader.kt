/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.loader
 
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.model.PluginInfo
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import kotlin.uuid.Uuid

/**
 * 插件动态提示词。
 * priority 越大，越靠前注入 system prompt。
 */
data class PluginPromptInjection(
    val pluginId: String,
    val text: String,
    val priority: Int = 0,
)

/**
 * 插件声明的定时 hook。
 */
data class PluginScheduledHook(
    val pluginId: String,
    val handler: String,
    val schedule: String,
)
 
/**
 * 插件加载器。
 *
 * 除原有 tool/hook 调用外，这里同时承担插件运行时的最小生命周期、统一事件分发、
 * 动态提示词提供和定时 hook 元数据暴露。旧版插件无需修改即可继续工作。
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
        const val PERMISSION_PROMPT_INJECT = "prompt_inject"
    }
 
    // 单线程调度器，确保所有 QuickJS 操作在同一线程执行
    private val pluginDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "plugin-quickjs").apply { isDaemon = true }
    }.asCoroutineDispatcher()
 
    // 已加载的插件缓存
    private val loadedPlugins = mutableMapOf<String, LoadedPlugin>()
 
    /**
     * 加载插件。
     *
     * 新版约定：若插件导出了 onLoad/onEnable，则宿主会自动调用；
     * 同时如果导出了 onEvent，还会收到 plugin.loaded/plugin.enabled 统一事件。
     */
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
 
            val loadedPlugin = LoadedPlugin(
                info = pluginInfo,
                sandbox = sandbox
            )
 
            val exportedNames = sandbox.getExportedFunctionNames()
            Log.i(TAG, "Plugin ${pluginInfo.manifest.id} exported functions: $exportedNames")
 
            pluginInfo.manifest.tools.forEach { tool ->
                if (!sandbox.hasFunction(tool.name)) {
                    Log.w(TAG, "Tool '${tool.name}' declared in manifest but not found in exports (available: $exportedNames)")
                } else {
                    Log.i(TAG, "Tool '${tool.name}' registered successfully")
                }
            }
 
            // 先放入运行时缓存，再执行生命周期回调。这样回调期间插件已处于可发现状态。
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
 
    /**
     * 卸载插件，并补齐 disable/unload 生命周期。
     */
    private suspend fun doUnloadPlugin(pluginId: String) {
        val plugin = loadedPlugins[pluginId] ?: return

        val disableContext = buildLifecycleContext(plugin, "plugin.disabled")
        invokeOptionalFunction(plugin, "onDisable", disableContext)
        dispatchGenericEvent(plugin, "plugin.disabled", disableContext)

        val unloadContext = buildLifecycleContext(plugin, "plugin.unloaded")
        invokeOptionalFunction(plugin, "onUnload", unloadContext)
        dispatchGenericEvent(plugin, "plugin.unloaded", unloadContext)

        loadedPlugins.remove(pluginId)
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
 
    /**
     * 调用插件工具。
     */
    suspend fun callTool(pluginId: String, toolName: String, params: JsonElement): Result<JsonElement> {
        return withContext(pluginDispatcher) {
            try {
                val plugin = loadedPlugins[pluginId]
                    ?: return@withContext Result.failure(IllegalStateException("Plugin not loaded: $pluginId"))
 
                if (!plugin.hasTool(toolName)) {
                    return@withContext Result.failure(IllegalArgumentException("Tool not found: $toolName"))
                }
 
                val result = plugin.sandbox.callFunction(toolName, params)
                Result.success(result)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call tool=$toolName in plugin=$pluginId", e)
                Result.failure(e)
            }
        }
    }
 
    /**
     * 触发插件事件。
     *
     * 兼容两种监听方式：
     * 1. 旧版 manifest.hooks：message_sent/message_received/daily_cron 等；
     * 2. 新版统一入口 exports.onEvent(event)，无需为每个事件单独声明 handler。
     *
     * onEvent 收到的事件格式：
     * { type, legacyType, timestamp, pluginId, data }
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

                // 如果旧版 hook 已经显式把 onEvent 作为 handler，则不重复调用。
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
     * 每次上游模型请求前调用插件的 providePrompt(ctx)。
     *
     * 为避免任意插件静默修改系统提示词，动态提示词需要 manifest.permissions
     * 显式包含 prompt_inject。providePrompt 可返回：
     * - 字符串；
     * - { "text": "...", "priority": 10 }。
     */
    suspend fun getDynamicPromptInjections(): List<PluginPromptInjection> = withContext(pluginDispatcher) {
        loadedPlugins.values
            .asSequence()
            .filter { it.info.isEnabled }
            .filter { PERMISSION_PROMPT_INJECT in it.info.manifest.permissions }
            .filter { it.sandbox.hasFunction("providePrompt") }
            .mapNotNull { plugin ->
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
                    return@mapNotNull null
                }

                parsePromptInjection(plugin.id, result)
            }
            .sortedByDescending { it.priority }
            .toList()
    }

    /**
     * 读取所有 daily_cron hook 及各自 schedule。
     * schedule 为空时沿用旧行为：每天 03:00。
     */
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
 
    /**
     * 旧接口保留给现有 DailySummaryService 使用。
     */
    fun getPluginsWithDailyCron(): List<Pair<LoadedPlugin, String>> {
        return loadedPlugins.values.filter { it.info.isEnabled }.flatMap { plugin ->
            plugin.info.manifest.hooks
                .filter { it.event == "daily_cron" }
                .map { hook -> plugin to hook.handler }
        }
    }

    private suspend fun invokeOptionalFunction(
        plugin: LoadedPlugin,
        functionName: String,
        params: JsonElement,
        logLabel: String = functionName,
    ) {
        if (!plugin.sandbox.hasFunction(functionName)) return

        try {
            val completed = withTimeoutOrNull(HOOK_TIMEOUT_MS) {
                plugin.sandbox.callFunction(functionName, params)
            }
            if (completed == null) {
                Log.w(
                    TAG,
                    "Plugin callback timed out after ${HOOK_TIMEOUT_MS}ms: " +
                        "plugin=${plugin.id}, function='$functionName', $logLabel"
                )
            } else {
                Log.d(TAG, "Plugin callback completed: plugin=${plugin.id}, function=$functionName, $logLabel")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Plugin callback failed: plugin=${plugin.id}, function=$functionName, $logLabel", e)
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