/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.PLUGIN_CRON_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.loader.PluginScheduledHook
import me.rerere.rikkahub.service.CronExpressionParser
import org.koin.core.context.GlobalContext
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * 插件定时任务调度服务。
 *
 * 继续沿用原 DailySummaryService 名称和 Android 组件，避免破坏旧配置；
 * 实际调度已升级为按每个插件 hook.schedule 计算下一次触发时间。
 * 宿主只维护一个 AlarmManager 闹钟，每次指向所有插件计划中的最近一次任务。
 */
class DailySummaryService {

    companion object {
        const val TAG = "DailySummaryService"
        const val ACTION_DAILY_CRON = "me.rerere.orangechat.DAILY_CRON"
        private const val REQUEST_CODE = 10003

        private const val PREFS_NAME = "daily_cron_prefs"
        private const val KEY_NEXT_TRIGGER_TIME = "next_trigger_time"
        private const val KEY_ENABLED = "enabled"

        const val EXTRA_PLUGIN_IDS = "plugin_ids"
        const val EXTRA_HANDLERS = "handlers"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"

        private data class Candidate(
            val hook: PluginScheduledHook,
            val triggerAtMillis: Long,
        )

        /**
         * 从当前已加载插件重新计算整个调度表。
         */
        fun rescheduleIfEnabled(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                rescheduleNow(context)
            }
        }

        /**
         * 立即重新计算下一次插件任务。供 TriggerService 在执行完成后调用。
         */
        suspend fun rescheduleNow(context: Context) {
            try {
                val pluginLoader = GlobalContext.get().getOrNull<PluginLoader>()
                if (pluginLoader == null) {
                    Log.w(TAG, "PluginLoader not available, skipping plugin cron scheduling")
                    cancel(context)
                    return
                }

                val hooks = pluginLoader.getScheduledHooks()
                if (hooks.isEmpty()) {
                    cancel(context)
                    Log.i(TAG, "No scheduled plugin hooks, cron alarm cancelled")
                    return
                }

                val nextBatch = computeNextBatch(hooks)
                if (nextBatch == null) {
                    cancel(context)
                    Log.w(TAG, "No valid future plugin cron execution could be calculated")
                    return
                }

                scheduleBatch(
                    context = context,
                    triggerTime = nextBatch.first,
                    hooks = nextBatch.second,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule plugin cron", e)
            }
        }

        private fun computeNextBatch(
            hooks: List<PluginScheduledHook>,
            basis: ZonedDateTime = ZonedDateTime.now(),
        ): Pair<Long, List<PluginScheduledHook>>? {
            val candidates = hooks.mapNotNull { hook ->
                val cron = CronExpressionParser.parse(hook.schedule).getOrElse { error ->
                    Log.w(
                        TAG,
                        "Invalid plugin cron '${hook.schedule}' for ${hook.pluginId}.${hook.handler}: ${error.message}"
                    )
                    return@mapNotNull null
                }
                val next = CronExpressionParser.nextExecution(cron, basis)
                if (next == null) {
                    Log.w(TAG, "Plugin cron has no future execution: ${hook.pluginId}.${hook.handler}")
                    null
                } else {
                    Candidate(
                        hook = hook,
                        triggerAtMillis = next.toInstant().toEpochMilli(),
                    )
                }
            }

            if (candidates.isEmpty()) return null

            val earliest = candidates.minOf { it.triggerAtMillis }
            // 5-field cron 的精度为分钟；这里留 1 秒容差，把同一时刻的任务合并到一个闹钟。
            val dueHooks = candidates
                .filter { abs(it.triggerAtMillis - earliest) <= 1_000L }
                .map { it.hook }

            return earliest to dueHooks
        }

        private fun scheduleBatch(
            context: Context,
            triggerTime: Long,
            hooks: List<PluginScheduledHook>,
        ) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_NEXT_TRIGGER_TIME, triggerTime)
                .putBoolean(KEY_ENABLED, true)
                .apply()

            val pluginIds = ArrayList(hooks.map { it.pluginId })
            val handlers = ArrayList(hooks.map { it.handler })

