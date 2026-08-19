package com.llmcopilot.settings;

import com.llmcopilot.settings.LLMCopilotSettings.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the persisted settings contract: shipped defaults and accessor delegation. */
class LLMCopilotSettingsTest {

    @Nested
    @DisplayName("shipped defaults")
    class Defaults {

        private final State state = new State();

        @Test
        void thePluginStartsEnabledAndAutoTriggering() {
            assertTrue(state.enabled);
            assertTrue(state.autoTrigger);
            assertTrue(state.showStatusBar);
        }

        @Test
        void theDefaultProviderIsLocalOllama() {
            assertEquals("ollama", state.provider);
            assertEquals("codellama", state.model);
            assertEquals("http://localhost:11434", state.baseUrl);
        }

        @Test
        void noCredentialsAreShipped() {
            assertEquals("", state.apiKey);
        }

        @Test
        void claudeCodeDefaultsToTheLocalProxyWithPathDiscovery() {
            assertEquals("http://localhost:3000", state.claudeCodeBaseUrl);
            assertEquals("", state.claudeCodeApiPath, "empty path means auto-discovery");
        }

        @Test
        void generationDefaultsFavourShortDeterministicCompletions() {
            assertEquals(256, state.maxTokens);
            assertEquals(0.2, state.temperature, 1e-9);
            assertEquals(50, state.contextLines);
            assertEquals(600, state.debounceMs);
        }
    }

    @Nested
    @DisplayName("state round-trip")
    class RoundTrip {

        @Test
        void loadedStateIsReturnedByGetState() {
            LLMCopilotSettings settings = new LLMCopilotSettings();
            State loaded = new State();
            loaded.provider = "openai";

            settings.loadState(loaded);

            assertSame(loaded, settings.getState());
        }

        @Test
        void accessorsReadFromTheLoadedState() {
            LLMCopilotSettings settings = new LLMCopilotSettings();
            State loaded = new State();
            loaded.enabled = false;
            loaded.provider = "anthropic";
            loaded.model = "claude-sonnet-4";
            loaded.apiKey = "secret";
            loaded.baseUrl = "https://example.test";
            loaded.maxTokens = 1_024;
            loaded.temperature = 0.7;
            loaded.contextLines = 120;
            loaded.debounceMs = 250;
            loaded.autoTrigger = false;
            loaded.testFramework = "JUnit 5";

            settings.loadState(loaded);

            assertFalse(settings.isEnabled());
            assertEquals("anthropic", settings.getProvider());
            assertEquals("claude-sonnet-4", settings.getModel());
            assertEquals("secret", settings.getApiKey());
            assertEquals("https://example.test", settings.getBaseUrl());
            assertEquals(1_024, settings.getMaxTokens());
            assertEquals(0.7, settings.getTemperature(), 1e-9);
            assertEquals(120, settings.getContextLines());
            assertEquals(250, settings.getDebounceMs());
            assertFalse(settings.isAutoTrigger());
            assertEquals("JUnit 5", settings.getTestFramework());
        }
    }
}
