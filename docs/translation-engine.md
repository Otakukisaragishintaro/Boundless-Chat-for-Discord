# 检测、翻译协议与配置

## 自然语言检测

`EnglishDetector` 消费同次捕获的输入：去除精确 `<xN>` 标签、以空格屏蔽 `<keepN/>`，再过滤 URL、反引号代码及自定义 emoji。必须有可提取 TEXT、至少 4 个 Unicode 字母，字母占非空白字符至少 0.5。不因存在代码块或 opaque/TEXT 节点数量比例否决混合正文。

目标文字体系命中阈值为字母的 0.2；中文目标还要求没有假名/韩文音节，日文目标遇假名即跳过。拉丁字母语言等共享文字体系无法可靠区分，纯汉字日文、短文和混合语言也存在误判。此处是脚本筛选，不是完整语言识别模型。

宿主已解析的 Annotated 代码、提及、时间戳等节点仅提供位置标记，不读取内部内容。纯保留节点没有自然语言，不触发翻译；Primitive 沿用已有反引号过滤，不保证识别所有原始 Markdown 代码形式。

## 协议与网络

| `provider_protocol` | 基础地址后追加端点 | 响应方式 |
|---|---|---|
| `openai-compatible`（默认） | `/chat/completions` | JSON `choices.0.message.content` 或 SSE delta |
| `openai-responses` | `/responses` | JSON message/output_text 或 SSE response.output_text.delta |
| `anthropic-messages` | `/messages` | JSON text 块或 SSE；请求 `max_tokens=8192` |

请求按所选协议构造，使用系统 `org.json` 和 `HttpURLConnection`。配置校验解析一次额外请求头，实际请求复用并追加协议认证头。默认提示词按目标语言生成；自定义提示词不随目标语言改写。协议层去除普通提示词首尾空白后，始终附加包装边界规则；另有独立的“必要保护提示词”开关（默认开启），按当前目标语言生成 `<keepN/>` 保留规则。该规则在提示词管理器中单独折叠查看，不混入普通提示词编辑框；关闭开关只是不向模型发送这段说明，不关闭程序自身的标记完整性检查。

`<keepN/>` 是唯一的保留内容占位符。原生保留节点、Discord 提及、频道/自定义 emoji、URL、按设置启用的 Unicode emoji，以及需要原样保留的段落分隔符，都直接转换为递增的 `<keepN/>` 标记；不再经过第二套 API 占位符编码。模型收到自然语言与 `<keepN/>`，系统提示词明确要求逐个原样复制且保持顺序。响应先按原文校验标记数量、顺序及完整性，校验通过后才恢复原始内容，再执行结构校验和节点重建。普通尖括号文本、未闭合片段和颜文字不属于占位符语法。样式边界 `<xN>` 与 `</xN>` 也会转换成同一序列中的 `<keepN/>`，由统一校验和恢复流程处理。

每次请求只创建一个协议 decoder，不切换解析模式。`SseReader` 以空行提交完整事件，支持多行 data、event、注释和 CR/LF/CRLF。Compatible 必须收到 `[DONE]`，Responses 必须收到 `response.completed`，Anthropic 必须收到 `message_stop`；结束后不等待 EOF。缺结束事件、已识别的错误帧、异常停止原因、所读取文本字段类型错误会失败，不返回该请求已收集的部分译文。这些检查不构成完整响应 schema 校验。包装标签回显属于 `Segments` 译文防损坏校验：实际翻译及翻译测试拒绝，连接测试不执行该内容校验。

非流式 Chat 必须显式返回 `finish_reason:"stop"`，并拒绝非 null 的 `message.refusal`、`tool_calls` 和 `function_call`；Anthropic 必须返回字符串 `stop_reason:"end_turn"` 或 `"stop_sequence"`，只拼接 text 内容块，跳过非对象及非 text 项。

Chat 与 Anthropic SSE 必须收到各自结束事件，但不强制出现停止原因；原因缺失、null 或空字符串时不检查取值，非空时与 JSON 共用正常停止原因校验。Chat SSE 只提取 `delta.content`，当前不检查 `delta.refusal`、`tool_calls` 或 `function_call`，不能把结束事件当作这些字段均已校验的证明。

