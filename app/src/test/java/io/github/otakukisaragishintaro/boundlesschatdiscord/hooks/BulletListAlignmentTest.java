package io.github.otakukisaragishintaro.boundlesschatdiscord.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.otakukisaragishintaro.boundlesschatdiscord.core.ContentTree;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.TextExtractor;
import io.github.otakukisaragishintaro.boundlesschatdiscord.hooks.ContentNodesTest.AnnotatedStructurableText;
import io.github.otakukisaragishintaro.boundlesschatdiscord.hooks.ContentNodesTest.BulletListContentNode;
import io.github.otakukisaragishintaro.boundlesschatdiscord.hooks.ContentNodesTest.InlineCodeContentNode;
import io.github.otakukisaragishintaro.boundlesschatdiscord.hooks.ContentNodesTest.LinkContentNode;
import io.github.otakukisaragishintaro.boundlesschatdiscord.hooks.ContentNodesTest.StrongContentNode;
import io.github.otakukisaragishintaro.boundlesschatdiscord.hooks.ContentNodesTest.TextContentNode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 列表项严格回填回归。
 *
 * <p>源树中的每个列表项独立成组。译文必须按相同顺序逐行给出；
 * 列表项之间新增空段落、合并或缺失译文时整条拒绝。请求中的 URL 与 emoji
 * 仍是必须保留的定位标记。</p>
 */
public class BulletListAlignmentTest {

    @org.junit.Before
    public void activateFixtureProfile() { ContentNodeTestProfile.activate(ContentNodesTest.class); }

    @org.junit.After
    public void clearFixtureProfile() { ContentNodeTestProfile.clear(); }

    private static final String URL = "https://codex-relay.codes/register?aff=QlIz";

    private static final String[] BULLETS = {
            "Support for Codex CLI, Claude Code, and other AI coding tools",
            "Access to multiple AI models",
            "Pay-as-you-go pricing",
            "Custom API keys and spending limits",
            "R$5 in free credits after Telegram verification"
    };

