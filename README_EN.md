# Boundless Chat for Discord

[简体中文](README.md) · [中文安装教程](docs/INSTALLATION_ZH.md) · [English installation guide](docs/INSTALLATION_EN.md)

**Boundless Chat for Discord** is an independently maintained Android LSPosed translation module by **Kuorshin**, and the first project in the **Boundless Chat** series. It is an independent downstream project based on WXM YYDS's original DiscordAutoTranslate and uses LibXposed API 102. Eligible message text is sent to a translation service configured by the user.

- [Latest release](https://github.com/Otakukisaragishintaro/Boundless-Chat-for-Discord/releases/latest)
- [GitHub Actions builds](https://github.com/Otakukisaragishintaro/Boundless-Chat-for-Discord/actions)
- [Project origin and licensing](NOTICE.md)

## Features

- Automatically translate message content and rich embeds. Tap the inline indicator to switch between the original and translated text; failed translations can be retried manually.
- Preserve Discord-hosted Markdown structure, links, emoji, mentions, and code nodes. Reject translations that cannot be safely mapped back to the original structure.
- Keep reply previews synchronized with the referenced message’s translation state.
- Support poll titles while preserving poll options, images, and interactions.
- Configure an API endpoint, model, target language, request headers, and one of three protocols: OpenAI Compatible, OpenAI Responses, or Anthropic Messages. Streaming and non-streaming requests are supported.
- Configure server- and channel-level translation rules. Channel rules take precedence over server rules, followed by the default policy.
- Add up to eight outbound translation buttons. Each button has its own display label, target language, and prompt. A button translates the current draft and uses Discord’s native send action; it does not change the incoming-message translation language.
- Test configuration, connectivity, incoming translation, and outbound translation from the settings screen.

## Supported Discord versions

| Discord Stable | versionCode | Supported features |
|---|---:|---|
| 340.13 | 340013 | Messages, reply previews, rich embeds, polls |
| 352.17 | 352017 | Messages, reply previews, rich embeds, polls |

Only the listed Discord versions are currently supported. Thread previews, image OCR, voice, and general UI text translation are not supported.

## Requirements

- Android 8.0 or later
- Root access
- LSPosed with LibXposed API 102 support
- A supported Discord Stable version listed above

## Installation

1. Install the module APK.
2. Open LSPosed, enable **Boundless Chat for Discord**, and add the official Discord package `com.discord` to the module scope.
3. Open the module settings and enter your translation API base URL, API key, and model. Select the protocol and incoming target language.
4. Save the configuration and wait for LSPosed synchronization to succeed. Force-stop and restart Discord.
5. Run **Configuration check**, **Test connection**, and **Test translation** before using the module. **Test outbound translation** validates the send-translation configuration without sending a Discord message.

See the [complete installation guide](docs/INSTALLATION_EN.md) for detailed setup and troubleshooting.

## Outbound language buttons

Open **Manage send buttons** in the module settings. Add a button, choose its label and target language, and optionally edit its independent prompt. You can configure up to eight buttons. The outbound target language and prompt are independent from incoming-message translation settings.

The `{{target_language}}` placeholder can be used in a prompt. The test button checks translation output but does not send a message. In Discord, the translation action appears only after Discord’s native send control is present; it stays hidden while that control is unavailable.

## Privacy

Eligible message text is sent to the translation provider you configure. API credentials are stored in the module’s private preferences and local LSPosed remote preferences. Review the provider’s privacy policy before use. Structural markers are sent to preserve formatting; URLs and recognizable emoji anchors may also be included.

Non-2xx upstream HTTP response bodies may be displayed in test results and recorded when project logging is enabled. They can contain sensitive information; review logs before sharing them.

## Development documentation

- [Architecture and lifecycle](docs/architecture.md)
- [Rendering, replies, polls, and indicators](docs/rendering.md)
- [Translation engine and configuration](docs/translation-engine.md)
- [Validation, signing, and releases](docs/development.md)

## Acknowledgements and project origin

This is an independent downstream project in the **Boundless Chat** series, maintained by **Kuorshin**. It is not an official branch or merged release of the original project.

This project is based on **DiscordAutoTranslate by WXM YYDS ([@wxmyyds](https://github.com/wxmyyds))**. We thank the original author for the initial implementation, LibXposed integration, Discord compatibility profiles, architecture, and continued maintenance. Subsequent features, UI improvements, outbound translation buttons, per-button prompts, settings improvements, and downstream maintenance are independently developed by Kuorshin.

Original upstream code remains under its original MIT License. Kuorshin's original contributions are separately licensed under [LICENSE-KUORSHIN.md](LICENSE-KUORSHIN.md). See [NOTICE.md](NOTICE.md) for scope and attribution details.

This project is not affiliated with Discord Inc. Discord is a trademark of Discord Inc. Use of this module may be subject to Discord’s Terms of Service.
