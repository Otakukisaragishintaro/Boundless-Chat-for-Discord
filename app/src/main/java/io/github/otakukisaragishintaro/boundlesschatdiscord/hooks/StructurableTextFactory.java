package io.github.otakukisaragishintaro.boundlesschatdiscord.hooks;

import io.github.otakukisaragishintaro.boundlesschatdiscord.core.ProjectLog;

import androidx.annotation.Nullable;

import io.github.otakukisaragishintaro.boundlesschatdiscord.core.HookConfiguration;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.Reflect;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.TextExtractor;

import java.lang.reflect.Constructor;
import java.util.List;

/**
 * 构造 Discord 的 {@code StructurableText} 实例（需求 §7 阶段 3）。
 *
 * <p>按配置指定的 StructurableText 类型构造 Primitive 与 Annotated 实例。
 * <ul>
 *   <li>{@code AnnotatedStructurableText} → 渲染内容节点列表；</li>
 *   <li>{@code PrimitiveStructurableText} → 整体当纯文本；</li>
 *   <li>其他类型 → 抛 {@code RuntimeException}。</li>
 * </ul>
 *
 * <p>为译文优先保留 Annotated 结构；若构造失败，调用方继续使用原始正文。</p>
 */
public final class StructurableTextFactory {

    private static volatile Constructor<?> sPrimitiveCtor;
    private static volatile Constructor<?> sAnnotatedCtor;

    private StructurableTextFactory() {
    }

    /** 由模块入口在拿到目标 ClassLoader 后初始化。 */
    public static void init(@Nullable ClassLoader cl, HookConfiguration config) {
        Class<?> primitiveClass = Reflect.loadClass(cl, config.typeClass("PrimitiveStructurableText"));
        Class<?> annotatedClass = Reflect.loadClass(cl, config.typeClass("AnnotatedStructurableText"));
        sPrimitiveCtor = ctorOf(primitiveClass, String.class);
        sAnnotatedCtor = ctorOf(annotatedClass, List.class);

    }

    @Nullable
    private static Constructor<?> ctorOf(@Nullable Class<?> cls, Class<?>... params) {
        if (cls == null) {
            return null;
        }
        try {
            Constructor<?> c = cls.getDeclaredConstructor(params);
            c.setAccessible(true);
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void reset() {
        sPrimitiveCtor = null;
        sAnnotatedCtor = null;
    }

    /** 构造 {@code PrimitiveStructurableText(text)}；不可用时返回 null。 */
    @Nullable
    public static Object primitive(@Nullable String text) {
        Constructor<?> ctor = sPrimitiveCtor;
        if (ctor == null || text == null) {
            return null;
        }
        try {
            return ctor.newInstance(text);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 构造 {@code AnnotatedStructurableText(List<ContentNode>)}；不可用时返回 null。 */
    @Nullable
    public static Object annotated(@Nullable List<?> nodes) {
        Constructor<?> ctor = sAnnotatedCtor;
        if (ctor == null || nodes == null) {
            return null;
        }
        try {
            return ctor.newInstance(nodes);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 为译文构造替换用的 StructurableText。
     *
     * <p>策略：优先保留原类型与 Markdown 结构。</p>
     *
     * @param original      原始 StructurableText（用于判断类型与取节点树）
     * @param translated    译文
     * @return 替换对象；无法构造时返回 null（调用方应保持原文）
     */
    @Nullable
    static Object forPlannedTranslation(@Nullable Object original, String translated,
                                       @Nullable TextExtractor.Result extracted) {
        if (original == null || translated == null || extracted == null) {
            return rejectFactory("INVALID_INPUT");
        }

        // 1) 原始是 Annotated：尝试保留 Markdown 结构。
        if (ContentNodes.isAnnotated(original)) {
            if (extracted.tree == null) return rejectFactory("MISSING_TREE");
            // A structure containing no translatable text (for example, emoji-only content)
            // must never be overwritten by an unrelated model response.
            if (extracted.tree.segmentCount == 0 || extracted.textNodeCount == 0) {
                return rejectFactory("NO_TRANSLATABLE_TEXT");
            }
            List<Object> replaced = ContentNodes.replacePlanned(extracted.tree, translated);
            if (replaced != null) {
                Object out = annotated(replaced);
                if (out != null) return out;
                ProjectLog.warn("annotated translation reconstruction failed; displaying translated text without rich formatting");
            } else {
                ProjectLog.warn("annotated translation alignment failed; displaying translated text without rich formatting");
            }
            // Translation visibility takes priority over preserving Markdown when native
            // node alignment cannot represent the model output.
            // Structural anchors belong to Discord's native node tree, not visible text.
            // Remove only the exact internal anchor grammars before using the plain-text
            // representation; never expose <keepN/> or <xN> tokens to the user.
            String visibleText = translated
                    .replaceAll("</?x[0-9]+>", "")
                    .replaceAll("<keep[0-9]+/>", "");
            Object plain = primitive(visibleText);
            return plain == null ? rejectFactory("PRIMITIVE_CONSTRUCTOR") : plain;
        }

        // 2) 原始是 Primitive：直接给纯文本。
        if (ContentNodes.isPrimitive(original)) {
            Object out = primitive(translated);
            return out == null ? rejectFactory("PRIMITIVE_CONSTRUCTOR") : out;
        }

        // 3) 未知类型：交给渲染器会抛异常，因此坚持不替换。
        return rejectFactory("UNSUPPORTED_TYPE");
    }

    @Nullable
    private static Object rejectFactory(String reason) {
        ProjectLog.info("structurable replacement rejected reason=" + reason);
        return null;
    }
}