    private static List<Object> list(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    /** 上报原文的节点树（与提取口径一致的 10 个可翻译片段）。 */
    private static List<Object> reportedTree() {
        List<List<Object>> items = new ArrayList<>();
        for (String bullet : BULLETS) {
            items.add(list(new TextContentNode(bullet)));
        }
        return list(
                new TextContentNode("Codex Relay — AI API for Coding"),
                new TextContentNode("\n\n"),
                new TextContentNode("If you're looking for an API provider for AI "
                        + "coding tools, check out Codex Relay."),
                new TextContentNode("\n\n"),
                new TextContentNode("What it offers:"),
                new TextContentNode("\n\n"),
                new BulletListContentNode(null, false, items),
                new TextContentNode("\n\n"),
                new TextContentNode("You can try it out with the free credits "
                        + "before spending anything."),
                new TextContentNode("\n\n"),
                new TextContentNode("Sign up here: "),
                new LinkContentNode(list(new TextContentNode(URL)), new Object()));
    }

    private static final String[] ZH_BULLETS = {
            "支持 Codex CLI、Claude Code 以及其他 AI 编程工具",
            "可使用多种 AI 模型",
            "按使用量计费",
            "自定义 API 密钥和消费限额",
            "通过 Telegram 验证后可获得 R$5 免费额度"
    };

    @Test
    public void reportedMessageHasTenSegments() {
        TextExtractor.Result r = TextExtractor.extract(
                new AnnotatedStructurableText(reportedTree()));

        assertNotNull(r);
        assertEquals(10, r.segmentCount);
        assertTrue("列表符号进入请求正文", r.requestText.contains("• <x1>Support for Codex CLI"));
    }

    @Test
    public void modelKeepingBlankLinesAndDashMarkersTranslatesEveryBullet() {
        StringBuilder t = new StringBuilder();
        t.append("Codex Relay — 面向编程的 AI API\n\n")
                .append("如果你正在为 AI 编程工具寻找 API 提供商，可以看看 Codex Relay。\n\n")
                .append("它提供：\n\n");
        for (int i = 0; i < ZH_BULLETS.length; i++) {
            t.append("- <x").append(i + 1).append('>')
                    .append(ZH_BULLETS[i]).append("</x").append(i + 1).append(">\n");
        }
        t.append("\n<x6>你可以在花任何钱之前先用免费额度试用。</x6>\n\n")
                .append("在此注册：").append(URL);

        List<Object> out = replaceTranslation(reportedTree(), t.toString());

        assertNotNull("结构完整的列表译文必须能回填", out);
        BulletListContentNode bullets = (BulletListContentNode) out.get(6);
        assertEquals("5 个列表项都要拿到自己的译文", 5, bullets.getItems().size());
        for (int i = 0; i < ZH_BULLETS.length; i++) {
            List<Object> item = bullets.getItems().get(i);
            assertEquals("第 " + (i + 1) + " 项译文",
                    ZH_BULLETS[i], ((TextContentNode) item.get(0)).getContent());
        }
        assertEquals("列表后的段落不得并进最后一项", 12, out.size());
        assertEquals("你可以在花任何钱之前先用免费额度试用。",
                ((TextContentNode) out.get(8)).getContent());
    }
    @Test
    public void modelEchoingBulletGlyphDoesNotDuplicateIt() {
        StringBuilder t = new StringBuilder();
        t.append("Codex Relay — 面向编程的 AI API\n\n")
                .append("如果你正在为 AI 编程工具寻找 API 提供商，可以看看 Codex Relay。\n\n")
                .append("它提供：\n\n");
        for (int i = 0; i < ZH_BULLETS.length; i++) {
            t.append("• <x").append(i + 1).append('>')
                    .append(ZH_BULLETS[i]).append("</x").append(i + 1).append(">\n");
        }
        t.append("\n<x6>你可以在花任何钱之前先用免费额度试用。</x6>\n\n")
                .append("在此注册：").append(URL);

        List<Object> out = ContentPlanTestSupport.replace(reportedTree(), t.toString());

        assertNotNull(out);
        BulletListContentNode bullets = (BulletListContentNode) out.get(6);
        for (int i = 0; i < ZH_BULLETS.length; i++) {
            String text = ((TextContentNode) bullets.getItems().get(i).get(0)).getContent();
            assertEquals("回显的符号必须剥离，显示由原节点负责", ZH_BULLETS[i], text);
            assertFalse(text.startsWith("•"));
        }
    }
    @Test
    public void explicitListMarkerAllowsMissingLeadBlankLine() {
        StringBuilder t = new StringBuilder();
        t.append("Codex Relay — 面向编程的 AI API\n\n")
                .append("如果你正在为 AI 编程工具寻找 API 提供商，可以看看 Codex Relay。\n\n")
                .append("它提供：\n");
        for (int i = 0; i < ZH_BULLETS.length; i++) {
            t.append("- <x").append(i + 1).append('>')
                    .append(ZH_BULLETS[i]).append("</x").append(i + 1).append(">\n");
        }
        t.append("\n<x6>你可以在花任何钱之前先用免费额度试用。</x6>\n\n")
                .append("在此注册：").append(URL);

        List<Object> out = ContentPlanTestSupport.replace(reportedTree(), t.toString());
        assertNotNull("列表项标记允许省略列表前的普通空行", out);
        assertEquals(5, ((BulletListContentNode) out.get(6)).getItems().size());
    }
    @Test
    public void listOnlyMessageAlignsEachItem() {
        List<List<Object>> items = new ArrayList<>();
        for (String bullet : BULLETS) {
            items.add(list(new TextContentNode(bullet)));
        }
        List<Object> nodes = list(new BulletListContentNode(null, false, items));

        StringBuilder t = new StringBuilder();
        for (int i = 0; i < ZH_BULLETS.length; i++) {
            t.append("<x").append(i + 1).append('>')
                    .append(ZH_BULLETS[i]).append("</x").append(i + 1).append(">\n");
        }
        List<Object> out = ContentPlanTestSupport.replace(nodes, t.toString());

        assertNotNull(out);
        BulletListContentNode bullets = (BulletListContentNode) out.get(0);
        assertEquals(5, bullets.getItems().size());
        for (int i = 0; i < ZH_BULLETS.length; i++) {
            assertEquals(ZH_BULLETS[i],
                    ((TextContentNode) bullets.getItems().get(i).get(0)).getContent());
        }
    }
    @Test
    public void mergedBulletParagraphKeepsOriginalInsteadOfClearingItems() {
        // 模型把 5 项合并成一行：无法确定每一项的对应关系，整条保留原文。
        // 旧实现按段落级对齐会把整块塞进首项、其余 4 项置空（列表塌成一行）。
        StringBuilder t = new StringBuilder();
        t.append("Codex Relay — 面向编程的 AI API\n\n")
                .append("如果你正在为 AI 编程工具寻找 API 提供商，可以看看 Codex Relay。\n\n")
                .append("<x2>它提供：</x2>\n\n")
                .append("- ").append(String.join("，", ZH_BULLETS)).append("\n\n")
                .append("你可以在花任何钱之前先用免费额度试用。\n\n")
                .append("在此注册：").append(URL);

        List<Object> nodes = reportedTree();
        assertNull(ContentPlanTestSupport.replace(nodes, t.toString()));

        BulletListContentNode bullets = (BulletListContentNode) nodes.get(6);
        assertEquals("原文列表项不得被清空", BULLETS[0],
                ((TextContentNode) bullets.getItems().get(0).get(0)).getContent());
        assertEquals(BULLETS[4],
                ((TextContentNode) bullets.getItems().get(4).get(0)).getContent());
    }

    @Test
    public void urlStaysIntactNextToTranslatedSignupLine() {
        StringBuilder t = new StringBuilder();
        t.append("Codex Relay — 面向编程的 AI API\n\n")
                .append("如果你正在为 AI 编程工具寻找 API 提供商，可以看看 Codex Relay。\n\n")
                .append("它提供：\n\n");
        for (int i = 0; i < ZH_BULLETS.length; i++) {
            t.append("- <x").append(i + 1).append('>')
                    .append(ZH_BULLETS[i]).append("</x").append(i + 1).append(">\n");
        }
        t.append("\n<x6>你可以在花任何钱之前先用免费额度试用。</x6>\n\n")
                .append("在此注册：").append(URL);

        List<Object> nodes = reportedTree();
        Object link = nodes.get(11);
        List<Object> out = ContentPlanTestSupport.replace(nodes, t.toString());

        assertNotNull(out);
        assertSame("URL 链接节点必须原样保留", link, out.get(11));
        assertEquals(URL, ((TextContentNode) ((LinkContentNode) out.get(11))
                .getContent().get(0)).getContent());
        assertEquals("原节点尾随空白属于结构", "在此注册： ",
                ((TextContentNode) out.get(10)).getContent());
    }
    @Test
    public void listItemsSeparatedByBlankParagraphsStillAlignByMarkers() {
        // 独立边界标记已明确每个列表项；模型额外输出空行不应使列表回填失败。
        StringBuilder t = new StringBuilder();
        t.append("Codex Relay — 面向编程的 AI API\n\n")
                .append("如果你正在为 AI 编程工具寻找 API 提供商，可以看看 Codex Relay。\n\n")
                .append("它提供：\n\n");
        for (int i = 0; i < ZH_BULLETS.length; i++) {
            t.append("- <x").append(i + 1).append('>')
                    .append(ZH_BULLETS[i]).append("</x").append(i + 1).append(">\n\n");
        }
        t.append("<x6>你可以在花任何钱之前先用免费额度试用。</x6>\n\n")
                .append("在此注册：").append(URL);

        List<Object> out = ContentPlanTestSupport.replace(reportedTree(), t.toString());
        assertNotNull("各列表项已有独立标记，额外空行不应破坏回填", out);
        assertEquals(5, ((BulletListContentNode) out.get(6)).getItems().size());
    }
    @Test
    public void orderedMarkersInListTranslationAreStripped() {
        // 列表项同样可能是有序列表：模型回显 "1. " 时也要剔除，
        // 否则显示成「• 1. 文本」。
        List<List<Object>> items = new ArrayList<>();
        items.add(list(new TextContentNode("first")));
        items.add(list(new TextContentNode("second")));
        List<Object> nodes = list(new BulletListContentNode(null, false, items));

        List<Object> out = ContentPlanTestSupport.replace(nodes, "1. <x1>第一</x1>\n2. <x2>第二</x2>");

        assertNotNull(out);
        BulletListContentNode bullets = (BulletListContentNode) out.get(0);
        assertEquals("第一",
                ((TextContentNode) bullets.getItems().get(0).get(0)).getContent());
        assertEquals("第二",
                ((TextContentNode) bullets.getItems().get(1).get(0)).getContent());
    }

    @Test
    public void dashAtStartOfPlainParagraphIsNotTreatedAsListMarker() {
        // 不含列表节点的消息里，行首 "- " 是正文内容，不得剥离。
        List<Object> nodes = list(
                new TextContentNode("first paragraph"),
                new TextContentNode("\n\n"),
                new TextContentNode("second paragraph"));

        List<Object> out = ContentPlanTestSupport.replace(nodes, "- 第一段\n\n第二段");

        assertNotNull(out);
        assertEquals("- 第一段", ((TextContentNode) out.get(0)).getContent());
        assertEquals("第二段", ((TextContentNode) out.get(2)).getContent());
    }
    @Test
    public void styledFirstItemConsumesItsBulletBeforeTheOpeningTag() {
        StrongContentNode strong = new StrongContentNode(list(new TextContentNode("Read the guide")));
        BulletListContentNode original = new BulletListContentNode(7L, true,
                Arrays.asList(list(strong)));
        TextExtractor.Result source = capture(list(original), "\n• <x1>Read the guide</x1>");

        List<Object> out = ContentNodes.replacePlanned(source.tree, "• <x1>阅读指南</x1>");

        assertNotNull(out);
        BulletListContentNode rebuilt = (BulletListContentNode) out.get(0);
        assertEquals(Long.valueOf(7L), rebuilt.component1());
        assertTrue(rebuilt.component2());
        StrongContentNode rebuiltStrong = (StrongContentNode) rebuilt.getItems().get(0).get(0);
        assertEquals("阅读指南", ((TextContentNode) rebuiltStrong.getContent().get(0)).getContent());
        assertEquals("\n• <x1>阅读指南</x1>", extracted(out));
        assertSame(strong, original.getItems().get(0).get(0));
        assertEquals("Read the guide", ((TextContentNode) strong.getContent().get(0)).getContent());
        assertNull(ContentNodes.replacePlanned(source.tree, "• 多余正文<x1>阅读指南</x1>"));
    }

    @Test
    public void laterStyledFragmentKeepsLiteralNumberingAfterPlainItemStart() {
        StrongContentNode strong = new StrongContentNode(list(new TextContentNode("1. important thing")));
        TextExtractor.Result source = capture(list(new BulletListContentNode(null, false,
                Arrays.asList(list(new TextContentNode("Read "), strong)))),
                "\n• <x1>Read </x1><x2>1. important thing</x2>");

        List<Object> out = ContentNodes.replacePlanned(source.tree,
                "• <x1>阅读 </x1><x2>1. 重要事项</x2>");

        assertNotNull(out);
        List<Object> item = ((BulletListContentNode) out.get(0)).getItems().get(0);
        assertEquals("阅读 ", ((TextContentNode) item.get(0)).getContent());
        assertEquals("1. 重要事项", ((TextContentNode)
                ((StrongContentNode) item.get(1)).getContent().get(0)).getContent());
        assertEquals("\n• <x1>阅读 </x1><x2>1. 重要事项</x2>", extracted(out));
    }

    @Test
    public void styledFirstItemsPreserveSourceWhitespaceParagraphsAndLaterPlainNumbering() {
        TextContentNode lf = new TextContentNode("\n");
        TextContentNode blank = new TextContentNode("\n\n");
        BulletListContentNode original = new BulletListContentNode(null, false, Arrays.asList(
                list(lf, new StrongContentNode(list(new TextContentNode("  Read\n\n1. guide  ")))),
                list(new StrongContentNode(list(new TextContentNode("Next"))),
                        new TextContentNode(" 1. literal continuation"))));
        List<Object> nodes = list(original, blank, new TextContentNode("After"));
        TextExtractor.Result source = capture(nodes,
                "\n• \n  <x1>Read</x1>\n\n<x2>1. guide</x2>  \n• <x3>Next</x3>"
                        + " 1. literal continuation\n\n<x4>After</x4>");

        List<Object> out = ContentNodes.replacePlanned(source.tree,
                "• \n<x1>阅读</x1>\n\n<x2>1. 指南</x2>\n• <x3>接着</x3>"
                        + " 1. 字面编号\n\n<x4>之后</x4>");

        assertNotNull(out);
        BulletListContentNode rebuilt = (BulletListContentNode) out.get(0);
        assertSame(lf, rebuilt.getItems().get(0).get(0));
        assertEquals("  阅读\n\n1. 指南  ", ((TextContentNode)
                ((StrongContentNode) rebuilt.getItems().get(0).get(1)).getContent().get(0)).getContent());
        assertEquals(" 1. 字面编号", ((TextContentNode) rebuilt.getItems().get(1).get(1)).getContent());
        assertSame(blank, out.get(1));
        assertEquals("之后", ((TextContentNode) out.get(2)).getContent());
        assertEquals("\n• \n  <x1>阅读</x1>\n\n<x2>1. 指南</x2>  \n• <x3>接着</x3>"
                + " 1. 字面编号\n\n<x4>之后</x4>", extracted(out));
    }

    @Test
    public void laterPlainLineAndParagraphKeepLiteralNumberingWithinTheSameItem() {
        TextContentNode lf = new TextContentNode("\n");
        TextExtractor.Result source = capture(list(new BulletListContentNode(null, false,
                Arrays.asList(list(new TextContentNode("Read"), lf,
                        new TextContentNode("1. important\n\n2. still literal")),
                        list(new TextContentNode("Next"))))),
                "\n• <x1>Read</x1>\n1. important\n\n2. still literal\n• <x2>Next</x2>");

        List<Object> out = ContentNodes.replacePlanned(source.tree,
                "<x1>阅读</x1>\n1. 重要事项\n\n2. 仍是正文\n2. <x2>接着</x2>");

        assertNotNull(out);
        BulletListContentNode rebuilt = (BulletListContentNode) out.get(0);
        assertEquals("阅读", ((TextContentNode) rebuilt.getItems().get(0).get(0)).getContent());
        assertSame(lf, rebuilt.getItems().get(0).get(1));
        assertEquals("1. 重要事项\n\n2. 仍是正文",
                ((TextContentNode) rebuilt.getItems().get(0).get(2)).getContent());
        assertEquals("接着", ((TextContentNode) rebuilt.getItems().get(1).get(0)).getContent());
        assertEquals("\n• <x1>阅读</x1>\n1. 重要事项\n\n2. 仍是正文\n• <x2>接着</x2>", extracted(out));
    }
    @Test
    public void retainedFirstItemsConsumePrefixOnceAndKeepLaterLiteralNumbering() {
        for (boolean styled : new boolean[]{false, true}) {
            InlineCodeContentNode code = new InlineCodeContentNode("native-code");
            TextContentNode body = new TextContentNode(" 1. important thing");
            Object last = styled ? new StrongContentNode(list(body)) : body;
            TextExtractor.Result source = capture(list(new BulletListContentNode(null, false,
                    Arrays.asList(list(new TextContentNode("Earlier")), list(code, last)))),
                    "\n• <x1>Earlier</x1>\n• <keep1/> <x2>1. important thing</x2>");

            List<Object> out = ContentNodes.replacePlanned(source.tree,
                    "• <x1>前项</x1>\n• <keep1/> <x2>1. 重要事项</x2>");

            assertNotNull(out);
            BulletListContentNode rebuilt = (BulletListContentNode) out.get(0);
            assertEquals("前项", ((TextContentNode) rebuilt.getItems().get(0).get(0)).getContent());
            assertSame(code, rebuilt.getItems().get(1).get(0));
            Object translated = rebuilt.getItems().get(1).get(1);
            if (styled) translated = ((StrongContentNode) translated).getContent().get(0);
            assertEquals(" 1. 重要事项", ((TextContentNode) translated).getContent());
            assertEquals("\n• <x1>前项</x1>\n• <keep1/> <x2>1. 重要事项</x2>", extracted(out));
            assertEquals(" 1. important thing", body.getContent());
        }
    }
    @Test
    public void plainParagraphMessageStillRejectsMismatchedStructure() {
        // 不含列表的消息保持严格校验：多出来的行不能被塞进节点。
        List<Object> nodes = list(
                new TextContentNode("first paragraph"),
                new TextContentNode("\n\n"),
                new TextContentNode("second paragraph"));

        assertNull(ContentPlanTestSupport.replace(nodes, "一\n二\n三\n四"));
    }

    private static TextExtractor.Result capture(List<Object> nodes, String expected) {
        ContentTree tree = ContentTree.compileForTranslation(nodes);
        assertNotNull("valid synthetic fixture must compile", tree);
        TextExtractor.Result result = TextExtractor.extractTree(tree);
        assertNotNull("compiled source must be extractable", result);
        assertEquals("fixture extraction must match its declared source", expected, result.requestText);
        return result;
    }

    private static String extracted(List<?> nodes) {
        ContentTree tree = ContentTree.compileForTranslation(nodes);
        assertNotNull("rebuilt nodes must compile", tree);
        TextExtractor.Result result = TextExtractor.extractTree(tree);
        assertNotNull("rebuilt nodes must be extractable", result);
        return result.requestText;
    }

    private static List<Object> replaceTranslation(List<?> nodes, String translated) {
        return ContentNodes.replacePlanned(ContentTree.compileForTranslation(nodes), translated);
    }

}
