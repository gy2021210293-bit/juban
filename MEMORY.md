# orangechat Project Memory

## Project

- Name: orangechat
- Initialized: 2026-07-26
- Source: https://github.com/sue1231513/orangechat
- Technology stack: Android/Kotlin, Jetpack Compose, Koin, Room, kotlinx.serialization, Gradle 9.4.1; multi-module
  project with AI, search, speech, web, workspace, and QuickJS plugin support.

## Durable Decisions

- Inspect and document the existing architecture before implementing requested behavior.
- Use the Karpathy Guidelines skill for every code change.
- Keep credentials out of this file; record only where they are configured.
- AI image generation is an assistant-scoped, opt-in `generate_image` tool. It creates a separate persistent chat card,
  continues through WorkManager across process death, and sends only the card description—not the generated image,
  generation prompt, or style prompt—to later upstream chat requests.
- Each assistant owns an optional image-generation system prompt displayed directly below the tool toggle. A queued job
  snapshots it and prepends it to the AI-authored image prompt with a blank-line separator.
- Generated-image cards must be converted to their description before all input transformers run; OCR must also ignore
  every `generated_image=true` local file. This prevents later chat turns from uploading a generated image to the OCR
  model before the provider-bound description replacement occurs.
- Proactive-message access to the complete tool/plugin surface is explicit, scoped, and disabled by default. When
  enabled it reuses `ToolSurfaceBuilder`; approval-required tools are pre-authorized only by this scoped permission or
  the existing global auto-approval setting.
- Workflows may define an optional backward-compatible `ai_wake` block. It dispatches the authoring assistant only
  after every action succeeds, can include bounded action output, and separately controls full tool/plugin access.
- Workflow AI wake dispatch is asynchronous: a successful workflow run means the foreground-service wake was accepted,
  not that the later model generation itself completed.
- User correction: workflow wake must prioritize and execute `ai_wake.prompt`; it must not inherit a broad “decide
  whether to act” escape hatch. The complete workflow request is placed in the final user message, and silence is
  allowed only when the custom requirement explicitly permits it.
- Pat interactions use target-owned QQ-style suffixes: the user has one global suffix, while each assistant has its own
  suffix. Double-tapping an assistant avatar persists a metadata-tagged interaction and starts a normal AI response;
  the optional per-assistant `pat_user` local tool is disabled by default and cannot override the user's suffix.
- Pat events reuse `UIMessagePart.Text` metadata and executed tool output, with dedicated centered rendering. Do not add
  a new provider-facing message-part subtype for this UI-only distinction.
- The main chat top bar exposes one plugin quick-entry icon. Its menu lists only enabled, successfully loaded plugins
  with a supported UI, then routes directly using the established precedence: declarative UI, WebView, or the supported
  built-in `memory_bank` page. Keep plugin management as the final menu item.

## Current Goal

- Source checked out locally from `master` at `d1aad52f4e3aae0857b8f05ef46769a6de53c7d0`.
- Proactive-message full tool/plugin authorization and customizable workflow AI wake are implemented.
- QQ-style pat interaction, target-owned suffix settings, avatar double-tap, and the `pat_user` assistant tool are
  implemented.
- The main-chat plugin quick-entry menu is implemented with direct navigation and an empty-state message.
- Remaining validation boundary: exercise background launch, configured models, MCP tools, and installed plugins on an
  Android device/emulator; also verify avatar double-tap, AI-triggered pat rendering, and plugin quick-entry navigation
  on-device.

## Pitfalls and External Resources

- The machine-wide Gradle init script conflicts with repositories mode, so verification uses the ignored
  `.gradle/isolated-user-home` workspace directory.
- Android SDK components used for verification: platform 37, Build Tools 36.0.0, and NDK 28.2.13676358.
- `material3/material-color-utilities` is the gitlink submodule in the checked-out commit and is initialized at
  `6fd88eb3e95ba1d457842e2a2bf847d06b3a018a`. The MNN entry is present only in `.gitmodules`, with no gitlink in this
  commit.
- Targeted JVM verification excludes `:web:buildWebUi` because npm dependency access reset during the frontend build.
  Kotlin compilation succeeded, and six new tests covering authorization, workflow JSON, and action-output handoff
  passed on 2026-07-27.
- A complete Debug build including the Web UI succeeded on 2026-07-27. APK outputs are under
  `app/build/outputs/apk/debug/`; arm64-v8a, x86_64, and universal variants were generated and verified with APK
  Signature Scheme v2.
- The repository expects the ignored local Debug signing file at `app/debug.keystore`; it was generated locally for
  packaging. Keep only its location in project memory.
