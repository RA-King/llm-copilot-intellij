# LLM Copilot

An IntelliJ IDEA plugin that brings inline AI code completion, an in-editor chat
panel, and a set of code actions to any LLM you choose — including models running
entirely on your own machine.

Version 2.1.0 · IntelliJ IDEA 2026.1+ · Java 17 · MIT licensed

[![CI](https://github.com/RA-King/llm-copilot-intellij/actions/workflows/ci.yml/badge.svg)](https://github.com/RA-King/llm-copilot-intellij/actions/workflows/ci.yml)

---

## What it does

**Ghost text completions.** As you type, a suggestion appears inline in grey.
Press <kbd>Tab</kbd> to accept it, <kbd>Esc</kbd> to dismiss. Completions are
structure-aware: the plugin scans backwards through braces to work out whether the
caret sits in a class body, an interface, an enum, a function body, or at top
level, and asks the model for the appropriate thing — a constructor for an empty
class, accessors for a class that already has fields, the next case for an enum.

**A duplication guard.** Models love to re-emit code that is already on screen.
Three filters run over every suggestion before you see it: echoed prefixes are
stripped, whole blocks that already exist above or below the caret are rejected,
and individual repeated lines are dropped. If more than half the suggestion is
material you already have, nothing is shown.

**Doc comments on demand.** Type `//` or `/**` on a line of its own directly above
a declaration and the plugin drafts a documentation comment for it. The result is
shown in a preview dialog — nothing is written to your file until you click
**Accept**, and the insert is a single undoable action.

**Chat and code actions.** A tool window on the right for free-form conversation
with the current file as context, plus one-shot actions over a selection: explain,
fix, refactor with an instruction, generate unit tests, generate a constructor,
generate getters and setters one field at a time, implement interface methods, and
write a commit message from the current diff.

---

## Requirements

| | |
|---|---|
| IDE | IntelliJ IDEA 2026.1 or newer (build 261+), Community or Ultimate |
| Java (to build) | JDK 21 or older — supplied automatically, see [Building](#building-from-source) |
| Java (to run) | Whatever your IDE runs on; the plugin targets bytecode 17 |
| An LLM | A local runtime such as Ollama, or an API key for a hosted provider |

---

## Supported LLM providers

Choose one under **Settings → Tools → LLM Copilot**. Thirteen providers are
supported; the first two need no account at all.

| Provider | Endpoint used | Credentials | Notes |
|---|---|---|---|
| `ollama` | `{baseUrl}/api/chat` | none | **Default.** Fully local. |
| `lmstudio` | `{baseUrl}/v1/chat/completions` | optional | Fully local, OpenAI-compatible. |
| `claudecode` | local proxy, auto-discovered | none | Talks to a local proxy — see below. |
| `openai` | `api.openai.com/v1/chat/completions` | `Authorization: Bearer` | |
| `anthropic` | `api.anthropic.com/v1/messages` | `x-api-key` | Sends `anthropic-version: 2023-06-01`. |
| `gemini` | `generativelanguage.googleapis.com/v1beta/openai/...` | `Authorization: Bearer` | Defaults to `gemini-2.5-flash`. |
| `deepseek` | `api.deepseek.com/v1/chat/completions` | `Authorization: Bearer` | Defaults to `deepseek-chat`. |
| `grok` | `api.x.ai/v1/chat/completions` | `Authorization: Bearer` | Defaults to `grok-3-mini`. |
| `mistral` | `api.mistral.ai/v1/chat/completions` | `Authorization: Bearer` | |
| `groq` | `api.groq.com/openai/v1/chat/completions` | `Authorization: Bearer` | |
| `openrouter` | `openrouter.ai/api/v1/chat/completions` | `Authorization: Bearer` | |
| `azure` | `{baseUrl}/chat/completions?api-version=…` | `api-key` | Model comes from the deployment in the URL. |
| `custom` | `{baseUrl}/v1/chat/completions` | optional | Any OpenAI-compatible endpoint. |

For **Azure**, set the base URL to your deployment root:

```
https://{resource}.openai.azure.com/openai/deployments/{deployment}
```

### The `claudecode` provider

This provider targets a locally running proxy rather than a hosted API, and the
community has settled on no single port or path. Rather than make you guess, the
plugin probes a list of known configurations and caches the first that answers:

| Port | Path | Wire format |
|---|---|---|
| 3000 | `/v1/messages` | Anthropic |
| 3000 | `/v1/chat/completions` | OpenAI |
| 3456 | `/v1/chat/completions` | OpenAI |
| 8000 | `/v1/chat/completions` | OpenAI |
| 4141 | `/v1/chat/completions` | OpenAI |
| 8082 | `/v1/messages` | Anthropic |
| 8080 | `/v1/chat/completions` | OpenAI |
| 1234 | `/v1/chat/completions` | OpenAI |
| 11435 | `/v1/chat/completions` | OpenAI |

Leave **API Path** blank to let discovery run. If you know your setup, filling in
the port and path skips probing entirely. The action **LLM Copilot: Test
Connection** reports which combination answered.

---

## Setup

### Install a prebuilt plugin

1. Build the ZIP (below), or take one from a release.
2. **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select `build/distributions/llm-copilot-intellij-2.1.0.zip`
4. Restart the IDE.

### Point it at a model

Open **Settings → Tools → LLM Copilot**.

The shipped defaults assume [Ollama](https://ollama.com) on the same machine, so
if you run:

```bash
ollama pull codellama
ollama serve
```

…there is nothing further to configure. Otherwise pick your provider, enter the
model name and API key, and use **LLM Copilot: Test Connection** to confirm.

### Settings reference

| Setting | Default | Meaning |
|---|---|---|
| Enabled | `true` | Master switch for completions. |
| Provider | `ollama` | One of the providers above. |
| Model | `codellama` | Model name as the provider spells it. |
| API Key | *(blank)* | Not needed for local providers. |
| Base URL | `http://localhost:11434` | Used by local, Azure, and custom providers. |
| Claude Code URL | `http://localhost:3000` | Host for the local proxy. |
| Claude Code API Path | *(blank)* | Blank means auto-discovery. |
| Max tokens | `256` | Completions are meant to be short. |
| Temperature | `0.2` | Low, for predictable code. |
| Context lines | `50` | Lines of file context sent with each request. |
| Debounce | `600` ms | Idle time before a completion is requested. |
| Auto-trigger | `true` | Off means completions only on the shortcut. |
| Show status bar | `true` | Status widget in the bottom bar. |
| Test framework | *(blank)* | Blank lets the model pick per language. |

> **Note on API keys.** Keys are stored in the IDE's plugin settings file
> (`LLMCopilot.xml`) as plain text, not in the OS keychain. Prefer a local
> provider on shared machines.

---

## Using it

### Keyboard shortcuts

These are the bindings the plugin registers in the default keymap. They are
Control-based on every platform; remap them under **Settings → Keymap** if they
collide with your setup.

| Action | Shortcut |
|---|---|
| Accept ghost text | <kbd>Tab</kbd> |
| Dismiss ghost text | <kbd>Esc</kbd> |
| Trigger completion manually | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>Space</kbd> |
| Selection actions menu | <kbd>Ctrl</kbd>+<kbd>Space</kbd> |
| Open chat panel | <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>I</kbd> |
| Inline chat | <kbd>Ctrl</kbd>+<kbd>I</kbd> |
| Explain code | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>E</kbd> |
| Refactor code | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>R</kbd> |
| Generate doc comment | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>D</kbd> |
| Generate unit tests | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>T</kbd> |
| Generate commit message | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>M</kbd> |

<kbd>Tab</kbd> and <kbd>Esc</kbd> only belong to the plugin while a suggestion is
on screen; otherwise they fall through to their normal behaviour.

Fix Code, Generate Constructor, Generate Getters & Setters, Implement Interface,
Toggle Enable/Disable, and Test Connection have no default shortcut. Reach them
from the editor context menu under **LLM Copilot**, or bind them yourself.

### Language support

Completion keyword triggers and comment styles are tuned for TypeScript,
JavaScript, Python, Java, Kotlin, Go, Rust, C/C++, and C#. Other languages still
work — they fall back to generic prompting and `/** … */` comment formatting.

Doc comments are emitted in the idiom of the language: `#` for Python and Ruby,
`///` for Rust, `//` for Go, and `/** … */` elsewhere.

---

## Building from source

```bash
git clone https://github.com/RA-King/llm-copilot-intellij
cd llm-copilot-intellij
chmod +x gradlew
./gradlew buildPlugin
```

Output: `build/distributions/llm-copilot-intellij-2.1.0.zip`

The build compiles against your **locally installed IntelliJ IDEA** rather than
downloading a 1 GB SDK. It probes the usual install locations; if yours is
elsewhere, say so in `gradle.properties`:

```properties
intellijIdeaPath=/Applications/IntelliJ IDEA 2026.1.app
```

…or pass it per-invocation:

```bash
./gradlew buildPlugin -PideaPath="/Applications/IntelliJ IDEA 2026.1.app"
```

### The JDK 21 requirement

The IntelliJ Platform Gradle plugin rejects any JVM newer than 21, and it does so
during settings evaluation — before any task or toolchain configuration can
intervene. The `gradlew` wrapper therefore locates a JDK 21 (IntelliJ ships one)
and runs Gradle on it. **Your system JDK is left alone**, so a machine on Java 25
needs no changes. Override it explicitly if you must:

```properties
org.gradle.java.home=/Applications/IntelliJ IDEA 2026.1.app/Contents/jbr/Contents/Home
```

### Useful tasks

| Task | Purpose |
|---|---|
| `./gradlew compileJava` | Fast type check (about a second when warm). |
| `./gradlew test` | Run the unit test suite. |
| `./gradlew buildPlugin` | Produce the installable ZIP. |
| `./gradlew verifyPlugin` | Run JetBrains' plugin compatibility verifier. |
| `./gradlew clean` | Delete build output. |

### Building without a local IDE

If no IntelliJ installation is found, the build falls back to downloading the
IntelliJ Platform distribution from JetBrains' Maven repository — roughly 1 GB on
first use, cached afterwards. This is how CI builds.

| Property | Effect |
|---|---|
| `-PignoreLocalIde` | Ignore any local install and use the downloaded platform. Reproduces CI locally. |
| `-PplatformVersion=2026.1` | Which platform version to download. Ignored when a local install is used. |

---

## Tests

```bash
./gradlew test
```

70 unit tests cover the logic that does not need a running IDE:

| Suite | What it pins down |
|---|---|
| `DuplicateGuardTest` | All three de-duplication levels, including whitespace-insensitive matching and preservation of trivial lines. |
| `IndentUtilsTest` | Fence and label stripping, relative indent preservation, tab vs. space output. |
| `StructureAnalyzerTest` | Container classification, suggestion selection, caret clamping. |
| `PromptBuilderTest` | Role structure, framework selection, diff truncation, completion-prompt branches. |
| `LLMCopilotSettingsTest` | Shipped defaults and state round-tripping. |

Editor-dependent code is tested through `FakeEditor`, a helper that stubs the few
`Editor` and `Document` methods the production code touches, so the suite runs in
seconds without starting the platform. A `|` in a fixture string marks the caret.

An HTML report lands in `build/reports/tests/test/index.html`.

---

## Project layout

```
src/main/java/com/llmcopilot/
├── completion/   ghost text, doc comments, structure analysis, key handling
├── chat/         tool window, editor context capture, code proposals
├── services/     LLMClient (all provider HTTP), PromptBuilder
├── settings/     persisted state and the settings UI
├── actions/      registered IDE actions
└── ui/           status bar widget
```

---

## Troubleshooting

**No completions appear.** Check the status bar widget is not showing the plugin
as disabled, then run **LLM Copilot: Test Connection**. Remember auto-trigger
waits for a 600 ms pause in typing.

**Suggestions keep getting swallowed.** That is usually the duplication guard
doing its job — it suppresses anything more than half of which already exists
nearby. Try in a genuinely empty region to confirm the pipeline works.

**`claudecode` cannot connect.** Run **LLM Copilot: Test Connection**; it probes
every known port and path and prints exactly which answered, along with the values
to paste into settings.

**The build cannot find IntelliJ IDEA.** Set `intellijIdeaPath` in
`gradle.properties`.

**The build fails complaining about the JDK version.** Something is bypassing the
wrapper's JDK 21 detection. Set `org.gradle.java.home` explicitly.

---

## Continuous integration

Two GitHub Actions workflows live in `.github/workflows`:

| Workflow | Trigger | Does |
|---|---|---|
| `ci.yml` | push / PR to `main` or `develop`, or manually | Validates the wrapper, compiles, runs the tests, builds the ZIP, and uploads the test report and plugin as artifacts. |
| `release.yml` | pushing a `v*` tag, or manually | Runs the tests, builds the ZIP, and publishes a GitHub release with generated notes and the ZIP attached. |

Both run on `ubuntu-latest` with JDK 21 and cache the Gradle home, so the platform
download is paid for once rather than per run. Cutting a release is:

```bash
git tag v2.1.0
git push origin v2.1.0
```

---

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 RA King.
