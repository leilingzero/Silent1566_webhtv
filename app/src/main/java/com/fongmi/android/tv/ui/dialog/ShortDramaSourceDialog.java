package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.ShortDramaConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ShortDramaSourceDialog {

    private final FragmentActivity activity;
    private AlertDialog dialog;
    private EditText rulesEdit;
    private Runnable onDismiss;

    public static ShortDramaSourceDialog create(FragmentActivity activity) {
        return new ShortDramaSourceDialog(activity);
    }

    private ShortDramaSourceDialog(FragmentActivity activity) {
        this.activity = activity;
    }

    public ShortDramaSourceDialog onDismiss(Runnable callback) {
        this.onDismiss = callback;
        return this;
    }

    public void show() {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_short_drama_source, null);
        rulesEdit = view.findViewById(R.id.rules);
        View manageBtn = view.findViewById(R.id.manage);

        ShortDramaConfig config = ShortDramaConfig.objectFrom(Setting.getShortDramaConfig());
        rulesEdit.setText(config.getDisplayRulesWithNames());
        manageBtn.setOnClickListener(v -> showSiteManage());

        dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.setting_short_drama_source)
                .setView(view)
                .setPositiveButton(R.string.dialog_positive, this::onSave)
                .setNegativeButton(R.string.dialog_negative, null)
                .setNeutralButton(R.string.dialog_short_drama_site_default, (d, w) -> rulesEdit.setText(ShortDramaConfig.defaultRulesText()))
                .setOnDismissListener(d -> { if (onDismiss != null) onDismiss.run(); })
                .create();
        dialog.show();
    }

    private void onSave(DialogInterface d, int which) {
        List<String> rules = extractKeys(rulesEdit.getText().toString());
        String json = "{\"configured\":true,\"enabledSites\":" + toJsonArray(rules) + "}";
        Setting.putShortDramaConfig(ShortDramaConfig.objectFrom(json).toJson());
    }

    private void showSiteManage() {
        List<Site> sites = VodConfig.get().getSites().stream().filter(s -> s != null && !s.isEmpty()).toList();
        if (sites.isEmpty()) return;

        List<String> current = splitRules(rulesEdit.getText().toString());
        String[] labels = new String[sites.size()];
        boolean[] checked = new boolean[sites.size()];

        for (int i = 0; i < sites.size(); i++) {
            Site site = sites.get(i);
            labels[i] = TextUtils.isEmpty(site.getName()) ? site.getKey() : site.getName() + "  " + site.getKey();
            checked[i] = matchesRule(current, site);
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.dialog_short_drama_site_manage)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> {
                    List<String> selected = new ArrayList<>();
                    for (int i = 0; i < sites.size(); i++)
                        if (checked[i]) selected.add(displayName(sites.get(i)));
                    // 合并已有的关键词规则（非站点条目）
                    for (String rule : splitRules(rulesEdit.getText().toString())) {
                        if (findSite(rule) == null && !selected.contains(rule)) selected.add(rule);
                    }
                    rulesEdit.setText(String.join(";", selected));
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private boolean matchesRule(List<String> rules, Site site) {
        String key = site.getKey() == null ? "" : site.getKey().toLowerCase(Locale.ROOT);
        String name = site.getName() == null ? "" : site.getName().toLowerCase(Locale.ROOT);
        for (String rule : rules) {
            if (TextUtils.isEmpty(rule)) continue;
            String r = rule.trim().toLowerCase(Locale.ROOT);
            if (key.equals(r) || name.equals(r)) return true;
            if (key.contains(r) || name.contains(r)) return true;
        }
        return false;
    }

    // 显示文本：站点 key 转为站点名称，关键词原样保留
    private String toDisplayText(List<String> rules) {
        if (rules == null || rules.isEmpty()) return "";
        List<String> display = new ArrayList<>();
        for (String rule : rules) {
            Site site = findSite(rule);
            display.add(site != null ? displayName(site) : rule);
        }
        return String.join(";", display);
    }

    // 保存时：站点名称转回 key，关键词原样保留
    private List<String> extractKeys(String text) {
        List<String> result = new ArrayList<>();
        for (String rule : splitRules(text)) {
            Site site = findSite(rule);
            String value = site != null ? site.getKey() : rule;
            if (!result.contains(value)) result.add(value);
        }
        return result;
    }

    // 按 key 或名称精确查找站点
    private Site findSite(String value) {
        if (TextUtils.isEmpty(value)) return null;
        String target = value.trim();
        for (Site site : VodConfig.get().getSites()) {
            if (site == null || site.isEmpty()) continue;
            if (target.equalsIgnoreCase(site.getKey())) return site;
            if (!TextUtils.isEmpty(site.getName()) && target.equalsIgnoreCase(site.getName())) return site;
        }
        return null;
    }

    private String displayName(Site site) {
        return TextUtils.isEmpty(site.getName()) ? site.getKey() : site.getName();
    }

    private List<String> splitRules(String text) {
        List<String> result = new ArrayList<>();
        if (TextUtils.isEmpty(text)) return result;
        for (String item : text.split("[,，;；\\n]")) {
            String s = item.trim();
            if (!s.isEmpty() && !result.contains(s)) result.add(s);
        }
        return result;
    }

    private String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }
}
