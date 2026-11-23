package io.github.wxmyyds.discordautotranslate.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.OutboundButtonConfig;

import java.util.ArrayList;
import java.util.List;

/** Dialog-based manager for independent outbound language actions. */
public final class OutboundButtonsEditor {
    public interface Listener {
        void onChanged(List<OutboundButtonConfig> buttons);
    }

    private OutboundButtonsEditor() {}

    public static Dialog show(Activity activity, List<OutboundButtonConfig> initial,
                              String[] languageValues, String[] languageLabels,
                              String defaultPrompt, Listener listener) {
        List<OutboundButtonConfig> draft = new ArrayList<>(initial);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 18), dp(activity, 12),
                dp(activity, 18), dp(activity, 12));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("发送语言按钮管理")
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            content.removeAllViews();
            TextView intro = new TextView(activity);
            intro.setText("每个按钮使用独立的目标语言和提示词。最多 "
                    + OutboundButtonConfig.MAX_BUTTONS + " 个；删除后聊天输入框会同步减少按钮。");
            intro.setTextSize(13f);
            content.addView(intro, match());
            for (int i = 0; i < draft.size(); i++) {
                final int index = i;
                OutboundButtonConfig item = draft.get(i);
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp(activity, 10), 0, dp(activity, 10));
                TextView summary = new TextView(activity);
                summary.setText(item.label + "  ·  " + item.targetLanguage
                        + "\n提示词：" + item.systemPrompt.length() + " 字符");
                summary.setTextSize(14f);
                row.addView(summary, match());
                LinearLayout actions = new LinearLayout(activity);
                Button edit = new Button(activity);
                edit.setText("编辑");
                edit.setOnClickListener(v -> editOne(activity, draft, index,
                        languageValues, languageLabels, defaultPrompt, listener, refresh[0]));
                actions.addView(edit, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                Button delete = new Button(activity);
                delete.setText("删除");
                delete.setOnClickListener(v -> new AlertDialog.Builder(activity)
                        .setTitle("删除发送按钮")
                        .setMessage("确定删除「" + item.label + "」吗？")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("删除", (d, which) -> {
                            draft.remove(index);
                            listener.onChanged(new ArrayList<>(draft));
                            refresh[0].run();
                        }).show());
                actions.addView(delete, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(actions, match());
                content.addView(row, match());
            }
            Button add = new Button(activity);
            add.setText("＋ 添加语言按钮");
            add.setEnabled(draft.size() < OutboundButtonConfig.MAX_BUTTONS);
            add.setOnClickListener(v -> {
                if (draft.size() >= OutboundButtonConfig.MAX_BUTTONS) return;
                editOne(activity, draft, -1, languageValues, languageLabels,
                        defaultPrompt, listener, refresh[0]);
            });
            content.addView(add, match());
            if (draft.isEmpty()) {
                TextView empty = new TextView(activity);
                empty.setText("当前没有发送按钮。添加一个语言按钮后，聊天输入框旁边才会出现翻译发送入口。");
                content.addView(empty, match());
            }
        };
        refresh[0].run();
        dialog.show();
        return dialog;
    }

    private static void editOne(Activity activity, List<OutboundButtonConfig> draft, int index,
                                String[] languageValues, String[] languageLabels,
                                String defaultPrompt, Listener listener, Runnable refresh) {
        boolean adding = index < 0;
        OutboundButtonConfig old = adding ? null : draft.get(index);
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(activity, 18), dp(activity, 8),
                dp(activity, 18), dp(activity, 8));

        TextView labelTitle = new TextView(activity);
        labelTitle.setText("按钮显示名称");
        form.addView(labelTitle, match());
        EditText label = new EditText(activity);
        label.setSingleLine(true);
        label.setHint("例如：日语发送");
        label.setText(adding ? "" : old.label);
        label.setInputType(InputType.TYPE_CLASS_TEXT);
        form.addView(label, match());

        TextView languageTitle = new TextView(activity);
        languageTitle.setText("目标语言");
        form.addView(languageTitle, match());
        Spinner language = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, languageLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        language.setAdapter(adapter);
        int selected = 2;
        if (!adding) {
            for (int i = 0; i < languageValues.length; i++) {
                if (languageValues[i].equals(old.targetLanguage)) selected = i;
            }
        }
        language.setSelection(selected);
        form.addView(language, match());

        final String[] prompt = {adding ? defaultPrompt : old.systemPrompt};
        Button promptButton = new Button(activity);
        promptButton.setText("编辑此按钮的独立提示词");
        promptButton.setOnClickListener(v -> editPrompt(activity, prompt, promptButton));
        form.addView(promptButton, match());

        AlertDialog editor = new AlertDialog.Builder(activity)
                .setTitle(adding ? "添加发送按钮" : "编辑发送按钮")
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("保存", null)
                .create();
        editor.setOnShowListener(d -> editor.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int position = language.getSelectedItemPosition();
                    if (position < 0 || position >= languageValues.length) {
                        Toast.makeText(activity, "请选择目标语言", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String target = languageValues[position];
                    String visibleLabel = label.getText().toString().trim();
                    if (visibleLabel.isEmpty()) {
                        visibleLabel = OutboundButtonConfig.defaultLabel(target);
                    }
                    try {
                        OutboundButtonConfig updated = new OutboundButtonConfig(
                                adding ? java.util.UUID.randomUUID().toString() : old.id,
                                visibleLabel, target, prompt[0]);
                        if (adding) draft.add(updated);
                        else draft.set(index, updated);
                        listener.onChanged(new ArrayList<>(draft));
                        editor.dismiss();
                        refresh.run();
                    } catch (IllegalArgumentException invalid) {
                        Toast.makeText(activity, invalid.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }));
        editor.show();
    }

    private static void editPrompt(Activity activity, String[] prompt, Button owner) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(activity, 18), dp(activity, 8),
                dp(activity, 18), dp(activity, 8));
        EditText input = new EditText(activity);
        input.setMinLines(8);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(prompt[0]);
        layout.addView(input, match());
        Button reset = new Button(activity);
        reset.setText("恢复默认提示词");
        reset.setOnClickListener(v -> input.setText(Config.DEF_SEND_SYSTEM_PROMPT));
        layout.addView(reset, match());
        new AlertDialog.Builder(activity)
                .setTitle("此语言按钮的独立提示词")
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("确定", (d, which) -> {
                    prompt[0] = input.getText().toString();
                    owner.setText("编辑此按钮的独立提示词（" + prompt[0].length() + " 字符）");
                }).show();
    }

    private static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
