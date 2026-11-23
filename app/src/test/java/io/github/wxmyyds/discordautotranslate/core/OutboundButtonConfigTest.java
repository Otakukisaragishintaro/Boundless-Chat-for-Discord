package io.github.wxmyyds.discordautotranslate.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class OutboundButtonConfigTest {
    @Test
    public void missingJsonMigratesLegacySingleLanguageSetting() {
        List<OutboundButtonConfig> buttons = OutboundButtonConfig.parse(
                null, "日本語", Config.DEF_SEND_SYSTEM_PROMPT);
        assertEquals(1, buttons.size());
        assertEquals("日本語", buttons.get(0).targetLanguage);
        assertEquals("日语发送", buttons.get(0).label);
    }

    @Test
    public void multipleButtonsRoundTripWithIndependentPrompts() {
        List<OutboundButtonConfig> source = new ArrayList<>();
        source.add(OutboundButtonConfig.create("日语发送", "日本語", "prompt-ja"));
        source.add(OutboundButtonConfig.create("英语发送", "English", "prompt-en"));
        source.add(OutboundButtonConfig.create("中文发送", "简体中文", "prompt-zh"));
        String json = OutboundButtonConfig.toJson(source);
        List<OutboundButtonConfig> decoded = OutboundButtonConfig.parse(json, "English", "");
        assertEquals(3, decoded.size());
        assertEquals("prompt-ja", decoded.get(0).systemPrompt);
        assertEquals("prompt-en", decoded.get(1).systemPrompt);
        assertEquals("prompt-zh", decoded.get(2).systemPrompt);
    }

    @Test
    public void requestSnapshotUsesSelectedButtonsLanguageAndPrompt() {
        List<OutboundButtonConfig> buttons = new ArrayList<>();
        buttons.add(OutboundButtonConfig.create("日语发送", "日本語", "Japanese prompt"));
        buttons.add(OutboundButtonConfig.create("英语发送", "English", "English prompt"));
        MemoryPrefs prefs = new MemoryPrefs();
        prefs.put(Config.KEY_OUTBOUND_BUTTONS, OutboundButtonConfig.toJson(buttons));
        Config.Snapshot base = Config.read(prefs);
        Config.Snapshot japanese = base.forSendTranslation(base.outboundButtons.get(0));
        Config.Snapshot english = base.forSendTranslation(base.outboundButtons.get(1));
        assertEquals("日本語", japanese.targetLang);
        assertEquals("Japanese prompt", japanese.systemPrompt);
        assertEquals("English", english.targetLang);
        assertEquals("English prompt", english.systemPrompt);
    }

    @Test
    public void emptyArrayDisablesAllButtons() {
        assertTrue(OutboundButtonConfig.parse("[]", "English",
                Config.DEF_SEND_SYSTEM_PROMPT).isEmpty());
        assertEquals("[]", OutboundButtonConfig.toJson(new ArrayList<>()));
    }

    @Test
    public void rejectsTooManyButtonsAndDuplicateIds() {
        List<OutboundButtonConfig> tooMany = new ArrayList<>();
        for (int i = 0; i <= OutboundButtonConfig.MAX_BUTTONS; i++) {
            tooMany.add(OutboundButtonConfig.create("Button " + i, "English", "prompt"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> OutboundButtonConfig.toJson(tooMany));

        OutboundButtonConfig first = OutboundButtonConfig.create("A", "English", "one");
        List<OutboundButtonConfig> duplicates = new ArrayList<>();
        duplicates.add(first);
        duplicates.add(new OutboundButtonConfig(first.id, "B", "日本語", "two"));
        assertThrows(IllegalArgumentException.class,
                () -> OutboundButtonConfig.toJson(duplicates));
    }
}
