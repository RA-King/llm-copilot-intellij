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
        /** Resolve types and signatures through the language's parser before completing. */
        public boolean psiContext       = true;
        /** Read what the code so far is working towards and tell the model about it. */
        public boolean intentInference  = true;
        public boolean showStatusBar    = true;
        /** Watch run, debug and terminal consoles for failures worth offering help with. */
        public boolean errorAssistEnabled   = true;
        /** Raise a notification the moment something fails, rather than waiting to be asked. */
        public boolean errorAssistAutoOffer = true;
        /** How many candidate fixes the error pane lists. */
        public int     errorSolutionCount   = 4;
        /** Lines of source read around each failing line and sent with the error. */
        public int     errorContextLines    = 40;
        /** Most lines of captured output kept from one failure. */
        public int     errorMaxOutputLines  = 120;
        /** Send the error together with what the rest of the project says about it. */
        public boolean errorDeepContext     = true;
        /** Characters of project context sent with an error. */
        public int     errorProjectChars    = 4000;

        // ── Ghost text ───────────────────────────────────────────────────────
        /** Set the wait from measured latency instead of always using debounceMs. */
        public boolean adaptiveDebounce   = true;
        /** Shortest wait the adaptive debounce will settle on; debounceMs is the longest. */
        public int     minDebounceMs      = 150;
        /** Most lines a statement-sized suggestion may occupy. */
        public int     maxStatementLines  = 3;
        /** Most lines a block-sized suggestion may occupy. */
        public int     maxBlockLines      = 12;
        /** How much of an identifier must be typed before a suggestion is worth it. */
        public int     minIdentifierChars = 2;

        // ── Project index ────────────────────────────────────────────────────
        /** Read the whole project's declarations and imports so answers can use them. */
        public boolean projectIndexEnabled    = true;
        public int     projectIndexMaxFiles   = 4000;
        public int     projectIndexMaxFileKb  = 256;
        /** Characters of project context sent with each completion. */
        public int     projectCompletionChars = 2400;

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
    public boolean isPsiContext()         { return myState.psiContext; }
    public void    setPsiContext(boolean v) { myState.psiContext = v; }
    public boolean isIntentInference()      { return myState.intentInference; }
    public void    setIntentInference(boolean v) { myState.intentInference = v; }
    public String  getTestFramework()     { return myState.testFramework; }
    public boolean isErrorAssistEnabled()   { return myState.errorAssistEnabled; }
    public void    setErrorAssistEnabled(boolean v)   { myState.errorAssistEnabled = v; }
    public boolean isErrorAssistAutoOffer() { return myState.errorAssistAutoOffer; }
    public void    setErrorAssistAutoOffer(boolean v) { myState.errorAssistAutoOffer = v; }
    public int     getErrorSolutionCount()  { return myState.errorSolutionCount; }
    public int     getErrorContextLines()   { return myState.errorContextLines; }
    public int     getErrorMaxOutputLines() { return myState.errorMaxOutputLines; }
    public boolean isErrorDeepContext()     { return myState.errorDeepContext; }
    public void    setErrorDeepContext(boolean v) { myState.errorDeepContext = v; }
    public int     getErrorProjectChars()   { return myState.errorProjectChars; }

    public boolean isAdaptiveDebounce()     { return myState.adaptiveDebounce; }
    public void    setAdaptiveDebounce(boolean v) { myState.adaptiveDebounce = v; }
    public int     getMinDebounceMs()       { return myState.minDebounceMs; }
    public int     getMaxStatementLines()   { return myState.maxStatementLines; }
    public int     getMaxBlockLines()       { return myState.maxBlockLines; }
    public int     getMinIdentifierChars()  { return myState.minIdentifierChars; }

    public boolean isProjectIndexEnabled()  { return myState.projectIndexEnabled; }
    public void    setProjectIndexEnabled(boolean v) { myState.projectIndexEnabled = v; }
    public int     getProjectIndexMaxFiles()      { return myState.projectIndexMaxFiles; }
    public int     getProjectIndexMaxFileSizeKb() { return myState.projectIndexMaxFileKb; }
    public int     getProjectCompletionChars()    { return myState.projectCompletionChars; }
}
