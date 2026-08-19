package com.llmcopilot.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "LLMCopilotSettings", storages = @Storage("LLMCopilot.xml"))
public class LLMCopilotSettings implements PersistentStateComponent<LLMCopilotSettings.State> {

    public static class State {
        public boolean enabled          = true;
        public String  provider         = "ollama";
        public String  model            = "codellama";
        public String  apiKey           = "";
        public String  baseUrl          = "http://localhost:11434";
        public String  azureApiVersion  = "2024-12-01-preview";
        public String  claudeCodeBaseUrl= "http://localhost:3000";
        public String  claudeCodeApiPath= "";
        public int     maxTokens        = 256;
        public double  temperature      = 0.2;
        public int     contextLines     = 50;
        public int     debounceMs       = 600;
        public boolean autoTrigger      = true;
        public boolean showStatusBar    = true;
        public String  testFramework    = "";
        public String  enabledLanguages = "";
    }

    // Make state package-visible so Configurable can access it directly
    State myState = new State();

    public static LLMCopilotSettings getInstance() {
        return ApplicationManager.getApplication().getService(LLMCopilotSettings.class);
    }

    @Override public @Nullable State getState()                    { return myState; }
    @Override public void           loadState(@NotNull State state){ this.myState = state; }

    public boolean isEnabled()            { return myState.enabled; }
    public void    setEnabled(boolean v)  { myState.enabled = v; }
    public String  getProvider()          { return myState.provider; }
    public String  getModel()             { return myState.model; }
    public String  getApiKey()            { return myState.apiKey; }
    public String  getBaseUrl()           { return myState.baseUrl; }
    public String  getClaudeCodeBaseUrl() { return myState.claudeCodeBaseUrl; }
    public String  getClaudeCodeApiPath() { return myState.claudeCodeApiPath; }
    public int     getMaxTokens()         { return myState.maxTokens; }
    public double  getTemperature()       { return myState.temperature; }
    public int     getContextLines()      { return myState.contextLines; }
    public int     getDebounceMs()        { return myState.debounceMs; }
    public boolean isAutoTrigger()        { return myState.autoTrigger; }
    public String  getTestFramework()     { return myState.testFramework; }
}
