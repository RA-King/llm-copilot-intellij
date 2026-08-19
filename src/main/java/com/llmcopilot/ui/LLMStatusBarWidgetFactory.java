package com.llmcopilot.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.*;
import com.intellij.util.Consumer;           // IntelliJ's own Consumer — NOT java.util.function
import com.llmcopilot.settings.LLMCopilotSettings;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;

/**
 * Status bar widget — shows active provider/model, click opens settings.
 * Compatible with IntelliJ 2026.1 (build 261).
 *
 * getClickConsumer() must return com.intellij.util.Consumer<MouseEvent>,
 * NOT java.util.function.Consumer — these are different types.
 */
public class LLMStatusBarWidgetFactory implements StatusBarWidgetFactory {

    @Override public @NotNull String  getId()          { return "LLMCopilotStatusBar"; }
    @Override public @NotNull String  getDisplayName() { return "LLM Copilot"; }
    @Override public          boolean isAvailable(@NotNull Project p) { return true; }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new StatusBarWidget() {

            @Override public @NotNull String ID() { return "LLMCopilotStatusBar"; }

            @Override
            public WidgetPresentation getPresentation() {
                return new StatusBarWidget.TextPresentation() {

                    @Override
                    public @NotNull String getText() {
                        LLMCopilotSettings s = LLMCopilotSettings.getInstance();
                        return s.isEnabled()
                            ? "LLM \u25C9 " + s.getProvider() + "/" + s.getModel()
                            : "LLM \u25CB";
                    }

                    @Override
                    public float getAlignment() { return 0.0f; }

                    @Override
                    public String getTooltipText() {
                        LLMCopilotSettings s = LLMCopilotSettings.getInstance();
                        return s.isEnabled()
                            ? "LLM Copilot \u2014 " + s.getProvider() + "/" + s.getModel()
                              + "  |  Click to open settings"
                            : "LLM Copilot disabled  |  Click to open settings";
                    }

                    // Return com.intellij.util.Consumer<MouseEvent> — the type
                    // declared by WidgetPresentation.getClickConsumer()
                    @Override
                    public Consumer<MouseEvent> getClickConsumer() {
                        return event -> com.intellij.openapi.options.ShowSettingsUtil
                            .getInstance()
                            .showSettingsDialog(project, "LLM Copilot");
                    }
                };
            }

            @Override public void install(@NotNull StatusBar statusBar) {}
            @Override public void dispose() {}
        };
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        Disposer.dispose(widget);
    }

    // canBeEnabledOn was removed in 2025+ platform; kept as non-@Override
    // for backward compat with older IntelliJ versions.
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) { return true; }
}
