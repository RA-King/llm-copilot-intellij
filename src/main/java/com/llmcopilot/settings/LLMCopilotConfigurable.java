package com.llmcopilot.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.*;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class LLMCopilotConfigurable implements Configurable {

    private JBTextField  fldModel, fldBaseUrl, fldClaudeBaseUrl, fldClaudePath, fldTestFW, fldEnabledLangs;
    private JBTextField  fldApiKey;   // plain text field — JBPasswordField has no int constructor
    private JBCheckBox   chkEnabled, chkAutoTrigger, chkStatusBar, chkPsiContext;
    private JSpinner     spnMaxTokens, spnTemperature, spnDebounce;
    private ComboBox<String> cmbProvider;

    private static final String[] PROVIDERS = {
        "ollama","openai","anthropic","gemini","deepseek","grok",
        "claudecode","mistral","groq","openrouter","lmstudio","azure","custom"
    };

    @Nls @Override public String getDisplayName() { return "LLM Copilot"; }

    @Override public @Nullable JComponent createComponent() {
        cmbProvider      = new ComboBox<>(PROVIDERS);
        fldModel         = new JBTextField(30);
        fldApiKey        = new JBTextField(30);  // Use plain text field
        fldBaseUrl       = new JBTextField(30);
        fldClaudeBaseUrl = new JBTextField(30);
        fldClaudePath    = new JBTextField(30);
        fldTestFW        = new JBTextField(20);
        fldEnabledLangs  = new JBTextField(30);
        chkEnabled       = new JBCheckBox("Enable LLM Copilot");
        chkAutoTrigger   = new JBCheckBox("Auto-trigger completions");
        chkStatusBar     = new JBCheckBox("Show status bar widget");
        chkPsiContext    = new JBCheckBox("Use IDE code analysis for completion context");
        chkPsiContext.setToolTipText(
            "Resolve the enclosing signature and referenced declarations through the language's "
            + "parser, so completions match real types. Results are cached per region; turn off "
            + "for the lowest possible latency.");
        spnMaxTokens     = new JSpinner(new SpinnerNumberModel(256, 10, 4096, 10));
        spnTemperature   = new JSpinner(new SpinnerNumberModel(0.2, 0.0, 2.0, 0.05));
        spnDebounce      = new JSpinner(new SpinnerNumberModel(600, 100, 3000, 100));
        ((JSpinner.NumberEditor) spnTemperature.getEditor()).getFormat().setMaximumFractionDigits(2);

        JPanel panel = FormBuilder.createFormBuilder()
            .addComponent(chkEnabled)
            .addSeparator()
            .addLabeledComponent("Provider:",           cmbProvider)
            .addLabeledComponent("Model:",              fldModel)
            .addLabeledComponent("API Key:",            fldApiKey)
            .addLabeledComponent("Base URL:",           fldBaseUrl)
            .addSeparator()
            .addComponent(new JBLabel("<html><b>Claude Code</b></html>"))
            .addLabeledComponent("Claude Code URL:",    fldClaudeBaseUrl)
            .addLabeledComponent("API Path override:",  fldClaudePath)
            .addComponent(new JBLabel("<html><i>e.g. /v1/messages or /v1/chat/completions</i></html>"))
            .addSeparator()
            .addLabeledComponent("Max tokens:",         spnMaxTokens)
            .addLabeledComponent("Temperature:",        spnTemperature)
            .addLabeledComponent("Debounce (ms):",      spnDebounce)
            .addSeparator()
            .addComponent(chkAutoTrigger)
            .addComponent(chkPsiContext)
            .addComponent(chkStatusBar)
            .addSeparator()
            .addLabeledComponent("Test framework:",     fldTestFW)
            .addLabeledComponent("Enabled languages:",  fldEnabledLangs)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();

        return new JScrollPane(panel);
    }

    @Override public boolean isModified() {
        LLMCopilotSettings s = LLMCopilotSettings.getInstance();
        LLMCopilotSettings.State st = s.myState;
        return st.enabled          != chkEnabled.isSelected()
            || !st.provider.equals(cmbProvider.getItem())
            || !st.model.equals(fldModel.getText())
            || !st.apiKey.equals(fldApiKey.getText())
            || !st.baseUrl.equals(fldBaseUrl.getText())
            || !st.claudeCodeBaseUrl.equals(fldClaudeBaseUrl.getText())
            || !st.claudeCodeApiPath.equals(fldClaudePath.getText())
            || st.maxTokens        != (int)    spnMaxTokens.getValue()
            || st.temperature      != (double) spnTemperature.getValue()
            || st.debounceMs       != (int)    spnDebounce.getValue()
            || st.autoTrigger      != chkAutoTrigger.isSelected()
            || st.psiContext       != chkPsiContext.isSelected()
            || st.showStatusBar    != chkStatusBar.isSelected()
            || !st.testFramework.equals(fldTestFW.getText())
            || !st.enabledLanguages.equals(fldEnabledLangs.getText());
    }

    @Override public void apply() {
        LLMCopilotSettings.State st = LLMCopilotSettings.getInstance().myState;
        st.enabled          = chkEnabled.isSelected();
        st.provider         = (String) cmbProvider.getItem();
        st.model            = fldModel.getText().trim();
        st.apiKey           = fldApiKey.getText().trim();
        st.baseUrl          = fldBaseUrl.getText().trim();
        st.claudeCodeBaseUrl= fldClaudeBaseUrl.getText().trim();
        st.claudeCodeApiPath= fldClaudePath.getText().trim();
        st.maxTokens        = (int)    spnMaxTokens.getValue();
        st.temperature      = (double) spnTemperature.getValue();
        st.debounceMs       = (int)    spnDebounce.getValue();
        st.autoTrigger      = chkAutoTrigger.isSelected();
        st.psiContext       = chkPsiContext.isSelected();
        st.showStatusBar    = chkStatusBar.isSelected();
        st.testFramework    = fldTestFW.getText().trim();
        st.enabledLanguages = fldEnabledLangs.getText().trim();
    }

    @Override public void reset() {
        LLMCopilotSettings.State st = LLMCopilotSettings.getInstance().myState;
        chkEnabled.setSelected(st.enabled);
        cmbProvider.setItem(st.provider);
        fldModel.setText(st.model);
        fldApiKey.setText(st.apiKey);
        fldBaseUrl.setText(st.baseUrl);
        fldClaudeBaseUrl.setText(st.claudeCodeBaseUrl);
        fldClaudePath.setText(st.claudeCodeApiPath);
        spnMaxTokens.setValue(st.maxTokens);
        spnTemperature.setValue(st.temperature);
        spnDebounce.setValue(st.debounceMs);
        chkAutoTrigger.setSelected(st.autoTrigger);
        chkPsiContext.setSelected(st.psiContext);
        chkStatusBar.setSelected(st.showStatusBar);
        fldTestFW.setText(st.testFramework);
        fldEnabledLangs.setText(st.enabledLanguages);
    }
}
