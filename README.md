# Boundless Chat for Discord

[English](README_EN.md) · [中文安装教程](docs/INSTALLATION_ZH.md) · [English installation guide](docs/INSTALLATION_EN.md)

**Boundless Chat for Discord** 是由 **Kuorshin** 独立维护的 Android Discord LSPosed 翻译模块，也是 **Boundless Chat** 系列的首个项目。它基于原作者 WXM YYDS 的 DiscordAutoTranslate 独立发展，使用 LibXposed API 102。消息会发送到用户自行配置的翻译服务。

[下载最新版](https://github.com/Otakukisaragishintaro/Boundless-Chat-for-Discord/releases/latest) · [GitHub Actions 构建](https://github.com/Otakukisaragishintaro/Boundless-Chat-for-Discord/actions) · [项目来源与授权](NOTICE.md)

## 功能与支持版本

- 自动翻译消息正文及富 Embed，消息编辑后重新判定；图标显示进度、译文、原文或失败，点击切换，失败时手动重试。
- 保留宿主 Markdown 结构、链接、表情、提及和代码节点。无法严格对应原结构的译文会被拒绝，继续显示原文。
- 回复预览跟随被引用消息的已有译文和显示选择；引用本身不发起翻译请求。
- 投票标题控制整卡原文/译文切换，保留选项、图片及投票行为。图片选项仅翻译无障碍描述。
- 独立设置页支持目标语言、API、三种供应商协议、流式请求、配置检查和翻译测试。
- 支持最多 8 个自定义翻译发送按钮；每个按钮可单独设置显示名称、目标语言和提示词，翻译后调用 Discord 原生发送动作。
- 翻译范围支持服务器/频道规则：频道优先于服务器，未命中时使用默认策略；通过手动输入 ID 添加，备注不参与匹配。
- 可选本地译文缓存：命中相同原文及完整翻译配置时直接复用译文，减少重复 API 请求、Token 消耗和等待时间。

| Discord Stable | versionCode | 已接入能力 | Discord 安装包 |
|---|---|---|---|
| 340.13 | 340013 | 正文、回复预览、富 Embed、投票 | [下载](https://1drv.ms/u/c/8ccaf25c5b0de4fb/IQC_FsEpTW9pQYQTZZX4XrKJAUecfhaG98Q7V0cyTK1MIqg?e=QTqag5) |
| 352.17 | 352017 | 正文、回复预览、富 Embed、投票 | [下载](https://1drv.ms/u/c/8ccaf25c5b0de4fb/IQCbMILHoJWrQYp5eeZrkUTAAT85sbCHsOEhamnOonbWzCg?e=qh9gUL) |

仅支持表中列出的 Discord 版本。暂不支持帖子预览、图片 OCR、语音及界面文本翻译。

## 安装与设置

要求 Android 8.0+、Root，以及支持 LibXposed API 102 的 LSPosed。

1. 安装模块 APK，在 LSPosed 中启用 **Boundless Chat for Discord**，作用域勾选 `com.discord`。
2. 打开模块设置，填写 API 基础地址、密钥和模型，选择协议及目标语言。
3. 保存并确认同步成功，强制停止并重启 Discord。模块尚未启用时也可先保存，服务连接后同步。
4. 使用“配置检查”检查格式。“测试连接”验证请求、协议和非空结果，不检查译文防损坏；“测试翻译”和实际翻译仍保留完整译文校验。失败时显示具体原因；非 2xx HTTP 错误展示上游原文。

API 基础地址示例：`https://api.openai.com/v1`，协议端点由模块追加。支持 OpenAI Compatible、OpenAI Responses、Anthropic Messages，均可选择流式或非流式请求。

“自动翻译”开关默认开启，“项目日志”开关默认关闭，“流式请求”开关默认开启。名称和说明随当前草稿状态更新，切换开关不会自动保存。项目日志开关统一控制本项目的启动及错误诊断：设置页 logcat 日志成功保存后生效；Discord 的 logcat 和 LSPosed 日志需**保存、同步成功并重启 Discord**后生效。关闭日志不会删除已有日志，也不控制 Discord 或框架自身输出。

### 翻译范围

打开“翻译范围”，选择默认翻译（黑名单模式）或默认不翻译（白名单模式），分别添加服务器、频道的翻译/不翻译规则。频道规则覆盖服务器规则；删除规则即恢复继承。私信按频道规则及默认策略处理。编辑后先“应用到草稿”，再保存、同步并重启 Discord。API 测试不受范围规则限制。

在 Discord 开发者模式中复制服务器或频道 ID 后手动添加。服务器和频道各最多 256 条规则，备注仅用于显示和搜索；修改备注或规则顺序不会清空译文。模块不采集最近目录或真实名称；消息位置仅在运行时用于规则判定，识别失败时不会请求或显示译文。

### 本地翻译缓存

缓存位于“本地翻译缓存”子页面，**默认关闭**，需要手动启用。开启后，成功完成的新翻译会被保存；之后遇到相同原文且翻译配置版本一致的请求，会优先读取本地译文，不再调用 API。缓存容量可设为 1–1,000,000 条，默认 100,000 条；超出容量时按最近使用情况淘汰旧条目。页面会显示持久化缓存条目数，并提供手动清空按钮。

缓存数据库仅保存原文的 SHA-256 哈希键和译文，不保存原文、API 密钥或额外请求头。缓存位于 Discord 应用私有目录，清除 Discord 应用数据或卸载 Discord 会删除缓存。启用前已有的显示译文不会追溯写入；关闭缓存不会删除已保存内容，重新启用后仍可复用。清空操作会删除本地持久化译文。

## 隐私与排障

消息的可翻译文本会发送到你配置的服务。代码、提及、时间戳等宿主保留节点仅发送位置标记；URL 和可识别的 emoji 锚点仍可能随请求发送。API 密钥保存在模块私有设置及本机 LSPosed remote preferences。范围规则随设置同步；不会采集或持久化浏览过的服务器/频道目录。

项目诊断不主动输出消息正文、译文、身份标识、链接或 API 凭据。**非 2xx 上游 HTTP 错误正文是例外**：设置页测试结果原样显示，项目日志启用时也原样记录，可能包含敏感信息。分享日志或截图前请检查内容。

- 完全不触发：检查作用域、精确版本支持、自动翻译开关和 API 配置；短文、纯链接/代码或符合目标文字体系的正文可能被检测跳过。
- 请求或回填失败：检查网络、协议、超时和模型是否保留行及标记；失败图标支持手动重试，不会自动重试。
- 需要帮助：启用日志并保存；排查 Discord 内的翻译还需同步成功并重启 Discord。反馈时附上 Discord 版本、模块版本、复现步骤及检查过敏感内容的日志。

## 开发文档

- [架构与生命周期](docs/architecture.md)
- [渲染、回复、投票及图标](docs/rendering.md)
- [检测、翻译协议与配置](docs/translation-engine.md)
- [验证、签名与发布](docs/development.md)
- [Hook 版本适配 Skill](.agents/skills/discord-hook-adaptation/SKILL.md)
- [项目协作规则](AGENTS.md)

本项目与 Discord Inc. 无关联；Discord 为其商标。使用模块可能违反 Discord 服务条款，请自行评估。

## 鸣谢与项目来源

本项目是 **Boundless Chat** 系列的独立下游项目，由 **Kuorshin** 独立开发和维护，并非原作者仓库的官方分支或合并版本。

本项目基于 **WXM YYDS（[@wxmyyds](https://github.com/wxmyyds)）** 的 [DiscordAutoTranslate](https://github.com/wxmyyds/DiscordAutoTranslate) 开发。感谢原作者提供的初始实现、LibXposed 接入、Discord 版本适配配置、项目架构与持续维护。此后新增的功能、界面改进、发送翻译按钮、独立提示词、设置体验和相关维护由 Kuorshin 独立完成。

原作者代码继续遵循原项目 MIT License；Kuorshin 的原创贡献另按 [LICENSE-KUORSHIN.md](LICENSE-KUORSHIN.md) 授权。具体范围见 [NOTICE.md](NOTICE.md)。
