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
    private JBCheckBox   chkEnabled, chkAutoTrigger, chkStatusBar, chkPsiContext, chkIntentInference;
    private JBCheckBox   chkErrorAssist, chkErrorAutoOffer, chkErrorDeepContext;
    private JBCheckBox   chkAdaptiveDebounce, chkProjectIndex;
    private JSpinner     spnMaxTokens, spnTemperature, spnDebounce, spnErrorSolutions, spnErrorContext;
    private JSpinner     spnMinDebounce, spnStatementLines, spnBlockLines, spnMinIdentifier;
    private JSpinner     spnIndexMaxFiles, spnIndexMaxFileKb, spnProjectChars;
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
        chkIntentInference = new JBCheckBox("Infer what the code so far is working towards");
        chkIntentInference.setToolTipText(
            "<html>Reads the job in the enclosing declaration's name, the parameters and locals "
          + "nothing has used yet, the block the caret sits in and an unsatisfied return type, "
          + "then tells the model what the next statement most likely does.</html>");
        chkPsiContext    = new JBCheckBox("Use IDE code analysis for completion context");
        chkPsiContext.setToolTipText(
            "Resolve the enclosing signature and referenced declarations through the language's "
            + "parser, so completions match real types. Results are cached per region; turn off "
            + "for the lowest possible latency.");
        chkErrorAssist   = new JBCheckBox("Offer solutions for run, debug and terminal errors");
        chkErrorAssist.setToolTipText(
            "Reads a failed run or a selected error, resolves the files its trace names, and lists "
            + "candidate fixes. Choosing one carries the error and its code into the chat window.");
        chkErrorAutoOffer = new JBCheckBox("Notify as soon as something fails");
        chkErrorAutoOffer.setToolTipText(
            "Off: reach the pane from the console context menu or Ctrl+Alt+E instead.");
        chkErrorDeepContext = new JBCheckBox("Send what the rest of the project says about the error");
        chkErrorDeepContext.setToolTipText(
            "<html>Looks up where the names in the failure are declared, lists the files that import "
          + "the failing one, and attaches the project manifest, so the answer is written against the "
          + "real signatures instead of plausible ones. Needs the project index.</html>");
        spnErrorSolutions = new JSpinner(new SpinnerNumberModel(4, 2, 8, 1));
        spnErrorContext   = new JSpinner(new SpinnerNumberModel(40, 10, 200, 5));

        chkAdaptiveDebounce = new JBCheckBox("Set the wait from how fast the model actually answers");
        chkAdaptiveDebounce.setToolTipText(
            "<html>A fixed debounce is a guess at a number that depends entirely on the model behind "
          + "it. With this on, the wait moves between the minimum below and the debounce above, "
          + "based on the median round trip measured so far.</html>");
        spnMinDebounce    = new JSpinner(new SpinnerNumberModel(150, 0, 2000, 25));
        spnStatementLines = new JSpinner(new SpinnerNumberModel(3, 1, 40, 1));
        spnBlockLines     = new JSpinner(new SpinnerNumberModel(12, 1, 80, 1));
        spnMinIdentifier  = new JSpinner(new SpinnerNumberModel(2, 0, 8, 1));

        chkProjectIndex   = new JBCheckBox("Index the whole project");
        chkProjectIndex.setToolTipText(
            "<html>Reads every declaration and import in the project once, in the background, so a "
          + "completion or an error answer can use the real signature of anything in the codebase. "
          + "Tools \u2192 LLM Copilot: Project Index Status shows what it holds.</html>");
        spnIndexMaxFiles  = new JSpinner(new SpinnerNumberModel(4000, 100, 50000, 100));
        spnIndexMaxFileKb = new JSpinner(new SpinnerNumberModel(256, 16, 4096, 16));
        spnProjectChars   = new JSpinner(new SpinnerNumberModel(2400, 0, 20000, 200));
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
            .addComponent(chkIntentInference)
            .addComponent(chkStatusBar)
            .addSeparator()
            .addComponent(new JBLabel("<html><b>Ghost text</b></html>"))
            .addComponent(chkAdaptiveDebounce)
            .addLabeledComponent("Shortest wait (ms):",   spnMinDebounce)
            .addLabeledComponent("Max statement lines:",  spnStatementLines)
            .addLabeledComponent("Max block lines:",      spnBlockLines)
            .addLabeledComponent("Min identifier chars:", spnMinIdentifier)
            .addSeparator()
            .addComponent(new JBLabel("<html><b>Project index</b></html>"))
            .addComponent(chkProjectIndex)
            .addLabeledComponent("Max files:",            spnIndexMaxFiles)
            .addLabeledComponent("Max file size (KB):",   spnIndexMaxFileKb)
            .addLabeledComponent("Chars per completion:", spnProjectChars)
            .addSeparator()
            .addComponent(new JBLabel("<html><b>Error assistance</b></html>"))
            .addComponent(chkErrorAssist)
            .addComponent(chkErrorAutoOffer)
            .addComponent(chkErrorDeepContext)
            .addLabeledComponent("Candidate fixes:",    spnErrorSolutions)
            .addLabeledComponent("Source context lines:", spnErrorContext)
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
            || st.intentInference  != chkIntentInference.isSelected()
            || st.showStatusBar    != chkStatusBar.isSelected()
            || st.errorAssistEnabled   != chkErrorAssist.isSelected()
            || st.errorAssistAutoOffer != chkErrorAutoOffer.isSelected()
            || st.errorSolutionCount   != (int) spnErrorSolutions.getValue()
            || st.errorContextLines    != (int) spnErrorContext.getValue()
            || st.errorDeepContext     != chkErrorDeepContext.isSelected()
            || st.adaptiveDebounce     != chkAdaptiveDebounce.isSelected()
            || st.minDebounceMs        != (int) spnMinDebounce.getValue()
            || st.maxStatementLines    != (int) spnStatementLines.getValue()
            || st.maxBlockLines        != (int) spnBlockLines.getValue()
            || st.minIdentifierChars   != (int) spnMinIdentifier.getValue()
            || st.projectIndexEnabled  != chkProjectIndex.isSelected()
            || st.projectIndexMaxFiles != (int) spnIndexMaxFiles.getValue()
            || st.projectIndexMaxFileKb != (int) spnIndexMaxFileKb.getValue()
            || st.projectCompletionChars != (int) spnProjectChars.getValue()
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
        st.intentInference  = chkIntentInference.isSelected();
        st.showStatusBar    = chkStatusBar.isSelected();
        st.errorAssistEnabled   = chkErrorAssist.isSelected();
        st.errorAssistAutoOffer = chkErrorAutoOffer.isSelected();
        st.errorSolutionCount   = (int) spnErrorSolutions.getValue();
        st.errorContextLines    = (int) spnErrorContext.getValue();
        st.errorDeepContext     = chkErrorDeepContext.isSelected();
        st.adaptiveDebounce     = chkAdaptiveDebounce.isSelected();
        st.minDebounceMs        = (int) spnMinDebounce.getValue();
        st.maxStatementLines    = (int) spnStatementLines.getValue();
        st.maxBlockLines        = (int) spnBlockLines.getValue();
        st.minIdentifierChars   = (int) spnMinIdentifier.getValue();
        st.projectIndexEnabled  = chkProjectIndex.isSelected();
        st.projectIndexMaxFiles = (int) spnIndexMaxFiles.getValue();
        st.projectIndexMaxFileKb = (int) spnIndexMaxFileKb.getValue();
        st.projectCompletionChars = (int) spnProjectChars.getValue();
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
        chkIntentInference.setSelected(st.intentInference);
        chkStatusBar.setSelected(st.showStatusBar);
        chkErrorAssist.setSelected(st.errorAssistEnabled);
        chkErrorAutoOffer.setSelected(st.errorAssistAutoOffer);
        spnErrorSolutions.setValue(st.errorSolutionCount);
        spnErrorContext.setValue(st.errorContextLines);
        chkErrorDeepContext.setSelected(st.errorDeepContext);
        chkAdaptiveDebounce.setSelected(st.adaptiveDebounce);
        spnMinDebounce.setValue(st.minDebounceMs);
        spnStatementLines.setValue(st.maxStatementLines);
        spnBlockLines.setValue(st.maxBlockLines);
        spnMinIdentifier.setValue(st.minIdentifierChars);
        chkProjectIndex.setSelected(st.projectIndexEnabled);
        spnIndexMaxFiles.setValue(st.projectIndexMaxFiles);
        spnIndexMaxFileKb.setValue(st.projectIndexMaxFileKb);
        spnProjectChars.setValue(st.projectCompletionChars);
        fldTestFW.setText(st.testFramework);
        fldEnabledLangs.setText(st.enabledLanguages);
    }
}
