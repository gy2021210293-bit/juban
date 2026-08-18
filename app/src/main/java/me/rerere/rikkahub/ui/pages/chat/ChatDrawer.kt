/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.ComponentActivity
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.ai.AssistantPicker
import me.rerere.rikkahub.ui.components.ui.BackupReminderCard
import me.rerere.rikkahub.ui.components.ui.Greeting
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.UpdateCard
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.rememberIsPlayStoreVersion
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.pages.miniapp.MiniAppSessionCache
import me.rerere.rikkahub.ui.pages.miniapp.OpenedMiniApp
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.toDp
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun ChatDrawerContent(
    navController: Navigator,
    vm: ChatVM,
    settings: Settings,
    current: Conversation,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isPlayStore = rememberIsPlayStoreVersion()
    val repo = koinInject<ConversationRepository>()

    val activity = context as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)

    val conversations = drawerVm.conversations.collectAsLazyPagingItems()
    val openedMiniApps by MiniAppSessionCache.openedApps.collectAsStateWithLifecycle()
    val conversationListState = rememberLazyListState(
        initialFirstVisibleItemIndex = drawerVm.scrollIndex,
        initialFirstVisibleItemScrollOffset = drawerVm.scrollOffset,
    )

    LaunchedEffect(conversationListState) {
        snapshotFlow {
            conversationListState.firstVisibleItemIndex to
                conversationListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                drawerVm.saveScrollPosition(index, offset)
            }
    }

    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )

    // 昵称编辑状态
    val nicknameEditState = useEditState<String> { newNickname ->
        vm.updateSettings(
            settings.copy(
                displaySetting = settings.displaySetting.copy(
                    userNickname = newNickname
                )
            )
        )
    }

    // 移动对话状态
    var showMoveToAssistantSheet by remember { mutableStateOf(false) }
    var conversationToMove by remember { mutableStateOf<Conversation?>(null) }
    val bottomSheetState = rememberModalBottomSheetState()

    // Menu popup 状态
    var showMenuPopup by remember { mutableStateOf(false) }

    ModalDrawerSheet(
        modifier = Modifier.width(300.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 侧边栏背景图（最底层）
            val drawerBgPath = settings.displaySetting.drawerBackgroundPath
            if (drawerBgPath.isNotEmpty()) {
                val bgFile = java.io.File(drawerBgPath)
                if (bgFile.exists()) {
                    val bgBitmap = remember(drawerBgPath) {
                        BitmapFactory.decodeFile(drawerBgPath)
                    }
                    if (bgBitmap != null) {
                        Image(
                            bitmap = bgBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(0.15f),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            if (settings.displaySetting.showUpdates && !isPlayStore) {
                UpdateCard(vm)
            }

            BackupReminderCard(
                settings = settings,
                onClick = { navController.navigate(Screen.Backup) },
            )

            // 用户头像和昵称自定义区域
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                UIAvatar(
                    name = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                    value = settings.displaySetting.userAvatar,
                    onUpdate = { newAvatar ->
                        vm.updateSettings(
                            settings.copy(
                                displaySetting = settings.displaySetting.copy(
                                    userAvatar = newAvatar
                                )
                            )
                        )
                    },
                    modifier = Modifier.size(50.dp),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                nicknameEditState.open(settings.displaySetting.userNickname)
                            }
                        )

                        Icon(
                            imageVector = HugeIcons.PencilEdit01,
                            contentDescription = "Edit",
                            modifier = Modifier
                                .onClick {
                                    nicknameEditState.open(settings.displaySetting.userNickname)
                                }
                                .size(LocalTextStyle.current.fontSize.toDp())
                        )
                    }
                    Greeting(
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            DrawerActions(
                navController = navController,
                drawerItemAlpha = settings.displaySetting.drawerItemAlpha,
            )

            OpenedMiniApps(
                miniApps = openedMiniApps,
                navController = navController,
                drawerItemAlpha = settings.displaySetting.drawerItemAlpha,
            )

            ConversationList(
                current = current,
                conversations = conversations,
                conversationJobs = conversationJobs.keys,
                listState = conversationListState,
                drawerItemAlpha = settings.displaySetting.drawerItemAlpha,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onClick = {
                    navigateToChatPage(navController, it.id)
                },
                onRegenerateTitle = {
                    vm.generateTitle(it, true)
                },
                onDelete = {
                    vm.deleteConversation(it)
                    // Refresh the conversation list to immediately remove the deleted item
                    // This fixes the issue where deleted conversations sometimes remain visible
                    // until manually clicked (issue #747)
                    conversations.refresh()
                    if (it.id == current.id) {
                        navigateToChatPage(navController)
                    }
                },
                onPin = {
                    vm.updatePinnedStatus(it)
                },
                onMoveToAssistant = {
                    conversationToMove = it
                    showMoveToAssistantSheet = true
                }
            )

            // 助手选择器
            AssistantPicker(
                settings = settings,
                onUpdateSettings = {
                    vm.updateSettings(it)
                    scope.launch {
                        val id = if (context.readBooleanPreference("create_new_conversation_on_start", true)) {
                            Uuid.random()
                        } else {
                            repo.getConversationsOfAssistant(it.assistantId)
                                .first()
                                .firstOrNull()
                                ?.id ?: Uuid.random()
                        }
                        navigateToChatPage(navigator = navController, chatId = id)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                onClickSetting = {
                    val currentAssistantId = settings.assistantId
                    navController.navigate(Screen.AssistantDetail(id = currentAssistantId.toString()))
                }
            )

            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .alpha(settings.displaySetting.drawerItemAlpha)
            ) {
                DrawerAction(
                    icon = {
                        Icon(
                            imageVector = HugeIcons.LookTop,
                            contentDescription = stringResource(R.string.assistant_page_title)
                        )
                    },
                    label = {
                        Text(stringResource(R.string.assistant_page_title))
                    },
                    onClick = {
                        navController.navigate(Screen.Assistant)
                    },
                )

                Box {
                    DrawerAction(
                        icon = {
                            Icon(HugeIcons.Sparkles, "Menu")
                        },
                        label = {
                            Text(stringResource(R.string.menu))
                        },
                        onClick = {
                            showMenuPopup = true
                        },
                    )
                    DropdownMenu(
                        expanded = showMenuPopup,
                        onDismissRequest = { showMenuPopup = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_page_menu_ai_translator)) },
                            leadingIcon = { Icon(HugeIcons.LanguageCircle, null) },
                            onClick = {
                                showMenuPopup = false
                                navController.navigate(Screen.Translator)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("健康数据") },
                            leadingIcon = { Icon(HugeIcons.Zap, null) },
                            onClick = {
                                showMenuPopup = false
                                navController.navigate(Screen.Health)
                            }
                        )
                    }
                }

                DrawerAction(
                    icon = {
                        Icon(HugeIcons.InLove, stringResource(R.string.favorite_page_title))
                    },
                    label = {
                        Text(stringResource(R.string.favorite_page_title))
                    },
                    onClick = {
                        navController.navigate(Screen.Favorite)
                    },
                )

                DrawerAction(
                    icon = {
                        Icon(HugeIcons.ChartColumn, "统计数据")
                    },
                    label = {
                        Text("统计数据")
                    },
                    onClick = {
                        navController.navigate(Screen.Stats)
                    },
                )

                Spacer(Modifier.weight(1f))

                DrawerAction(
                    icon = {
                        Icon(HugeIcons.Settings03, null)
                    },
                    label = { Text(stringResource(R.string.settings)) },
                    onClick = {
                        navController.navigate(Screen.Setting)
                    },
                )
            }
        }
        }
    }

    // 昵称编辑对话框
    nicknameEditState.EditStateContent { nickname, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                nicknameEditState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_nickname))
            },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_nickname_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到助手 Bottom Sheet
    if (showMoveToAssistantSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToAssistantSheet = false
                conversationToMove = null
            },
            sheetState = bottomSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_assistant),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(settings.assistants) { assistant ->
                        AssistantItem(
                            assistant = assistant,
                            isCurrentAssistant = assistant.id == conversationToMove?.assistantId,
                            onClick = {
                                conversationToMove?.let { conversation ->
                                    vm.moveConversationToAssistant(conversation, assistant.id)
                                    scope.launch {
                                        bottomSheetState.hide()
                                        showMoveToAssistantSheet = false
                                        conversationToMove = null
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerActions(
    navController: Navigator,
    drawerItemAlpha: Float = 1f,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "常用",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        DrawerNavigationItem(
            icon = HugeIcons.Rocket01,
            label = "Mini Apps",
            drawerItemAlpha = drawerItemAlpha,
            onClick = { navController.navigate(Screen.MiniAppManager) },
        )
        DrawerNavigationItem(
            icon = HugeIcons.Image02,
            label = stringResource(R.string.chat_page_menu_image_generation),
            drawerItemAlpha = drawerItemAlpha,
            onClick = { navController.navigate(Screen.ImageGen) },
        )
        DrawerNavigationItem(
            icon = HugeIcons.McpServer,
            label = "MCP 管理",
            drawerItemAlpha = drawerItemAlpha,
            onClick = { navController.navigate(Screen.SettingMcp) },
        )
        DrawerNavigationItem(
            icon = HugeIcons.Zap,
            label = "工作流",
            drawerItemAlpha = drawerItemAlpha,
            onClick = { navController.navigate(Screen.Workflows) },
        )
        DrawerNavigationItem(
            icon = HugeIcons.ServerStack01,
            label = "工作区",
            drawerItemAlpha = drawerItemAlpha,
            onClick = { navController.navigate(Screen.Workspaces) },
        )
        DrawerNavigationItem(
            icon = HugeIcons.Search01,
            label = stringResource(R.string.chat_page_search_chats),
            drawerItemAlpha = drawerItemAlpha,
            onClick = { navController.navigate(Screen.MessageSearch) },
        )
    }
}

@Composable
private fun OpenedMiniApps(
    miniApps: List<OpenedMiniApp>,
    navController: Navigator,
    drawerItemAlpha: Float,
) {
    if (miniApps.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "已打开",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        miniApps.forEach { miniApp ->
            DrawerNavigationItem(
                icon = HugeIcons.Rocket01,
                label = miniApp.title.ifBlank { "未命名 Mini App" },
                drawerItemAlpha = drawerItemAlpha,
                onClick = {
                    navController.navigate(
                        Screen.MiniApp(
                            url = miniApp.url,
                            title = miniApp.title,
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun DrawerNavigationItem(
    icon: ImageVector,
    label: String,
    drawerItemAlpha: Float,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = drawerItemAlpha),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DrawerAction(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Tooltip(
            tooltip = {
                label()
            }
        ) {
            Box(
                modifier = Modifier
                    .padding(10.dp)
                    .size(20.dp),
            ) {
                icon()
            }
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    isCurrentAssistant: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isCurrentAssistant) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (isCurrentAssistant) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UIAvatar(
                name = assistant.name,
                value = assistant.avatar,
                onUpdate = {},
                modifier = Modifier.size(40.dp),
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrentAssistant) {
                    Text(
                        text = stringResource(R.string.assistant_page_current_assistant),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
