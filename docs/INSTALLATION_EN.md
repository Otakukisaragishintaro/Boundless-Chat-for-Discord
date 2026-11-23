# Installation and Setup Guide

## 1. Requirements

- Android 8.0 or later
- Root access
- LSPosed with LibXposed API 102 support
- Discord Android Stable 340.13 (versionCode 340013) or 352.17 (versionCode 352017)

The module hooks the Discord client. Other Discord versions are not guaranteed to work. After Discord updates, wait for a matching compatibility profile before relying on the module.

## 2. Install and enable the module

1. Install the module APK.
2. Open LSPosed and enable **Discord AutoTranslate**.
3. Add the official Discord app, package name `com.discord`, to the module scope.
4. Complete any restart requested by LSPosed, then open the module settings activity.

## 3. Configure a translation provider

- **Protocol**: OpenAI Compatible, OpenAI Responses, or Anthropic Messages, according to your provider.
- **API URL**: The provider’s base URL, for example `https://api.openai.com/v1`. The module appends the protocol endpoint.
- **API key**: Your provider-issued credential. Never commit it to a public repository or include it in screenshots.
- **Model**: A model name supported by your provider.
- **Target language**: The language used for incoming-message translations.
- **Streaming**: Enable only when the selected endpoint supports the configured streaming protocol.

Run **Configuration check**, **Test connection**, and **Test translation**. These tests validate the provider configuration and translation output; they do not send a Discord message.

## 4. Configure outbound translation buttons

1. Open **Manage send buttons**.
2. Select **Add language button**.
3. Set a display label and target language.
4. Optionally edit the button’s independent prompt. The prompt supports the `{{target_language}}` placeholder.
5. Finish the button editor, then save the complete settings page.

You can configure up to eight buttons. Each button has its own target language and prompt, independent of the incoming translation language. **Test outbound translation** validates the generated translation but does not send a Discord message.

In a Discord chat, the outbound translation action appears only after Discord’s native send control is present. Selecting it translates the current draft and then invokes Discord’s native send action. The translation action stays hidden while the composer is empty or the native send control is unavailable.

## 5. Save, sync, and restart Discord

1. Tap **Save**.
2. Wait for the local save confirmation and successful LSPosed synchronization.
3. Force-stop Discord and launch it again.

Settings can be saved locally before LSPosed connects. They will synchronize when the service becomes available. If synchronization fails, resolve the LSPosed connection issue before restarting Discord.

## 6. Translation scope rules

- **Translate by default**: unmatched locations are translated; rules can exclude selected locations.
- **Do not translate by default**: unmatched locations are skipped; rules can allow selected locations.
- Channel rules override server rules.
- Deleting a rule restores inheritance from the server rule or default policy.

Copy server and channel IDs manually in Discord Developer Mode. Apply changes to the draft, save the settings, wait for synchronization, and restart Discord.

## 7. Troubleshooting

**No translation indicator or outbound button appears**
- Confirm that the module is enabled in LSPosed and that `com.discord` is in its scope.
- Confirm that your Discord version is listed as supported.
- Check the automatic translation switch and API configuration.
- The outbound action is intentionally hidden until Discord’s native send control is actually available.

**Settings were saved but Discord did not change**
- Check the LSPosed service connection and synchronization status.
- Force-stop and restart Discord.

**Provider tests fail**
- Verify the API URL, key, model, protocol, network connection, and provider quota.
- Review the error category and HTTP status in the test result.
- Non-2xx upstream response bodies may contain sensitive information. Review logs before sharing them.

**Android refuses to install an update**
- Release APKs use this project’s own signing key. A later APK can update an installed release only if it uses the same signing identity. Android rejects updates signed with a different key. Do not uninstall an existing installation until you have considered its stored settings and data.

## Privacy

Eligible message text is sent to the translation provider you configure. API credentials are stored in the module’s private preferences and local LSPosed remote preferences. Review your provider’s data-handling policy. When project logging is enabled, non-2xx upstream HTTP response bodies may be recorded; inspect logs before sharing.
