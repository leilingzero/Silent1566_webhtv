package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.AiConfig;
import com.fongmi.android.tv.service.AiRecommendationService;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class AiConfigDialog {

    private static final String[] PROTOCOL_VALUES = {
            AiConfig.PROTOCOL_OPENAI_RESPONSES,
            AiConfig.PROTOCOL_OPENAI_CHAT,
            AiConfig.PROTOCOL_ANTHROPIC_MESSAGES,
            AiConfig.PROTOCOL_GEMINI_NATIVE
    };

    private final FragmentActivity activity;
    private Runnable onDismiss;
    private AiConfig config;

    private SwitchMaterial enabled;
    private AutoCompleteTextView protocol;
    private TextInputEditText endpoint;
    private TextInputEditText apiKey;
    private AutoCompleteTextView model;
    private TextInputEditText userAgent;
    private MaterialButton fetchModels;

    public static AiConfigDialog create(FragmentActivity activity) {
        return new AiConfigDialog(activity);
    }

    private AiConfigDialog(FragmentActivity activity) {
        this.activity = activity;
    }

    public AiConfigDialog onDismiss(Runnable callback) {
        this.onDismiss = callback;
        return this;
    }

    public void show() {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_ai_config, null);
        enabled = view.findViewById(R.id.enabled);
        protocol = view.findViewById(R.id.protocol);
        endpoint = view.findViewById(R.id.endpoint);
        apiKey = view.findViewById(R.id.apiKey);
        model = view.findViewById(R.id.model);
        userAgent = view.findViewById(R.id.userAgent);
        fetchModels = view.findViewById(R.id.fetchModels);
        View prompt = view.findViewById(R.id.prompt);
        MaterialButton test = view.findViewById(R.id.test);

        config = AiConfig.objectFrom(Setting.getAiConfig());
        setupProtocolDropdown();
        setupModelDropdown(new ArrayList<>());
        enabled.setChecked(config.isEnabled());
        protocol.setText(protocolLabel(config.getProtocol()), false);
        endpoint.setText(config.getEndpoint());
        endpoint.setHint(AiConfig.defaultEndpoint(config.getProtocol()));
        apiKey.setText(config.getApiKey());
        model.setText(config.getModel());
        userAgent.setText(config.getCustomUserAgent());
        prompt.setOnClickListener(v -> showPromptConfig());
        fetchModels.setOnClickListener(v -> fetchModels(fetchModels));
        test.setOnClickListener(v -> testConfig(test));

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.setting_ai_recommendation)
                .setView(view)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> onSave())
                .setNegativeButton(R.string.dialog_negative, null)
                .setOnDismissListener(d -> { if (onDismiss != null) onDismiss.run(); })
                .show();
    }

    private void showPromptConfig() {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_ai_prompt_config, null);
        TextInputEditText recommendPrompt = view.findViewById(R.id.recommendPrompt);
        recommendPrompt.setText(config.getRecommendPrompt());
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.dialog_ai_prompt_config)
                .setView(view)
                .setPositiveButton(R.string.dialog_positive, null)
                .setNegativeButton(R.string.dialog_negative, null)
                .setNeutralButton(R.string.dialog_ai_prompt_reset, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                config.setRecommendPrompt(text(recommendPrompt));
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                recommendPrompt.setText(AiConfig.DEFAULT_RECOMMEND_PROMPT);
                recommendPrompt.setSelection(recommendPrompt.length());
            });
        });
        dialog.show();
    }

    private void setupProtocolDropdown() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_dropdown_item_1line, protocolLabels());
        protocol.setAdapter(adapter);
        protocol.setThreshold(0);
        protocol.setOnClickListener(v -> protocol.showDropDown());
        protocol.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) protocol.showDropDown(); });
        protocol.setOnItemClickListener((parent, view, position, id) -> {
            String oldProtocol = config.getProtocol();
            String selected = position >= 0 && position < PROTOCOL_VALUES.length ? PROTOCOL_VALUES[position] : AiConfig.DEFAULT_PROTOCOL;
            config.setProtocol(selected);
            endpoint.setHint(AiConfig.defaultEndpoint(selected));
            String currentEndpoint = text(endpoint);
            if (currentEndpoint.isEmpty() || currentEndpoint.equals(AiConfig.defaultEndpoint(oldProtocol))) {
                endpoint.setText(AiConfig.defaultEndpoint(selected));
            }
        });
    }

    private void setupModelDropdown(List<AiRecommendationService.ModelInfo> models) {
        List<String> values = new ArrayList<>();
        for (AiRecommendationService.ModelInfo item : models) if (!item.getId().isEmpty()) values.add(item.getId());
        model.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_dropdown_item_1line, values));
        model.setThreshold(0);
        model.setOnClickListener(v -> {
            if (model.getAdapter() != null && model.getAdapter().getCount() > 0) model.showDropDown();
        });
        model.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && model.getAdapter() != null && model.getAdapter().getCount() > 0) model.showDropDown();
        });
    }

    private void onSave() {
        readConfigFromInput();
        Setting.putAiConfig(config.toJson());
    }

    private void testConfig(MaterialButton button) {
        readConfigFromInput();
        button.setEnabled(false);
        Notify.show(R.string.dialog_ai_test_running);
        Task.execute(() -> {
            AiRecommendationService.TestResult result = AiRecommendationService.testConfig(config);
            activity.runOnUiThread(() -> {
                button.setEnabled(true);
                if (result.isSuccess()) {
                    Notify.show(activity.getString(R.string.dialog_ai_test_success, result.getCount(), result.getSampleTitle()));
                } else {
                    Notify.show(activity.getString(R.string.dialog_ai_test_failed, result.getMessage()));
                }
            });
        });
    }

    private void fetchModels(MaterialButton button) {
        readConfigFromInput();
        button.setEnabled(false);
        Notify.show(R.string.dialog_ai_fetch_models_running);
        Task.execute(() -> {
            AiRecommendationService.ModelFetchResult result = AiRecommendationService.fetchModels(config);
            activity.runOnUiThread(() -> {
                button.setEnabled(true);
                if (!result.isSuccess()) {
                    Notify.show(activity.getString(R.string.dialog_ai_fetch_models_failed, result.getMessage()));
                    return;
                }
                if (result.getModels().isEmpty()) {
                    Notify.show(R.string.dialog_ai_fetch_models_empty);
                    return;
                }
                setupModelDropdown(result.getModels());
                Notify.show(activity.getString(R.string.dialog_ai_fetch_models_success, result.getModels().size()));
                model.requestFocus();
                model.showDropDown();
            });
        });
    }

    private void readConfigFromInput() {
        config.setEnabled(enabled.isChecked());
        config.setProtocol(selectedProtocol());
        config.setEndpoint(text(endpoint));
        config.setApiKey(text(apiKey));
        config.setModel(text(model));
        config.setCustomUserAgent(text(userAgent));
    }

    private String selectedProtocol() {
        String value = text(protocol);
        String[] labels = protocolLabels();
        for (int i = 0; i < labels.length && i < PROTOCOL_VALUES.length; i++) {
            if (labels[i].equals(value)) return PROTOCOL_VALUES[i];
        }
        return AiConfig.isSupportedProtocol(value) ? value : config.getProtocol();
    }

    private String[] protocolLabels() {
        return activity.getResources().getStringArray(R.array.dialog_ai_protocol_labels);
    }

    private String protocolLabel(String value) {
        String[] labels = protocolLabels();
        for (int i = 0; i < PROTOCOL_VALUES.length && i < labels.length; i++) {
            if (PROTOCOL_VALUES[i].equals(value)) return labels[i];
        }
        return labels.length > 0 ? labels[0] : AiConfig.PROTOCOL_OPENAI_RESPONSES;
    }

    private static String text(TextView input) {
        return input == null || input.getText() == null ? "" : input.getText().toString().trim();
    }
}
