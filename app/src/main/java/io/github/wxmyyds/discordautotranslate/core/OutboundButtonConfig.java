package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** One independently configured outbound-translation action. */
public final class OutboundButtonConfig {
    public static final int MAX_BUTTONS = 8;
    public static final int MAX_LABEL_LENGTH = 18;
    public static final int MAX_PROMPT_LENGTH = 24000;

    public final String id;
    public final String label;
    public final String targetLanguage;
    public final String systemPrompt;

    public OutboundButtonConfig(String id, String label, String targetLanguage,
                                String systemPrompt) {
        this.id = clean(id, 80, "id");
        this.label = clean(label, MAX_LABEL_LENGTH, "label");
        this.targetLanguage = clean(targetLanguage, 80, "targetLanguage");
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        if (this.label.trim().isEmpty() || this.targetLanguage.trim().isEmpty()
                || this.systemPrompt.trim().isEmpty()
                || this.systemPrompt.length() > MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("发送按钮配置不完整或提示词过长");
        }
    }

    private static String clean(String value, int max, String field) {
        if (value == null || value.trim().isEmpty() || value.length() > max) {
            throw new IllegalArgumentException("发送按钮 " + field + " 无效");
        }
        return value.trim();
    }

    public static OutboundButtonConfig create(String label, String targetLanguage,
                                               String prompt) {
        return new OutboundButtonConfig(UUID.randomUUID().toString(), label,
                targetLanguage, prompt);
    }

    public static List<OutboundButtonConfig> defaults(String language, String prompt) {
        String safeLanguage = language == null || language.trim().isEmpty()
                ? Config.DEF_SEND_TARGET_LANG : language.trim();
        String safePrompt = prompt == null || prompt.trim().isEmpty()
                ? Config.DEF_SEND_SYSTEM_PROMPT : prompt;
        return Collections.singletonList(create(defaultLabel(safeLanguage),
                safeLanguage, safePrompt));
    }

    public static String defaultLabel(String language) {
        if ("日本語".equals(language)) return "日语发送";
        if ("English".equals(language)) return "英语发送";
        if ("简体中文".equals(language)) return "中文发送";
        if ("繁體中文".equals(language)) return "繁中发送";
        if ("한국어".equals(language)) return "韩语发送";
        return language + "发送";
    }

    public static List<OutboundButtonConfig> parse(@Nullable String json,
                                                    String legacyLanguage,
                                                    String legacyPrompt) {
        if (json == null || json.trim().isEmpty()) {
            return defaults(legacyLanguage, legacyPrompt);
        }
        try {
            JSONArray array = new JSONArray(json);
            if (array.length() < 1 || array.length() > MAX_BUTTONS) {
                throw new IllegalArgumentException("发送按钮数量必须为 1 到 " + MAX_BUTTONS);
            }
            List<OutboundButtonConfig> result = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                OutboundButtonConfig button = new OutboundButtonConfig(
                        item.getString("id"), item.getString("label"),
                        item.getString("targetLanguage"), item.getString("systemPrompt"));
                if (!ids.add(button.id)) {
                    throw new IllegalArgumentException("发送按钮 ID 重复");
                }
                result.add(button);
            }
            return Collections.unmodifiableList(result);
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("发送按钮配置 JSON 无效", malformed);
        }
    }

    public static String toJson(List<OutboundButtonConfig> buttons) {
        if (buttons == null || buttons.isEmpty() || buttons.size() > MAX_BUTTONS) {
            throw new IllegalArgumentException("至少保留一个发送按钮，最多 " + MAX_BUTTONS + " 个");
        }
        JSONArray array = new JSONArray();
        Set<String> ids = new HashSet<>();
        for (OutboundButtonConfig button : buttons) {
            if (button == null || !ids.add(button.id)) {
                throw new IllegalArgumentException("发送按钮配置无效或 ID 重复");
            }
            JSONObject item = new JSONObject();
            try {
                item.put("id", button.id);
                item.put("label", button.label);
                item.put("targetLanguage", button.targetLanguage);
                item.put("systemPrompt", button.systemPrompt);
                array.put(item);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return array.toString();
    }
}