            val intent = Intent(context, DailySummaryReceiver::class.java).apply {
                action = ACTION_DAILY_CRON
                putStringArrayListExtra(EXTRA_PLUGIN_IDS, pluginIds)
                putStringArrayListExtra(EXTRA_HANDLERS, handlers)
                putExtra(EXTRA_SCHEDULED_AT, triggerTime)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms() -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent,
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent,
                    )
                    Log.w(TAG, "Exact alarm permission not granted, using inexact plugin cron alarm")
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent,
                    )
                }
                else -> {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            }

            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            Log.i(
                TAG,
                "Scheduled ${hooks.size} plugin cron hook(s) at ${sdf.format(java.util.Date(triggerTime))}: " +
                    hooks.joinToString { "${it.pluginId}.${it.handler} [${it.schedule}]" }
            )
        }

        fun cancel(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_NEXT_TRIGGER_TIME)
                .putBoolean(KEY_ENABLED, false)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, DailySummaryReceiver::class.java).apply {
                action = ACTION_DAILY_CRON
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                alarmManager.cancel(it)
                it.cancel()
                Log.d(TAG, "Cancelled plugin cron alarm")
            }
        }

        fun getNextTriggerTime(context: Context): Long? {
            val triggerTime = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_NEXT_TRIGGER_TIME, 0L)
            return triggerTime.takeIf { it > 0L }
        }

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        }

        /**
         * 手动触发：不指定目标 hook 时，TriggerService 会执行全部已声明定时 hook。
         */
        fun triggerNow(context: Context) {
            val serviceIntent = Intent(context, DailySummaryTriggerService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}

class DailySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(DailySummaryService.TAG, "Plugin cron receiver triggered, action=${intent.action}")
        when (intent.action) {
            DailySummaryService.ACTION_DAILY_CRON -> {
                val serviceIntent = Intent(context, DailySummaryTriggerService::class.java).apply {
                    putStringArrayListExtra(
                        DailySummaryService.EXTRA_PLUGIN_IDS,
                        intent.getStringArrayListExtra(DailySummaryService.EXTRA_PLUGIN_IDS),
                    )
                    putStringArrayListExtra(
                        DailySummaryService.EXTRA_HANDLERS,
                        intent.getStringArrayListExtra(DailySummaryService.EXTRA_HANDLERS),
                    )
                    putExtra(
                        DailySummaryService.EXTRA_SCHEDULED_AT,
                        intent.getLongExtra(DailySummaryService.EXTRA_SCHEDULED_AT, 0L),
                    )
                }
                context.startForegroundService(serviceIntent)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(DailySummaryService.TAG, "Boot completed, rebuilding plugin cron schedule")
                DailySummaryService.rescheduleIfEnabled(context)
            }
        }
    }
}

/**
 * 插件定时任务执行服务。
 * Alarm 中携带本次真正到期的 pluginId/handler，只执行这些任务；完成后重新计算下一次计划。
 */
class DailySummaryTriggerService : Service() {

    companion object {
        private const val TAG = "DailySummaryTrigger"
        private const val NOTIFICATION_ID = 20003
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Plugin cron trigger service started")

        val notification = NotificationCompat.Builder(this, PLUGIN_CRON_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("正在执行插件定时任务...")
            .setSmallIcon(R.drawable.small_icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        val requestedPluginIds = intent
            ?.getStringArrayListExtra(DailySummaryService.EXTRA_PLUGIN_IDS)
            .orEmpty()
        val requestedHandlers = intent
            ?.getStringArrayListExtra(DailySummaryService.EXTRA_HANDLERS)
            .orEmpty()
        val scheduledAt = intent?.getLongExtra(DailySummaryService.EXTRA_SCHEDULED_AT, 0L) ?: 0L

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pluginLoader = GlobalContext.get().getOrNull<PluginLoader>()
                if (pluginLoader == null) {
                    Log.w(TAG, "PluginLoader not available, skipping plugin cron")
                    return@launch
                }

                val allHooks = pluginLoader.getScheduledHooks()
                val targetHooks = if (
                    requestedPluginIds.isNotEmpty() &&
                    requestedPluginIds.size == requestedHandlers.size
                ) {
                    requestedPluginIds.zip(requestedHandlers).mapNotNull { (pluginId, handler) ->
                        allHooks.firstOrNull {
                            it.pluginId == pluginId && it.handler == handler
                        }
                    }
                } else {
                    // 手动 triggerNow 或旧 PendingIntent：兼容执行全部定时 hook。
                    allHooks
                }

                if (targetHooks.isEmpty()) {
                    Log.i(TAG, "No matching plugin cron hooks to execute")
                    return@launch
                }

                val now = java.time.LocalDateTime.now()
                val eventData = JsonObject(
                    mapOf(
                        "timestamp" to JsonPrimitive(now.toString()),
                        "date" to JsonPrimitive(now.toLocalDate().toString()),
                        "hour" to JsonPrimitive(now.hour),
                        "minute" to JsonPrimitive(now.minute),
                        "scheduledAt" to JsonPrimitive(scheduledAt),
                    )
                )

                Log.i(TAG, "Dispatching ${targetHooks.size} scheduled plugin hook(s)")
                targetHooks.forEach { hook ->
                    pluginLoader.callScheduledHook(
                        pluginId = hook.pluginId,
                        handler = hook.handler,
                        params = eventData,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch plugin cron", e)
            } finally {
                try {
                    DailySummaryService.rescheduleNow(this@DailySummaryTriggerService)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to rebuild plugin cron schedule", e)
                }
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
