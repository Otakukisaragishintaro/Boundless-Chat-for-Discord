package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Incoming translation styles shared by every target language.
 *
 * <p>Each style owns one prompt for every supported language. The default style is selected
 * independently per language; a server or channel rule may select a style ID which is then
 * resolved using that message's target language.</p>
 */
public final class IncomingPromptStyles {
    public static final int MAX_STYLES = 32;
    public static final int MAX_NAME_LENGTH = 32;
    public static final String DEFAULT_STYLE_ID = "default";

    public static final class Style {
        public final String id;
        public final String name;
        public final Map<String, String> prompts;

        public Style(String id, String name, Map<String, String> prompts) {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new IllegalArgumentException("翻译风格 ID 无效");
            }
            if (name == null || name.trim().isEmpty() || name.trim().length() > MAX_NAME_LENGTH) {
                throw new IllegalArgumentException("翻译风格名称不能为空或超过长度限制");
            }
            Map<String, String> copy = new LinkedHashMap<>();
            for (String language : LanguagePromptConfig.LANGUAGES) {
                String prompt = prompts == null ? null : prompts.get(language);
                if (prompt == null) prompt = LanguagePromptConfig.defaultPrompt(language);
                validatePrompt(prompt);
                copy.put(language, prompt);
            }
            this.id = id;
            this.name = name.trim();
            this.prompts = Collections.unmodifiableMap(copy);
        }
    }

    private final List<Style> styles;
    private final Map<String, String> defaultStyleByLanguage;
    private final Map<String, Style> byId;

    private IncomingPromptStyles(List<Style> styles, Map<String, String> defaults) {
        if (styles == null || styles.isEmpty() || styles.size() > MAX_STYLES) {
            throw new IllegalArgumentException("翻译风格数量必须在 1 到 " + MAX_STYLES + " 之间");
        }
        Map<String, Style> index = new LinkedHashMap<>();
        for (Style style : styles) {
            if (style == null || index.put(style.id, style) != null) {
                throw new IllegalArgumentException("翻译风格 ID 重复或无效");
            }
        }
        Map<String, String> selected = new LinkedHashMap<>();
        for (String language : LanguagePromptConfig.LANGUAGES) {
            String id = defaults == null ? null : defaults.get(language);
            if (id == null) id = DEFAULT_STYLE_ID;
            if (!index.containsKey(id)) {
                throw new IllegalArgumentException("语言 " + language + " 的默认风格不存在");
            }
            selected.put(language, id);
        }
        this.styles = Collections.unmodifiableList(new ArrayList<>(styles));
        this.defaultStyleByLanguage = Collections.unmodifiableMap(selected);
        this.byId = Collections.unmodifiableMap(index);
    }

    public static IncomingPromptStyles defaults() {
        Map<String, String> prompts = LanguagePromptConfig.defaults();
        Style standard = new Style(DEFAULT_STYLE_ID, "默认", prompts);
        Map<String, String> defaults = new LinkedHashMap<>();
        for (String language : LanguagePromptConfig.LANGUAGES) defaults.put(language, DEFAULT_STYLE_ID);
        return new IncomingPromptStyles(Collections.singletonList(standard), defaults);
    }

    /** Migrate the former per-language prompt map into the default style without losing text. */
    public static IncomingPromptStyles read(String json, Map<String, String> legacyPrompts) {
        if (json == null || json.trim().isEmpty()) {
            Map<String, String> prompts = legacyPrompts == null
                    ? LanguagePromptConfig.defaults() : legacyPrompts;
            Style standard = new Style(DEFAULT_STYLE_ID, "默认", prompts);
            Map<String, String> defaults = new LinkedHashMap<>();
            for (String language : LanguagePromptConfig.LANGUAGES) {
                defaults.put(language, DEFAULT_STYLE_ID);
            }
            return new IncomingPromptStyles(Collections.singletonList(standard), defaults);
        }
        try {
            JSONObject root = new JSONObject(json);
            if (root.length() != 2 || !root.has("styles") || !root.has("defaults")) {
                throw new IllegalArgumentException("接收翻译风格配置结构无效");
            }
            JSONArray array = root.getJSONArray("styles");
            if (array.length() < 1 || array.length() > MAX_STYLES) {
                throw new IllegalArgumentException("翻译风格数量无效");
            }
            List<Style> styles = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                if (item.length() != 3 || !item.has("id") || !item.has("name")
                        || !item.has("prompts")) {
                    throw new IllegalArgumentException("翻译风格项目结构无效");
                }
                JSONObject promptObject = item.getJSONObject("prompts");
                Map<String, String> prompts = new LinkedHashMap<>();
                for (String language : LanguagePromptConfig.LANGUAGES) {
                    if (!promptObject.has(language)) {
                        throw new IllegalArgumentException("翻译风格缺少语言：" + language);
                    }
                    prompts.put(language, promptObject.getString(language));
                }
                styles.add(new Style(item.getString("id"), item.getString("name"), prompts));
            }
            JSONObject defaultObject = root.getJSONObject("defaults");
            Map<String, String> defaults = new LinkedHashMap<>();
            for (String language : LanguagePromptConfig.LANGUAGES) {
                if (!defaultObject.has(language)) {
                    throw new IllegalArgumentException("默认风格缺少语言：" + language);
                }
                defaults.put(language, defaultObject.getString(language));
            }
            return new IncomingPromptStyles(styles, defaults);
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("接收翻译风格 JSON 无效", malformed);
        }
    }

    public List<Style> styles() {
        return styles;
    }

    public Style style(String id) {
        Style style = byId.get(id);
        if (style == null) throw new IllegalArgumentException("指定的翻译风格不存在：" + id);
        return style;
    }

    public String defaultStyleId(String language) {
        String id = defaultStyleByLanguage.get(language);
        return id == null ? DEFAULT_STYLE_ID : id;
    }

    public String resolve(String language, String styleId) {
        String target = language == null || language.trim().isEmpty()
                ? Config.DEF_TARGET_LANG : language.trim();
        Style selected = style(styleId == null || styleId.trim().isEmpty()
                ? defaultStyleId(target) : styleId);
        String prompt = selected.prompts.get(target);
        if (prompt == null) throw new IllegalArgumentException("翻译风格不支持目标语言：" + target);
        return prompt;
    }

    public Map<String, String> defaultPrompts() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String language : LanguagePromptConfig.LANGUAGES) {
            result.put(language, resolve(language, defaultStyleId(language)));
        }
        return Collections.unmodifiableMap(result);
    }

    public IncomingPromptStyles withPrompt(String styleId, String language, String prompt) {
        if (!LanguagePromptConfig.LANGUAGES.contains(language)) {
            throw new IllegalArgumentException("不支持的目标语言");
        }
        validatePrompt(prompt);
        List<Style> updated = new ArrayList<>();
        for (Style style : styles) {
            if (!style.id.equals(styleId)) {
                updated.add(style);
            } else {
                Map<String, String> prompts = new LinkedHashMap<>(style.prompts);
                prompts.put(language, prompt);
                updated.add(new Style(style.id, style.name, prompts));
            }
        }
        if (!byId.containsKey(styleId)) throw new IllegalArgumentException("翻译风格不存在");
        return new IncomingPromptStyles(updated, defaultStyleByLanguage);
    }

    public IncomingPromptStyles addStyle(String name) {
        if (styles.size() >= MAX_STYLES) throw new IllegalArgumentException("翻译风格数量已达上限");
        Map<String, String> prompts = LanguagePromptConfig.defaults();
        List<Style> updated = new ArrayList<>(styles);
        updated.add(new Style(UUID.randomUUID().toString().replace("-", ""), name, prompts));
        return new IncomingPromptStyles(updated, defaultStyleByLanguage);
    }

    public IncomingPromptStyles renameStyle(String styleId, String name) {
        List<Style> updated = new ArrayList<>();
        for (Style style : styles) {
            updated.add(style.id.equals(styleId) ? new Style(style.id, name, style.prompts) : style);
        }
        if (!byId.containsKey(styleId)) throw new IllegalArgumentException("翻译风格不存在");
        return new IncomingPromptStyles(updated, defaultStyleByLanguage);
    }

    public IncomingPromptStyles setDefault(String language, String styleId) {
        if (!LanguagePromptConfig.LANGUAGES.contains(language) || !byId.containsKey(styleId)) {
            throw new IllegalArgumentException("默认翻译风格设置无效");
        }
        Map<String, String> defaults = new LinkedHashMap<>(defaultStyleByLanguage);
        defaults.put(language, styleId);
        return new IncomingPromptStyles(styles, defaults);
    }

    public IncomingPromptStyles deleteStyle(String styleId) {
        if (!byId.containsKey(styleId)) throw new IllegalArgumentException("翻译风格不存在");
        if (styles.size() == 1) throw new IllegalArgumentException("至少保留一种翻译风格");
        for (String language : LanguagePromptConfig.LANGUAGES) {
            if (styleId.equals(defaultStyleByLanguage.get(language))) {
                throw new IllegalArgumentException("此风格仍是 " + language + " 的默认风格，请先更换默认风格");
            }
        }
        List<Style> updated = new ArrayList<>();
        for (Style style : styles) if (!style.id.equals(styleId)) updated.add(style);
        return new IncomingPromptStyles(updated, defaultStyleByLanguage);
    }

    public String toJson() {
        JSONObject root = new JSONObject();
        JSONArray array = new JSONArray();
        JSONObject defaults = new JSONObject();
        try {
            for (Style style : styles) {
                JSONObject item = new JSONObject();
                JSONObject prompts = new JSONObject();
                for (String language : LanguagePromptConfig.LANGUAGES) {
                    prompts.put(language, style.prompts.get(language));
                }
                item.put("id", style.id);
                item.put("name", style.name);
                item.put("prompts", prompts);
                array.put(item);
            }
            for (String language : LanguagePromptConfig.LANGUAGES) {
                defaults.put(language, defaultStyleByLanguage.get(language));
            }
            root.put("styles", array);
            root.put("defaults", defaults);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return root.toString();
    }

    private static void validatePrompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()
                || prompt.length() > LanguagePromptConfig.MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("翻译提示词不能为空或超过长度限制");
        }
    }
}