- Some models respond with bare `SKIP` instead of `[PASS]`. Workflow prompting now explicitly rejects that substitution,
  while the output filter treats standalone `SKIP`/`[SKIP]` as silent so it is never surfaced as a user message.
- Root cause of “AI sees a system time reminder instead of workflow wake”: `TimeReminderTransformer` can prepend a
  separate user message, but `ProactiveMessageTriggerService` previously kept only `.first()`, discarding the actual
  workflow instruction. Preserve every transformed input message; the workflow request remains the final user message.
- Pat interaction verification on 2026-07-28: four targeted JVM tests passed, Kotlin compilation and the Debug build
  succeeded. The full app JVM suite still has eight unrelated existing failures (seven
  `TimeReminderTransformerTest` assertions and one `ShareSheetTest` OpenAI-provider assertion).
- Plugin quick-entry verification on 2026-07-28: four targeted JVM tests passed together with the four pat tests, and
  main/test Kotlin compilation succeeded via Gradle's fallback compiler after a Kotlin daemon temp-file permission
  warning. No APK packaging task was run for this follow-up.
- A fresh complete Debug build containing the pat interaction and plugin quick-entry changes succeeded on 2026-07-28.
  It produced arm64-v8a, x86_64, and universal APKs for `me.rerere.orangechat.debug` version 2.2.3 (159); all three
  passed APK Signature Scheme v2 verification. Use the arm64-v8a APK for ordinary Android phones.
- User device feedback on 2026-07-28 exposed a pat interaction bug not covered by JVM tests: read-only `UIAvatar`
  always uses the clickable `Surface` overload, so a custom assistant avatar consumes taps before the surrounding
  `combinedClickable` can recognize a double tap. The pat settings are also hard to discover because assistant-target
  suffix, user-target suffix, and the optional AI tool switch live on three separate pages.
- The custom-avatar double-tap bug was repaired on 2026-07-28 by disabling `UIAvatar`'s internal clickable surface when
  the avatar has no click/edit action, allowing the parent double-tap handler to receive events. Both model and custom
  assistant avatars retain the same handler, and double-tapping while AI generation is active now shows an explicit
  wait message instead of silently doing nothing. The pat message format and settings UI were intentionally unchanged.
- The repaired Debug APKs were rebuilt on 2026-07-28 at 12:49:41 and all passed v2 signature verification. The arm64
  APK SHA-256 is `B7861B3E4DDAA5386069CA7FE3192D658E38DA7DD47E978A1F84F9F7285764BE`; live gesture
  confirmation remains device-side because no ADB device was connected during verification.
- User reported that the 12:49 avatar fix still did not react. The ineffective `UIAvatar`-surface workaround was
  removed and replaced with a dedicated 40 dp transparent double-tap target rendered last above both model and custom
  assistant message avatars, so avatar internals and frame overlays cannot win hit testing. APKs rebuilt at 13:03:33;
  arm64 SHA-256 is `F2A499387040A0BD3F5663F1EE5E655BE39E73EB739B1C573650DF0CD7C3E69A`.
- Confirmed product decision on 2026-07-28: the avatar-interaction action and content are independently customizable.
  User-to-assistant defaults are stored per assistant. For assistant-to-user interactions, the AI may pass optional
  `action` and `content` arguments to `pat_user`; each omitted argument falls back independently to the user's configured
  default. Keep the double-tap trigger, `pat_user` internal tool name, and `orangechat_event=pat` metadata for backward
  compatibility.
- Custom-action verification on 2026-07-28: all five targeted `PatInteractionTest` cases passed, including AI-provided
  action/content and configured-default fallback. The complete Debug APK build succeeded; arm64-v8a, x86_64, and
  universal outputs all passed APK Signature Scheme v2 verification. The arm64 APK was built at 13:22:01 and has
  SHA-256 `98D1D3C4C19A6ECA838FE83D90FC187632B5EB97A5364B83CC6B98CE649A3151`. Live gesture confirmation remains
  device-side.
- Superseded proposal, resolved by the confirmed decision above: customize the visible avatar-interaction action (for example,
  `捏一捏` or `戳一戳`) while retaining the existing double-tap trigger, suffix ownership, `pat_user` internal tool name,
  and pat-event metadata for backward compatibility.
- Plugin quick-entry visibility is now a per-plugin preference independent of plugin enablement. Only plugins with a
  supported UI expose the setting. Existing installations initialize the preference from each plugin's current enabled
  state, then preserve it across later enable/disable changes. A disabled plugin selected for quick entry remains
  user-openable but stays unloaded from AI tools and background hooks; its menu row is marked as disabled.
