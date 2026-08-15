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
 * 在原有 tool/hook 能力之上补充最小生命周期、统一事件入口和动态提示词能力。
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

    private val pluginDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "plugin-quickjs").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val loadedPlugins = mutableMapOf<String, LoadedPlugin>()

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
                Log.w(TAG, "Plugin callback timed out: plugin=${plugin.id}, function='$functionName', $logLabel")
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
