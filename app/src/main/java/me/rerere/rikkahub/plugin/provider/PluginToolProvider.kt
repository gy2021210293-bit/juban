/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.plugin.loader.LoadedPlugin
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginToolDefinition

/**
 * 插件工具提供者。
 * 将插件工具和插件提供的上下文接入模型请求。
 */
class PluginToolProvider(
    private val pluginLoader: PluginLoader,
    private val pluginManager: PluginManager
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 获取所有插件提供的工具。
     * 会等待插件初始化完成，确保竞态条件下不会返回空列表。
     */
    suspend fun getTools(): List<Tool> {
        pluginManager.awaitInitialization()
        return pluginLoader.getAllLoadedPlugins().flatMap { plugin ->
            plugin.info.manifest.tools.map { toolDef ->
                createTool(plugin, toolDef)
            }
        }
    }

    /**
     * 获取指定插件的工具。
     */
    suspend fun getPluginTools(pluginId: String): List<Tool> {
        pluginManager.awaitInitialization()
        val plugin = pluginLoader.getLoadedPlugin(pluginId) ?: return emptyList()
        return plugin.info.manifest.tools.map { toolDef ->
            createTool(plugin, toolDef)
        }
    }

    /**
     * 创建 Tool 对象。
     */
    private fun createTool(plugin: LoadedPlugin, toolDef: PluginToolDefinition): Tool {
        return Tool(
            name = ToolNaming.buildPluginToolName(plugin.id, toolDef.name),
            description = buildDescription(plugin, toolDef),
            needsApproval = true,
            parameters = {
                InputSchema.Obj(
                    properties = buildParameters(toolDef),
                    required = toolDef.parameters.filter { it.required }.map { it.name }
                )
            },
            execute = { params ->
                executeTool(plugin, toolDef, params)
            }
        )
    }

    private fun buildDescription(plugin: LoadedPlugin, toolDef: PluginToolDefinition): String {
        val sb = StringBuilder()
        sb.appendLine(toolDef.description)
        sb.appendLine()
        sb.appendLine("Provided by plugin: ${plugin.info.manifest.name} (${plugin.info.manifest.id})")
        return sb.toString().trim()
    }

    private fun buildParameters(toolDef: PluginToolDefinition): JsonObject {
        return buildJsonObject {
            toolDef.parameters.forEach { param ->
                put(param.name, buildJsonObject {
                    put("type", param.type)
                    if (param.description != null) {
                        put("description", param.description)
                    }
                    when (param.type) {
                        "array" -> {
                            put("items", buildJsonObject {
                                put("type", "string")
                            })
                        }
                        "object" -> Unit
                    }
                })
            }
        }
    }

    private suspend fun executeTool(
        plugin: LoadedPlugin,
        toolDef: PluginToolDefinition,
        params: JsonElement
    ): List<UIMessagePart> {
        val result = pluginLoader.callTool(
            pluginId = plugin.id,
            toolName = toolDef.name,
            params = params
        )

        return result.fold(
            onSuccess = { jsonElement ->
                val resultStr = json.encodeToString(JsonElement.serializer(), jsonElement)
                listOf(UIMessagePart.Text(resultStr))
            },
            onFailure = { error ->
                val errorObj = buildJsonObject {
                    put("success", false)
                    put("error", error.message ?: "Unknown error")
                }
                listOf(UIMessagePart.Text(errorObj.toString()))
            }
        )
    }

    /**
     * 获取插件提示词注入。
     *
     * 兼容三层来源：
     * 1. 已加载插件工具的能力总览；
     * 2. 旧版 manifest.promptTemplate + inject_as_prompt；
     * 3. 新版 exports.providePrompt(ctx) 动态上下文。
     *
     * 动态上下文会在每次模型请求前重新计算，所以插件可以把实时状态、角色状态、
     * 经营/养成进度等内容直接带入上游 system prompt，而不是只能依赖工具被动查询。
     */
    suspend fun getPluginPromptInjections(): List<String> {
        pluginManager.awaitInitialization()

        val pluginsWithTools = pluginLoader.getAllLoadedPlugins()
            .filter { it.info.manifest.tools.isNotEmpty() }

        val overview = if (pluginsWithTools.isNotEmpty()) {
            val pluginNames = pluginsWithTools.joinToString("、") { it.info.manifest.name }
            "你当前装载了以下插件提供的工具（完整工具列表和参数见 tools 定义）：${pluginNames}。" +
                "不要只在用户明确点名某个工具时才使用——只要对话场景与某个工具的用途相关，就应该主动考虑调用它，而不是被动等待用户指示。" +
                "部分工具绑定的是持续性的人设/系统状态（例如经营、社交、记录类），更需要你自己记得在合适的时机调用，而不是等用户提醒。"
        } else {
            null
        }

        val manualTemplates = pluginLoader.getAllLoadedPlugins().mapNotNull { plugin ->
            val manifest = plugin.info.manifest
            val promptTemplate = manifest.promptTemplate ?: return@mapNotNull null
            val injectConfig = plugin.info.config["inject_as_prompt"]
            val shouldInject = when (injectConfig) {
                is kotlinx.serialization.json.JsonPrimitive -> injectConfig.content == "true"
                else -> false
            }
            if (shouldInject) promptTemplate else null
        }

        val dynamicTemplates = pluginLoader.getDynamicPromptInjections().map { injection ->
            val plugin = pluginLoader.getLoadedPlugin(injection.pluginId)
            val pluginName = plugin?.name ?: injection.pluginId
            buildString {
                appendLine("<plugin-context id=\"${injection.pluginId}\" name=\"$pluginName\">")
                appendLine(injection.text)
                append("</plugin-context>")
            }
        }

        return buildList {
            if (overview != null) add(overview)
            addAll(manualTemplates)
            addAll(dynamicTemplates)
        }
    }

    fun getToolStats(): ToolStats {
        val plugins = pluginLoader.getAllLoadedPlugins()
        val totalTools = plugins.sumOf { it.info.manifest.tools.size }

        return ToolStats(
            totalPlugins = plugins.size,
            totalTools = totalTools,
            pluginDetails = plugins.map { plugin ->
                PluginToolDetail(
                    pluginId = plugin.id,
                    pluginName = plugin.info.manifest.name,
                    toolCount = plugin.info.manifest.tools.size,
                    toolNames = plugin.info.manifest.tools.map { it.name }
                )
            }
        )
    }

    data class ToolStats(
        val totalPlugins: Int,
        val totalTools: Int,
        val pluginDetails: List<PluginToolDetail>
    )

    data class PluginToolDetail(
        val pluginId: String,
        val pluginName: String,
        val toolCount: Int,
        val toolNames: List<String>
    )
}