package com.llmcopilot.chat;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.*;
import com.intellij.ui.content.*;
import org.jetbrains.annotations.NotNull;

public class LLMChatToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        LLMChatPanel panel   = new LLMChatPanel(project);
        ContentFactory cf    = ContentFactory.getInstance();
        Content content      = cf.createContent(panel, "", false);
        content.setPreferredFocusableComponent(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