- Per-plugin quick-entry preferences are included in plugin settings export/import and removed with the plugin.
  Verification on 2026-07-28: five targeted `PluginQuickEntryTest` cases passed and the complete Debug APK build
  succeeded. All three APK variants passed v2 signature verification; the arm64 APK was built at 14:31:51 with SHA-256
  `5F6838BB96C8505D1C824864BA22C57315B87B5B65C83BC84EB79E8FCAFA9CB9`.
- Device crash diagnosis on 2026-07-29: `FloatingBubbleService` renders assistant avatars through a platform
  `ImageView` in an overlay window. That window can use a software canvas, so its Coil request must keep
  `allowHardware(false)` to avoid drawing a hardware bitmap into software rendering. This restriction is intentionally
  scoped to the floating-bubble avatar request rather than the global image loader.
- Floating-bubble avatar presentation decision on 2026-07-29: never show the sender name or its initial inside the
  bubble. Render configured image and emoji avatars directly; for `Avatar.Dummy` or an image-load failure, retain only
  the circular background without adding fallback text.
- Dynamic environment context architecture added on 2026-07-29: `DynamicContextProvider` is the single formatter and
  privacy boundary shared by normal generation, proactive messages, device-event wakes, workflow wakes, and the
  settings preview. The global feature defaults off; category switches default on for backward-compatible settings.
- `DynamicContextMonitor` is process-local and retains only the latest change per category. It starts only while the
  master switch is enabled and clears its change state when stopped or when the process restarts.
- Automatic notification context uses the notification listener's currently active set rather than an age window.
  It allows ordinary message notifications but emits only app name, title, and original time; OTP-like and
  finance-related items are filtered, with five-item limits. Location is capped at city/district/street and expires
  after 30 minutes without coordinate fallback. Calendar context covers unfinished events in the next 24 hours,
  sorted and capped at five.
- Dynamic context verification on 2026-07-29: four targeted JVM tests passed, `:app:compileDebugKotlin` succeeded, and
  no APK packaging task was run. Live permission flows, device broadcasts, media sessions, active notifications,
  reverse geocoding, and calendar-provider results remain device-side validation boundaries.
- A complete Debug build containing the dynamic environment context changes succeeded on 2026-07-29. The generated
  arm64-v8a, x86_64, and universal APKs are package `me.rerere.orangechat.debug`, version 2.2.3 (159), and all passed APK
  Signature Scheme v2 verification. The arm64-v8a APK built at 16:49:22 has SHA-256
  `84A7E01BB096644A12F483C5A47FE546E3448B6CDAE768A4CA55D129437B6312`.
- Dynamic context prompt ordering was revised on 2026-07-29 to preserve provider prefix caching: stable interpretation
  and safety rules remain in the system prompt, while each generated snapshot is an ephemeral USER message inserted
  after retained history and immediately before the current real USER request. It is inserted after input transforms,
  is never persisted or rendered, and the same snapshot is reused during a generation's tool loop. Proactive paths
  preserve the same logical order but may merge adjacent USER parts at the provider boundary for APIs that require
  alternating roles.
- Gateway recognition contract confirmed on 2026-07-29: every dynamic snapshot message, including normal chat,
  proactive, device-event, and workflow paths, carries message-level metadata
  `{"dynamic_environment":true,"generated_at":"<the snapshot's original generated_at>"}`. The gateway must recognize
  snapshots only through `metadata.dynamic_environment === true`, never from text or message position. Real user
  messages do not carry this marker. OpenAI Chat Completions and Responses serializers both preserve the metadata.
- The post-metadata complete Debug build succeeded on 2026-07-29. All three APK variants passed v2 signature
  verification; the arm64-v8a APK built at 17:38:38 has SHA-256
  `9770F7631BA231284926CCBF0E0BBCB42C993E04362F5573BDF5ED8B5146A750`.
- Workflow/proactive tool-loop requests preserve the original USER wake request, but an OpenAI Chat Completions
  follow-up is serialized as `USER request -> ASSISTANT tool_calls -> one TOOL message per result`. Therefore the
  request can end in TOOL rather than USER. A gateway must locate the USER request preceding the trailing assistant/tool
  chain and retain that complete chain; stopping the reverse scan at the first TOOL, or accepting only tool-call IDs
  already known to the gateway database, can incorrectly reduce the client message set to zero for locally executed
  tools.
- Publishing on 2026-07-31: `origin` is now `gy2021210293-bit/juban`, while the original source remote is retained as
  `upstream`. This checkout began as a shallow `blob:none` partial clone, so a first push to an empty repository needed
  `git -c http.sslBackend=openssl fetch --unshallow --no-filter upstream`; Windows Schannel TLS fails here. GitHub
  accepted the push but warns that `_chk.zip` (82 MB) and `app/src/main/assets/runtime/python.tar.gz` (74 MB) exceed
  its recommended 50 MB threshold; consider Git LFS before those files grow further.
