package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link Segments} 的共用标记拒绝谓词与纯文本防损坏校验。 */
public class SegmentsTest {

    @Test
    public void plainTextViolationRejectsWrapperTagsWithoutRepairOrPrivateDetails() {
        for (String text : new String[]{"<translate>你好</translate>", "<translate>",
                "</translate>", "前言 <translate>你好</translate> 后语",
                "https://private.invalid/?token=secret <translate>Bearer private-key"}) {
            assertEquals("译文包含包装标签", Segments.plainTextViolation(text, "hello"));
        }
        assertNull(Segments.plainTextViolation("你好", "hello"));
        // Only the exact request wrapper tags are rejected; no new fuzzy tag repair/detection.
        assertNull(Segments.plainTextViolation("<trans", "hello"));
        assertNull(Segments.plainTextViolation("<strong>你好</strong>", "hello"));
    }

    @Test
    public void stripOuterResponseWrapperRemovesOnlyOneExactOuterPair() {
        assertEquals("你好", Segments.stripOuterResponseWrapper("<translate>你好</translate>", "你好"));
        assertEquals("hello", Segments.stripOuterResponseWrapper(" <translate>hello</translate> ", "hello"));
        assertEquals("prefix <translate>hello</translate>",
                Segments.stripOuterResponseWrapper("prefix <translate>hello</translate>", "hello"));
        assertEquals("<translate>hello", Segments.stripOuterResponseWrapper("<translate>hello", "hello"));
        assertEquals("<translate><translate>hello</translate></translate>",
                Segments.stripOuterResponseWrapper("<translate><translate>hello</translate></translate>", "hello"));
        assertEquals("hello", Segments.stripOuterResponseWrapper("hello</translate>", "hello"));
        assertEquals("<keep1/>你好", Segments.stripOuterResponseWrapper("<keep1/>你好</translate>", "<keep1/>你好"));
        assertEquals("hello</translate></translate>",
                Segments.stripOuterResponseWrapper("hello</translate></translate>", "hello"));
        assertEquals("prefix </translate> middle</translate>",
                Segments.stripOuterResponseWrapper("prefix </translate> middle</translate>", "hello"));
        assertEquals("literal </translate>",
                Segments.stripOuterResponseWrapper("literal </translate>", "literal </translate>"));
        assertNull(Segments.stripOuterResponseWrapper(null, "hello"));
    }

    @Test
    public void validationChecksCanBeDisabledIndependentlyAndLengthRatiosAreConfigurable() {
        String wrapped = "<translate>hello</translate>";
        assertEquals("译文包含包装标签", Segments.plainTextViolation(wrapped, "hello"));
        assertNull(Segments.plainTextViolation(wrapped, "hello",
                false, false, false, false, 10, 500));

        String source = repeat('a', 40);
        String shortTranslation = repeat('b', 8);
        assertNotNull(Segments.plainTextViolation(shortTranslation, source,
                false, false, false, true, 25, 500));
        assertNull(Segments.plainTextViolation(shortTranslation, source,
                false, false, false, true, 20, 500));

        String longTranslation = repeat('b', 220);
        assertNotNull(Segments.plainTextViolation(longTranslation, source,
                false, false, false, true, 10, 500));
        assertNull(Segments.plainTextViolation(longTranslation, source,
                false, false, false, true, 10, 600));
    }

    @Test
    public void eachIntegritySwitchControlsOnlyItsOwnValidationRule() {
        assertNotNull(Segments.plainTextViolation("XXXX", "hello",
                false, false, true, false, 0, 1000));
        assertNull(Segments.plainTextViolation("XXXX", "hello",
                false, false, false, false, 0, 1000));

        assertTrue(Segments.plainTextViolation("hello", "hello<keep1/>",
                false, false, true, 0, 1000).contains("missing=[<keep1/>x1]"));
        assertNull(Segments.plainTextViolation("hello", "hello<keep1/>",
                false, false, false, false, 0, 1000));
    }

    @Test
    public void plainTextViolationChecksEmptyTextBeforeOtherValidation() {
        for (String text : new String[]{null, "", " \n\t "}) {
            assertEquals("译文为空", Segments.plainTextViolation(text, "hello<keep1/>"));
        }
    }

    @Test
    public void plainTextViolationLengthBoundariesAreInclusive() {
        String source = repeat('a', 40);
        assertNull(Segments.plainTextViolation(repeat('b', 4), source));
        assertNotNull(Segments.plainTextViolation(repeat('b', 3), source));
        assertNull(Segments.plainTextViolation(repeat('b', 200), source));
        assertNotNull(Segments.plainTextViolation(repeat('b', 201), source));
        assertNull(Segments.plainTextViolation(repeat('b', 121), repeat('a', 39)));
    }

    @Test
    public void plainTextViolationPlaceholderBoundaryAndNullAreRejected() {
        assertNull(Segments.plainTextViolation("XXX", "hello"));
        assertNotNull(Segments.plainTextViolation("XXXX", "hello"));
        assertNotNull(Segments.plainTextViolation(null, "hello"));
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            out.append(value);
        }
        return out.toString();
    }

    @Test
    public void plainTextViolationGuardsForSinglePath() {
        // 单链路引擎在返回译文前调用该校验：任何一项不过都判失败，
        // 由用户点图标重试，绝不把损坏内容送进节点树。
        assertNull("普通尖括号文本不应被当作旧分段标记",
                Segments.plainTextViolation(">.< <.< >_< <3", "hello world"));
        assertNotNull("模板占位符必须拦截",
                Segments.plainTextViolation("SENSENOVA_6_8_FLASH_LITE_XXXXXX", "hello world"));
        assertNotNull("空译文必须拦截",
                Segments.plainTextViolation("  ", "hello world"));

        // 长度比例校验只对 ≥40 字符的原文生效。
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            source.append("paragraph ").append(i).append(' ');
        }
        String longSource = source.toString();
        assertTrue(longSource.length() >= 40);
        StringBuilder flood = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            flood.append(longSource); // 10 倍长度：复读刷屏
        }
        assertNotNull("复读（长度暴涨）必须拦截",
                Segments.plainTextViolation(flood.toString(), longSource));
        assertNotNull("内容大面积丢失必须拦截",
                Segments.plainTextViolation("丢了", longSource));

        assertNull("正常译文必须放行",
                Segments.plainTextViolation("你好世界", "hello world"));
        assertNull("短原文不做比例校验",
                Segments.plainTextViolation("很长很长的译文比原文还长好几倍", "hi"));
    }
}
