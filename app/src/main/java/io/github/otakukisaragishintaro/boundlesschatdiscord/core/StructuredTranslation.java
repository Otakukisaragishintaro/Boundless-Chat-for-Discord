package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Converts protected text into independently identified text segments.
 * Providers translate segment text only; marker placement is reconstructed locally.
 */
public final class StructuredTranslation {
    private static final Pattern MARKER = Pattern.compile("<keep[1-9][0-9]*/>");

    private StructuredTranslation() { }

    public static final class Source {
        public final String requestJson;
        private final List<String> ids;
        private final List<String> markers;

        private Source(String requestJson, List<String> ids, List<String> markers) {
            this.requestJson = requestJson;
            this.ids = ids;
            this.markers = markers;
        }

        public String restore(String responseJson) throws TranslationException {
            try {
                JSONObject root = new JSONObject(responseJson);
                JSONArray returned = root.optJSONArray("segments");
                if (returned == null || returned.length() != ids.size()) {
                    throw invalid("返回片段数量不匹配");
                }
                Map<String, String> translated = new HashMap<>();
                for (int i = 0; i < returned.length(); i++) {
                    JSONObject item = returned.optJSONObject(i);
                    if (item == null) throw invalid("返回片段不是对象");
                    Object idValue = item.opt("id");
                    Object textValue = item.opt("text");
                    if (!(idValue instanceof String) || !(textValue instanceof String)) {
                        throw invalid("返回片段缺少字符串 id 或 text");
                    }
                    String id = (String) idValue;
                    if (!ids.contains(id) || translated.put(id, (String) textValue) != null) {
                        throw invalid("返回片段 id 未知或重复");
                    }
                }
                if (translated.size() != ids.size()) {
                    throw invalid("返回片段 id 不完整");
                }
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < ids.size(); i++) {
                    out.append(translated.get(ids.get(i)));
                    if (i < markers.size()) out.append(markers.get(i));
                }
                return out.toString();
            } catch (JSONException failure) {
                throw invalid("返回内容不是合法 JSON");
            }
        }
    }

    public static boolean hasMarkers(String source) {
        return source != null && MARKER.matcher(source).find();
    }

    public static Source split(String source) throws TranslationException {
        if (source == null) throw invalid("源文本为空");
        Matcher matcher = MARKER.matcher(source);
        List<String> ids = new ArrayList<>();
        List<String> markers = new ArrayList<>();
        JSONArray segments = new JSONArray();
        int start = 0;
        int index = 0;
        while (matcher.find()) {
            addSegment(segments, ids, "s" + index++, source.substring(start, matcher.start()));
            markers.add(matcher.group());
            start = matcher.end();
        }
        addSegment(segments, ids, "s" + index, source.substring(start));
        try {
            JSONObject request = new JSONObject();
            request.put("segments", segments);
            return new Source(request.toString(), ids, markers);
        } catch (JSONException impossible) {
            throw invalid("无法构造片段请求");
        }
    }

    private static void addSegment(JSONArray array, List<String> ids,
                                   String id, String text) throws TranslationException {
        try {
            JSONObject segment = new JSONObject();
            segment.put("id", id);
            segment.put("text", text);
            array.put(segment);
            ids.add(id);
        } catch (JSONException impossible) {
            throw invalid("无法构造片段请求");
        }
    }

    private static TranslationException invalid(String detail) {
        return new TranslationException(TranslationException.Kind.PARSE,
                TranslationException.Reason.TRANSLATION_INVALID,
                "结构化片段校验失败：" + detail);
    }
}