Responses 按 `output_index`、`content_index` 和 `item_id` 收集文本增量，校验递增事件序号，并逐段核对完成响应；按 output/content 顺序拼接全部 `message/output_text`，跳过 reasoning、工具等非 message output 项，拒绝 message 内容中的 refusal、流式 refusal 事件、failed、incomplete 和完成文本不一致。流式和非流式共用同一个完成文本提取操作，不用 `.done` 快照重复追加或补齐缺失增量。事件格式参见 [OpenAI 流式响应文档](https://developers.openai.com/api/docs/guides/streaming-responses)。

HTTP 累计响应最多 1 MiB 字节，decoder/SSE 累计最多 1 MiB UTF-16 单位，UTF-8 严格校验，禁止跟随重定向。非 2xx 读取有界 body 后按 HTTP 状态分类；正文保留给设置页测试结果，并在项目日志启用时原样记录。引擎在配置校验和构造请求体前登记 `Http.Request`，取消与连接 attach 原子协调，finally 才释放；实际 worker 是否退出由生命周期另行确认。

## 调度、缓存与错误

等待队列与状态仓库各有独立的 512 上限；翻译缓存为可选功能，默认关闭。启用后采用 512 条内存 LRU + 用户自定义容量（1–1,000,000 条）的 SQLite 持久化 LRU。建议设置为 100,000–1,000,000 条。并发实际限制为 1–8，默认 4；满队列明确失败。请求和交付都核对任务所有权，旧结果不得覆盖新消息/编辑版本。失败后只有用户点击才重新请求，无自动重试。

缓存启用时，消息绑定和发送前翻译先查本地缓存，命中后直接复用，不请求翻译 API；成功的入站译文和发送前译文都会保存，重复消息不受消息 ID 限制。关闭缓存时不读取、不写入缓存，沿用原有 API 请求流程；关闭不会删除旧条目，重新开启后仍可使用。设置页将缓存开关、容量输入、已保存数量及清空操作收纳在“本地翻译缓存”二级菜单；容量可在关闭缓存时编辑并保存，关闭期间只记录新上限，不打开数据库、不执行淘汰；重新开启缓存时再按新上限执行 LRU 清理。建议容量为 100,000–1,000,000 条。Discord 进程通过 remote preferences 同步已保存条目数，清空按钮发送独立命令让 Discord 进程清理数据库。持久化数据库位于 Discord 私有应用目录，退出/重启 Discord、重新登录及设备重启后仍保留；清除 Discord 应用数据或卸载 Discord 会删除它。数据库只保存内容配置哈希和译文，不保存原文、API 密钥或请求头。缓存读写失败时退回正常 API 流程，不阻断消息显示。

缓存键含输入及影响结果的配置版本；API URL、模型、目标语言、协议、流式、系统提示词、凭据指纹变化后旧结果不会命中，但不会因普通配置变更而立即清空。日志开关不影响请求版本、缓存或任务状态。密钥及额外请求头仅以 SHA-256 指纹参与版本编码，不以原值进入指纹字段。

错误类别为 `CONFIG / NETWORK / TIMEOUT / HTTP / RATE_LIMIT / PARSE / EMPTY / CANCELLED / UNKNOWN`。未知异常仅保留类型，服务端停止理由使用固定描述。请求校验失败不缓存；原生重建拒绝时删除对应缓存、标记失败并保留宿主原树。

## 按语言独立的提示词

`LanguagePromptConfig` 为 22 种受支持语言分别保存接收消息和发送消息提示词，两个方向互不共享。默认模板按目标语言生成；旧版单一提示词只迁移到旧配置当前选中的语言，不覆盖其他语言。提示词编辑器默认收起内容，用户点击显示开关后才展开；可逐语言恢复默认。发送按钮显式配置的自定义提示词仍是该按钮的优先覆盖，按钮恢复默认后才读取对应语言的发送提示词。必要的 `<keepN/>` 保护规则由 `Protocols.protectionPromptFor(targetLanguage)` 按当前目标语言独立生成；设置页将它与普通提示词分开显示，默认折叠查看，并提供默认开启的独立发送开关。该开关参与配置版本及跨进程配置同步。关闭时仍保留 `DiscordTokenMarkers.Protection.restore()` 和 `ContentNodes` 的强制标记完整性检查，不能静默丢失保护内容。

## 设置与持久化

| 键 | 默认值 | 含义 |
|---|---|---|
| `auto_translate_enabled` | true | 自动翻译开关 |
| `logging_enabled` | false | 项目日志开关，不参与翻译版本 |
| `translation_cache_enabled` | false | 本地翻译缓存开关；默认关闭，不参与翻译版本 |
| `translation_cache_limit` | 100000 | 持久化 LRU 上限，允许输入 1–1,000,000 的任意整数，不参与翻译版本 |
| `translation_scope` | 默认翻译、无规则 | 单一 JSON 对象：`defaultTranslate`、`servers`、`channels`；规则含字符串 `id/label` 和布尔 `translate` |
| `api_url` | `https://api.openai.com/v1` | 基础地址，自动追加协议端点 |
| `api_key` / `extra_headers` | 空 | 密钥及每行 `Name: Value` 的额外头 |
| `api_model` | `gpt-4o-mini` | 模型 |
| `target_lang` | `简体中文` | 目标语言；旧列表外值继续保留 |
| `provider_protocol` | `openai-compatible` | 上表三种协议之一 |
| `streaming_enabled` | true | 三种协议共用的流式请求开关 |
| `protection_prompt_enabled` | true | 单独发送按目标语言生成的 `<keepN/>` 必要保护提示词；关闭不影响程序强制完整性检查 |
| `system_prompt` | `Config.DEF_SYSTEM_PROMPT` | 规则式翻译提示词 |
| `timeout_ms` | 30000 | 正整数；HTTP 实际至少 1000 ms |
| `max_concurrency` | 4 | 正整数；调度器实际限制为 1–8 |

缺省键才使用默认值。显式错误类型、未知协议、必填空白或非正整数使快照无效；空密钥和空额外请求头有效。错误快照不能回填成可保存默认表单，只有用户确认重置才能删除已保存配置。

`ConfigurationSync` 是保存、重置、导入和导出的唯一策略。先持久化模块私有 SharedPreferences；本地尚未初始化时从 remote 导入，确认初始化后向 remote 导出。初始化标记随成功事务提交。`PreferenceCommit` 保存失败恢复原键快照并仍报告失败；恢复持久化也失败时本进程阻止读取/同步，直到显式重试成功，不自动更换权威来源。

设置页在既有持久化 executor 上读取和提交配置；包含 poison 检查的权威快照读取也在后台完成，页面重建不会在主线程等待另一个页面的提交锁。`SettingsPersistence` 统一处理读取及停止期间的延后交付；首次读取完成前禁用配置操作。页面弱订阅进程级服务连接，仅未保存草稿写入实例状态，异步完成不得覆盖已恢复草稿或后续编辑。Discord 不会因普通配置变更而实时重载翻译设置，**保存并成功同步后重启 Discord 生效**；清空缓存使用独立的一次性 remote-preferences 命令，由 Discord 进程即时处理。自动翻译、项目日志、流式请求与本地缓存统一使用原生 Switch；名称和描述反映当前草稿的开启/关闭状态，加载中或配置无效时显示对应状态，不将不可用占位值描述为已关闭。切换仅修改草稿，仍走原有保存/同步链；流式说明区分 SSE 与非流式 JSON，完整校验后才显示译文。

### 翻译范围

`TranslationScope` 是唯一规则模型：频道规则 > 服务器规则 > 默认策略。服务器/频道各最多 256 条规则；ID 只接受无前导零的正十进制 uint64，备注最多 80 UTF-16 单元且只用于显示。重复 ID、未知 JSON 键或错误类型使整份配置无效，不自动修复。未保存新键才默认全部翻译。

原生 `message.bind` 按 profile 捕获 `Message` 的频道 long 和可空 `GuildId` 包装对象（null 表示私信）。两版本 ID getter 的完整名称与返回类型均核实；`GuildId.toString` 返回十进制 ID。`HookBridge` 记录有界行位置；正文及 Embed/Poll 键均回到包含它的行，位置未知时拒绝请求和译文消费。全局开关继续阻止新请求；范围 gate 位于状态创建和缓存读取之前，重试及异步交付重新核对范围和所有权。匹配策略变化（默认动作、各分区 ID 或对应动作）清理状态、卡片及缓存、使旧代次失效；纯备注或规则顺序变化不影响匹配，保留译文、缓存及在途任务所有权。完整对象相等与序列化仍保留备注和顺序，范围不参与 API 请求版本。

设置页使用独立平铺规则卡片、默认策略及搜索/编辑弹窗，不使用树形三态列表。仅“应用到草稿”交给已有配置保存链；取消不保存，旋转/停止保留编辑器草稿和正在编辑的 ID/备注/动作，远程导入不得覆盖正在编辑的副本。添加规则只接受手动输入 ID；不采集最近目录、不持久化浏览位置、不 hook 真实名称。运行时位置仅保存在有界行映射中，用于统一规则判定。

### 设置页 API 测试与结果

三种测试使用当前表单快照，不要求先保存，也不要求 LSPosed 可用；草稿测试不会提交设置或启用日志；不受消息服务器/频道范围限制。离线检查复用 `JsonApiEngine.validateConfig`；两个网络测试通过唯一的 `ApiTester.newCall(cfg, TestKind, text)` 请求链路运行。固定探针只由 `ApiTester` 选择，设置页传入的测试文本不影响连接测试。页面停止时主动断开其 HTTP 请求并取消 Future，迟到结果不更新页面。

| 测试类型 | 成功的含义 | 结果中的译文 |
|---|---|---|
| `CONFIGURATION`（检查设置） | 离线配置校验通过；未请求网络，不能证明密钥、模型或连接可用 | 无 |
| `CONNECTION`（测试连接） | 固定探针请求成功，按所选协议及 SSE/JSON 模式完成响应解析；**HTTP 200 不够**，不能忽略无效结构、异常停止或缺少完成事件 | 无；不运行译文防损坏校验 |
| `TRANSLATION`（测试翻译） | 测试文本的请求、完整协议解析及 `Segments` 译文防损坏校验通过（非空、包装标签、旧分段标记、保留节点定位、占位符及长度比例）；不证明宿主节点重建的行段、样式或链接投影校验通过 | 仅成功时展示译文；失败不展示部分或损坏结果 |

`ApiTester.Result` 返回测试类型、成功标志、耗时和结构化 `Failure`。设置页由唯一的 `ApiTestResultFormatter` 通过 `getString` 解析本地化资源，区分三种成功概念；失败显示本地化建议、类别及固定类别码、固定原因码、已知 HTTP 状态和异常类型。每次测试还在结果下方显示独立请求 ID 与阶段诊断，并可随测试结果一起复制。耗时只用于页面显示，不进入持久化日志。Failure 包含 `kind / reason / httpStatus / exceptionType / upstreamErrorBody`，异常类型取底层 cause 类型（若有）；仅非 2xx HTTP 错误携带未经修改的上游正文，其余失败的 `upstreamErrorBody` 为 null。不携带原异常对象或异常消息，UI 不调用 `Throwable.getMessage()` 或 `userMessage()`。

| 固定原因码 | 排查建议 |
|---|---|
| `CONFIG_INVALID` | URL 填基础地址，如 `https://api.openai.com/v1`，不带 `/chat/completions`、`/responses`、`/messages`；检查协议、模型、正整数超时/并发、`Name: Value` 请求头 |
| `NETWORK_FAILURE` | 检查网络、DNS、TLS、VPN 或代理，然后手动重试 |
| `REQUEST_TIMEOUT` | 连接或等待数据超时；检查服务可用性，增大超时后重试，与模型停止和译文校验失败不同 |
| `HTTP_ERROR` | 400：请求参数；401：认证；403：权限；404：端点或模型不存在（检查基础 URL 和协议）；5xx：服务异常；其余状态检查供应商文档 |
| `RATE_LIMITED` | 非 2xx 正文读取完成后，HTTP 429 统一归为 `RATE_LIMIT / RATE_LIMITED`；稍后重试、降低并发并检查配额 |
| `RESPONSE_INVALID` | 协议或格式不匹配；流式必须对应 SSE，非流式对应 JSON，不自动切换模式；检查基础路径是否重复包含端点 |
| `RESPONSE_INCOMPLETE` | 响应截断或缺少 SSE 完成事件；检查服务和代理以及 SSE/JSON 支持，不接受部分结果 |
| `GENERATION_STOPPED` | 非正常停止原因，或 Responses 生成失败/未完成；检查模型输出限制和提示词，增加网络超时不能修复。拒绝/工具字段等一般协议错误归 `RESPONSE_INVALID`，非正常停止原因才归此码 |
| `TRANSLATION_INVALID` | 完整响应违反译文保留规则；调整仅翻译提示词或模型，增加超时不能修复内容损坏 |
| `EMPTY_TEXT` | 输入或提取文本为空；先填写非空测试文本，再检查模型、提示词与协议 |
| `CANCELLED` | 请求已取消；保持页面打开并手动重新测试 |
| `UNKNOWN_FAILURE` | 根据异常类型排查；需先保存启用日志的开关，再手动重试，不输出异常消息或响应正文 |

## 日志

`ProjectLog` 是本项目的唯一日志出口，默认及无效配置均关闭；启动时先读取已保存开关，再记录日志。关闭时不写输出、不消耗 once 去重键，重新开启可重新记录诊断。关闭日志不改变业务失败处理，也不删除既有日志或控制框架自身日志。

- **设置页进程：logcat**，标签为 `BoundlessChatDiscord`。页面不挂载 LSPosed 框架输出；不需要 LSPosed 连接，也不需要重启页面。必须将“项目日志”开关设为开启并成功保存后生效，或加载/导入已确认本地保存的值；草稿切换、草稿测试和失败的本地提交都不能强行开启日志。保存成功先应用已确认本地快照，再输出保存结果；远程同步失败仍与本地保存成功分开记录。成功保存关闭开关或重置为默认配置后，连该成功结果日志也不输出。
- **Discord 进程：logcat + LSPosed 项目日志**，框架输出仅由模块入口挂载，使用 remote 配置。因此需保存并同步成功后重启 Discord，设置页的 logcat 诊断与 Discord 的 LSPosed 诊断不是同一进程/目的地。
- **跨进程持久化诊断**：Discord 进程通过现有 `CacheCountProvider` 的 UID 校验调用新增 `publish_log` 方法，将项目日志异步发送到插件 APK。Provider 仍保留原有缓存数量发布接口；日志写入插件私有 `boundless_diagnostics.db`，与 Discord 翻译缓存完全分离。数据库最多保留最近 5,000 条，记录来源（`discord` / `module_test`）、时间、进程、级别、标签、请求 ID 和消息；写入前限制字段长度并脱敏常见 Bearer/API Key 格式。设置页可查看最近记录、复制全部或清空。
- **测试翻译诊断**：`ApiTester.Result` 带独立请求 ID 和阶段事件；测试页在结果下方显示阶段、保护标记数量、范围检查、语言检测、生产翻译入口、成功/失败类别、原因和 HTTP 状态。可复制的测试结果同时包含该次诊断。测试预览留在当前界面，不把原文/响应预览写入持久化日志；持久化记录只保存阶段和错误分类。
- 生产日志在项目日志开关开启时才跨进程转发；测试界面的阶段日志不依赖 Discord 进程日志开关。Provider 仅接受 Discord UID 的写入请求，插件应用自身读取私有数据库，不向 Discord 进程开放数据库文件。
- 网络请求收到非 2xx 状态时，`JsonApiEngine` 将 HTTP 状态和上游响应正文交给统一错误结果；设置页测试结果原样显示正文，`ProjectLog` 在日志开关启用时也原样记录正文。HTTP 200 后的协议/译文错误不把成功响应正文当作错误详情。
- 项目诊断不主动输出原请求正文、译文、消息身份、链接或凭据；**非 2xx 上游错误响应正文是明确允许展示和记录的例外**，不进入缓存或任务状态。错误响应正文可能包含上述敏感内容，分享日志或截图前应检查。


## 代码目录与跨聊天平台复用边界

| 目录 | 职责 | 是否依赖 Discord UI |
|---|---|---|
| `core/` | 配置快照、持久化缓存、翻译范围、任务状态、文本分段和通用工具 | 否 |
| `translate/` | API 请求、协议编码/解析、SSE、连接与翻译测试 | 否 |
| `ui/` | Android 设置页、配置同步界面、发送按钮配置编辑器和测试结果展示 | 否；设置页只负责配置 |
| `hooks/` | Discord 消息行、消息正文、输入框、原生发送按钮和视图生命周期适配 | **是** |
| 根目录 `ModuleEntry` | LSPosed 模块入口、目标包启动、资源/Hook 生命周期和运行时装配 | 是 |

跨聊天软件复用时，API、协议解析、缓存、配置版本、任务调度和译文校验继续复用 `core/` 与 `translate/`，不应复制到宿主 Hook 中。包名、版本特征、混淆类名、方法签名、消息视图定位、输入框定位、原生发送动作和按钮插入位置属于平台专属适配代码。不同聊天软件的输入框生命周期、消息节点结构和发送动作差异较大，必须逐个平台验证，不能假设只替换一个类名即可适配。

建议后续按以下边界逐步迁移；当前 `hooks/` 仍是 Discord 适配目录，尚未整体迁移到新目录。为避免只改包路径造成大量无意义风险，先保留已验证的调用链，后续抽取统一接口后再迁移：

```text
core/                 通用配置、缓存、任务和文本处理
translate/            通用 API、协议和流式响应
ui/                   通用设置与编辑器
platform/common/      后续抽取的宿主无关适配接口与数据模型
platform/discord/     后续迁移的 Discord Hook 与视图定位
platform/<other>/     其他聊天软件的 Hook 与视图适配
ModuleEntry           根据目标包选择并装配平台适配器
```

设置页分区：主页面保留常用开关、翻译范围、发送按钮管理、提示词/校验入口和测试工具；本地翻译缓存、容量、已保存数量及清空操作集中在缓存二级菜单；服务协议、流式请求、API URL、密钥、模型、请求头、超时和并发集中在 API 二级菜单。文本字段提供独立应用入口，开关和选择项按交互规则即时提交。
