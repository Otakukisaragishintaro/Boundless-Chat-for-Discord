package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical per-target-language prompt library for both translation directions. */
public final class LanguagePromptConfig {
    public static final int MAX_PROMPT_LENGTH = 24000;
    public static final List<String> LANGUAGES;
    static {
        List<String> languages = new ArrayList<>();
        Collections.addAll(languages, "简体中文", "繁體中文", "English", "日本語", "한국어", "Русский",
                "Українська", "Español", "Français", "Deutsch", "Português", "Italiano",
                "العربية", "فارسی", "हिन्दी", "বাংলা", "ไทย", "עברית", "Ελληνικά",
                "Tiếng Việt", "Türkçe", "Bahasa Indonesia");
        LANGUAGES = Collections.unmodifiableList(languages);
    }

    private LanguagePromptConfig() { }

    public static String defaultPrompt(String language) {
        String target = language == null || language.trim().isEmpty()
                ? Config.DEF_TARGET_LANG : language.trim();
        return Config.DEF_SYSTEM_PROMPT.replace("Simplified Chinese", target);
    }

    public static Map<String, String> defaults() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String language : LANGUAGES) result.put(language, defaultPrompt(language));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Missing maps are migrated from the former single prompt into the currently selected
     * language only. Every other language receives its own generated default template.
     */
    public static Map<String, String> read(String json, String selectedLanguage,
                                           String legacyPrompt, boolean outbound) {
        Map<String, String> result = new LinkedHashMap<>(defaults());
        if (json != null && !json.trim().isEmpty()) {
            try {
                JSONObject object = new JSONObject(json);
                if (object.length() < LANGUAGES.size()) {
                    throw new IllegalArgumentException("语言提示词配置必须包含全部受支持语言");
                }
                for (String key : object.keySet()) {
                    if (key == null || key.trim().isEmpty()) {
                        throw new IllegalArgumentException("语言提示词包含空语言键");
                    }
                    String prompt = object.getString(key);
                    validatePrompt(prompt);
                    result.put(key, prompt);
                }
                for (String language : LANGUAGES) {
                    if (!object.has(language)) {
                        throw new IllegalArgumentException("语言提示词缺少：" + language);
                    }
                }
            } catch (JSONException malformed) {
                throw new IllegalArgumentException("语言提示词 JSON 无效", malformed);
            }
        } else if (legacyPrompt != null && !legacyPrompt.trim().isEmpty()) {
            String legacyDefault = outbound ? Config.DEF_SEND_SYSTEM_PROMPT
                    : Config.DEF_SYSTEM_PROMPT;
            if (!legacyDefault.equals(legacyPrompt)) {
                String selected = selectedLanguage == null || selectedLanguage.trim().isEmpty()
                        ? Config.DEF_TARGET_LANG : selectedLanguage.trim();
                validatePrompt(legacyPrompt);
                result.put(selected, legacyPrompt);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public static String toJson(Map<String, String> prompts) {
        JSONObject object = new JSONObject();
        try {
            Map<String, String> values = prompts == null ? defaults() : prompts;
            for (String language : LANGUAGES) {
                String prompt = values.get(language);
                if (prompt == null) prompt = defaultPrompt(language);
                validatePrompt(prompt);
                object.put(language, prompt);
            }
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (!object.has(entry.getKey())) {
                    validatePrompt(entry.getValue());
                    object.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return object.toString();
    }

    public static String resolve(Map<String, String> prompts, String language) {
        String target = language == null || language.trim().isEmpty()
                ? Config.DEF_TARGET_LANG : language.trim();
        String prompt = prompts == null ? null : prompts.get(target);
        return prompt == null ? defaultPrompt(target) : prompt;
    }

    public static boolean isDefaultPrompt(String prompt, String language, boolean outbound) {
        return defaultPrompt(language).equals(prompt)
                || (outbound && Config.DEF_SEND_SYSTEM_PROMPT.equals(prompt));
    }

    private static void validatePrompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty() || prompt.length() > MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("语言提示词不能为空或超过长度限制");
        }
    }
}
