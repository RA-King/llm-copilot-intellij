# LLM Copilot

An IntelliJ IDEA plugin that brings inline AI code completion, an in-editor chat
panel, and a set of code actions to any LLM you choose — including models running
entirely on your own machine.

Version 0.0.01 · IntelliJ IDEA 2026.1+ · Java 17 · MIT licensed

[![CI](https://github.com/RA-King/llm-copilot-intellij/actions/workflows/ci.yml/badge.svg)](https://github.com/RA-King/llm-copilot-intellij/actions/workflows/ci.yml)

---

## What it does

**Ghost text completions.** As you type, a suggestion appears inline in grey.
Press <kbd>Tab</kbd> to accept it, <kbd>Esc</kbd> to dismiss. Completions are
structure-aware: the plugin scans backwards through braces to work out whether the
caret sits in a class body, an interface, an enum, a function body, or at top
level, and asks the model for the appropriate thing — a constructor for an empty
class, accessors for a class that already has fields, the next case for an enum.

**Out of the way by default.** Ghost text is unasked-for text on the screen, in
the middle of writing. What makes it tolerable is not how good the suggestions
are, it is how reliably it refuses to appear where it would be in the way. One
gate decides, so the debounce timer and the trigger logic always agree:

| It stays quiet when | Why |
|---|---|
| Real code follows the caret on the line | Accepting would delete what you have already written. Whitespace and closing delimiters don't count — finishing an argument list from inside its own brackets is the normal case |
| You are deleting, undoing or pasting | You are removing something; answering with more is the worst case for intrusiveness |
| The caret is inside a string or a comment | Code completion there is noise |
| You just pressed <kbd>Esc</kbd> on this line | The single most irritating thing an inline completion can do is come straight back. The refusal is remembered until you have typed six more characters, rewritten the line, or thirty seconds have passed |
| You have typed one character after a `.` | The IDE's own completion list is instant, exact and already on screen |
| Text is selected, or there is a second caret | You are doing something else |

And when it does appear, it appears **below** the caret line rather than across
the code beneath it. A multi-line suggestion occupies a block of its own: what
is below is pushed down while it is showing and springs back when it is
dismissed, so nothing you wrote is ever obscured and the suggestion reads as one
unit instead of text scattered down the file.

The length is bounded by where the caret is — one line mid-expression, up to
three on a blank line in a body, up to twelve on the line after an opening
brace. The ceiling is both asked for in the prompt and enforced on the reply: a
model that writes past it is cut back at the last line where the snippet is
still balanced, and dropped entirely if there is no such line. A block-sized
answer is only allowed where a block was genuinely just opened; anywhere else it
is demoted, because otherwise *suggest the next line* becomes *write the rest of
the function*.

**Snappy.** Two things make it feel immediate rather than merely fast. Typing
through a suggestion costs no round trip at all — when you type the characters
it was already proposing, the rest of that same suggestion is shown straight
back, so the answer cannot change under your hands mid-word. And the wait before
asking is measured rather than guessed: a fixed debounce is a guess at a number
that depends entirely on the model behind it, so the wait now tracks the median
round trip observed and slides between the shortest wait and the debounce you
configured. **Tools → LLM Copilot: Project Index Status** reports what it has
measured.

**It knows the whole application.** Every declaration and import in the project
is read once in the background and kept as a symbol table and a two-way import
graph, so a completion can be given the *real* signature of anything in the
codebase rather than a plausible-looking guess — and an error answer can be told
where the names in the failure are declared and who calls the file that threw.
See [The project index](#the-project-index).

**Completions that follow your line of thought.** Before asking for anything, the
plugin reads what the code so far is working towards. The verb in the enclosing
declaration's name is a job — `fetchUserOrders` retrieves and returns,
`validateEmail` checks and rejects, `collectActiveNames` accumulates into
something. Against that reading it works out how far the body has got: parameters
nothing has referenced yet, locals declared and never read, a list initialised
empty just before the loop the caret sits in, guard clauses already written, and
whether the declared return type has been satisfied on the main path. From those
facts it states what the next statement most likely does — *add `user` to
`result`, or skip it when it does not qualify*; *return early, passing the error
on to the caller* — and the model is told to continue that thought rather than
start a different one.

The same reading decides how much to write: one line and a tight token ceiling
mid-expression, a statement or two on a blank line in a body, the whole block on
the line after an opening brace.

The language-specific shapes it depends on live in one table, covering Java,
Kotlin, Scala, Groovy, TypeScript, JavaScript, Python, C#, C, C++, Rust, Go,
Ruby, PHP, Swift and Dart. `for (User user : users)`, `for _, user := range
users`, `foreach ($users as $user)`, `users.each do |user|` and `for (user <-
users)` are all read as the same thing: a loop over `users` binding `user`.

**A duplication guard.** Models love to re-emit code that is already on screen.
Three filters run over every suggestion before you see it: echoed prefixes are
stripped, whole blocks that already exist above or below the caret are rejected,
and individual repeated lines are dropped. If more than half the suggestion is
material you already have, nothing is shown.

**Doc comments on demand.** Type `//` or `/**` on a line of its own directly above
a declaration and the plugin drafts a documentation comment for it. The result is
shown in a preview dialog — nothing is written to your file until you click
**Accept**, and the insert is a single undoable action.

**Help with the error in front of you.** When a run or debug session ends badly,
the plugin keeps the output. Right-click the error in the console — or select it
in the terminal and press <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>E</kbd> — and it is
read for what it actually is: the exception and its message, the frames that name
real files, and the source around the line that threw. What comes back is a short
list of candidate fixes, one line each, most likely first, rather than one long
answer that may have guessed the wrong cause. Each fix carries the file and line
it edits and how sure the model is. Choosing one carries the error, the resolved
source, the project context and the chosen approach into the chat window, where
the answer arrives with the edit in it and the conversation carries on. Java,
Kotlin, Python, Node, Go, Rust, C#, Ruby, PHP, compiler diagnostics and
Gradle/Maven/npm failures are all recognised.

The source at the failing line is the obvious context, and on its own it is
rarely enough: a `NullPointerException` at `repo.findByCustomer(id)` cannot be
answered from that line — the answer is in whatever `repo` is, what that type
declares, and who constructed it. So the project index is consulted first and
the real declaration of every name in the failure goes in with it, along with
the files that import the failing one and the project's own manifest.

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
3. Select `build/distributions/llm-copilot-intellij-0.0.01.zip`
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
| Infer intent | `true` | Read what the code is working towards and tell the model what the next statement most likely does. |
| Show status bar | `true` | Status widget in the bottom bar. |
| Test framework | *(blank)* | Blank lets the model pick per language. |
| Offer solutions for errors | `true` | Watch run, debug and terminal output for failures. |
| Notify as soon as something fails | `true` | Off means the pane is reached from the menu or <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>E</kbd>. |
| Candidate fixes | `4` | How many one-line fixes the error pane lists. |
| Source context lines | `40` | Lines read around each failing line and sent with the error. |
| Send what the rest of the project says about the error | `true` | Attach where the names involved are declared, what imports the failing file, and the project manifest. |
| Set the wait from how fast the model actually answers | `true` | The wait slides between the shortest wait below and the debounce above, based on the median round trip measured so far. |
| Shortest wait | `150` ms | Floor for the adaptive wait. The debounce above remains the ceiling. |
| Max statement lines | `3` | Most lines a statement-sized suggestion may occupy. |
| Max block lines | `12` | Most lines a block-sized suggestion may occupy. |
| Min identifier chars | `2` | How much of an identifier must be typed before ghost text is offered. |
| Index the whole project | `true` | Read every declaration and import in the project so answers can use the real signatures. |
| Max files | `4000` | Ceiling on what the index holds. |
| Max file size | `256` KB | Larger files are skipped — generated bundles teach the model nothing. |
| Chars per completion | `2400` | Project context sent with each completion. `0` leaves it on for errors only. |

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
| Explain the error in the console or terminal | <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>E</kbd> |

<kbd>Tab</kbd> and <kbd>Esc</kbd> only belong to the plugin while a suggestion is
on screen; otherwise they fall through to their normal behaviour.

Fix Code, Generate Constructor, Generate Getters & Setters, Implement Interface,
Toggle Enable/Disable, and Test Connection have no default shortcut. Reach them
from the editor context menu under **LLM Copilot**, or bind them yourself.

### Errors from the console, the debugger and the terminal

Anything the IDE runs or debugs is watched: if the process exits non-zero — or
exits cleanly but printed something that reads like a failure, which is how test
runners that swallow their status behave — the output is kept and a notification
offers to look at it.

Three ways in:

- **Right-click the error** in a run or debug console → **LLM Copilot: Explain
  This Error**. A selection is used if there is one.
- **Select it in the terminal** and press <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>E</kbd>.
  The terminal is not an editor, so the selection is taken through the same copy
  handler <kbd>Ctrl</kbd>+<kbd>C</kbd> uses; the clipboard is put back afterwards.
- **Tools → LLM Copilot: Analyse a Recent Error** lists every failure captured so
  far, newest first, for one that has already scrolled away.

The pane lists the candidate fixes — each with the file and line it edits and
how sure the model is — plus:

- **Work it through properly** — the full diagnosis, which is the reasoning the
  shortlist deliberately leaves out: what the runtime was doing, which of the
  resolved declarations are actually involved, the cause with its evidence, the
  change, and what else in the project the change affects. Where the evidence
  does not settle it, the answer says which candidates it is between and what
  one observation would tell them apart. The entry reports how much it has to
  work with — *against 11 resolved names and 3 callers*.
- **Explain this error** — what it means, no fix yet.
- **Ask something about it…** — your own question, with everything attached.
- **Open the failing file** at the line, plus **a jump to any other file a fix
  named**. A cause that lives one file away from the throw is common.
- **Copy the error text.**

Everything but the last three opens the chat window with the question already
asked.

Frames are resolved to real files before anything is sent: absolute paths
directly, relative paths against the project root, and bare names — a Java trace
only ever names `Order.java` — through the file-name index. Frames inside
dependencies, the JDK and language runtimes are pushed behind your own code, so
the source that gets sent is the source you wrote.

### The project index

Most of the context above is about the caret: the declaration it is in, the
types on the line, the block it sits inside. None of it can answer *where is
`OrderRepository` declared*, *who calls this*, or *what is this project* — and
those are the questions a deep answer turns on. It is the difference between a
fix that compiles and a fix that is right.

So the project's sources are read once, in the background after the window
opens, and reduced to three things:

- **A symbol table** — every class, interface, function, method, type and
  constant, name to the file and *the declaration line itself*, so a lookup
  returns something quotable rather than a path.
- **An import graph, both ways** — forwards for what a file depends on,
  backwards for what depends on it. The second is what *will this change break
  anything* needs.
- **A digest of the project's shape** — languages, top-level layout, manifests
  and likely entry points, for the prompts that need orientation rather than
  detail.

Sixteen languages are read: Java, Kotlin, Scala, TypeScript, JavaScript,
TSX/JSX, Python, C#, Rust, Go, Ruby, PHP, Swift, Dart, C and C++. It is regex
over declaration lines rather than PSI, deliberately — IntelliJ IDEA Community
has no parser for most of that list, and the index has to cope with files that
do not currently compile. Anything subtler is the language's own resolver's
job, which the plugin already asks for the file being edited.

Nothing waits for it. The build is deferred until the IDE's own indices are
done, because a project that is still indexing is already spending every core
it has. Completions and error answers get sharper the moment it is ready and
work without it until then. Saving a file re-reads that one file.

Two actions under **Tools**:

| Action | What it does |
|---|---|
| **LLM Copilot: Project Index Status** | Files and symbols held, how long the last build took, the project digest, and the measured ghost-text latency |
| **LLM Copilot: Rebuild Project Index** | Re-reads everything. Only needed after the project changed outside the IDE |

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

Output: `build/distributions/llm-copilot-intellij-0.0.01.zip`

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

294 unit tests cover the logic that does not need a running IDE:

| Suite | What it pins down |
|---|---|
| `DuplicateGuardTest` | All three de-duplication levels, including whitespace-insensitive matching and preservation of trivial lines. |
| `IndentUtilsTest` | Fence and label stripping, relative indent preservation, tab vs. space output. |
| `StructureAnalyzerTest` | Container classification, suggestion selection, caret clamping. |
| `IntentInferenceTest` | Name reading, the block the caret is in, unused parameters and locals, accumulator detection, suggestion shape, rendering, and the same accumulating loop read across eleven languages. |
| `LanguageProfileTest` | Loop forms, local declarations, empty initialisers, void types, control-header detection and block style per language. |
| `PromptBuilderTest` | Role structure, framework selection, diff truncation, completion-prompt branches. |
| `LLMCopilotSettingsTest` | Shipped defaults and state round-tripping. |
| `ErrorParserTest` | Colour-code stripping, failure detection, capture trimming, and stack-trace reading for Node, Python, Java, .NET, Go, Rust, Ruby, compilers and build tools. |
| `ErrorSolutionsTest` | Numbered lists, bullets and bolded headings, over-long titles, code fences, the item limit, and the `[file:line] (confidence)` each fix carries. |
| `SuggestionGateTest` | Every reason ghost text refuses to appear — code after the caret, deletions, strings, comments, dismissals, short identifiers, selections — plus the line budget per shape and the balanced-cut trimming. |
| `GhostTextPacingTest` | Dismissal memory and its release conditions, forward-typing detection, the latency-led debounce across fast, slow and middling models, and typing through a suggestion. |
| `SourceSummaryTest` | Declaration and import extraction for Java, Kotlin, TypeScript, Python, Rust and Go, plus the import window and minified-line limits. |

Editor-dependent code is tested through `FakeEditor`, a helper that stubs the few
`Editor` and `Document` methods the production code touches, so the suite runs in
seconds without starting the platform. A `|` in a fixture string marks the caret.

An HTML report lands in `build/reports/tests/test/index.html`.

---

## Project layout

```
src/main/java/com/llmcopilot/
├── completion/   ghost text, the suggestion gate and its pacing, doc comments,
│                 structure analysis, key handling
├── index/        the whole-project symbol table and import graph
├── chat/         tool window, editor context capture, code proposals
├── errors/       error capture, trace parsing, deep context, the solutions pane
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
git tag v0.0.01
git push origin v0.0.01
```

---

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 RA King.
