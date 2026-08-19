package com.llmcopilot.completion;

import com.intellij.openapi.editor.*;

/**
 * Analyses where the cursor sits structurally — mirrors structureAnalyzer.ts.
 * Scans backward through braces to find the innermost container.
 */
public class StructureAnalyzer {

    public enum StructureKind {
        CLASS_BODY, INTERFACE_BODY, FUNCTION_BODY, ENUM_BODY, TOP_LEVEL, UNKNOWN
    }

    public enum SuggestionKind {
        CONSTRUCTOR, GETTER_SETTER, NEXT_METHOD, NEXT_STATEMENT, NEXT_DECLARATION, ENUM_CASE, GENERIC
    }

    public static class StructureContext {
        public final StructureKind  kind;
        public final SuggestionKind suggestion;
        public final String         containerName;
        public final String         containerType;
        public final String         signature;       // first line(s) of container declaration
        public final String         surroundingCode; // 20 lines before cursor
        public final boolean        isEmpty;

        public StructureContext(StructureKind kind, SuggestionKind suggestion,
                                String containerName, String containerType,
                                String signature, String surroundingCode, boolean isEmpty) {
            this.kind          = kind;
            this.suggestion    = suggestion;
            this.containerName = containerName;
            this.containerType = containerType;
            this.signature     = signature;
            this.surroundingCode = surroundingCode;
            this.isEmpty       = isEmpty;
        }
    }

    private static final java.util.regex.Pattern CLASS_PAT =
        java.util.regex.Pattern.compile("^\\s*(?:export\\s+)?(?:abstract\\s+|final\\s+)?(?:class|struct|record)\\s+(\\w+)");
    private static final java.util.regex.Pattern IFACE_PAT =
        java.util.regex.Pattern.compile("^\\s*(?:export\\s+)?(?:interface|protocol|trait)\\s+(\\w+)");
    private static final java.util.regex.Pattern ENUM_PAT  =
        java.util.regex.Pattern.compile("^\\s*(?:export\\s+)?enum\\s+(\\w+)");
    private static final java.util.regex.Pattern FN_PAT    =
        java.util.regex.Pattern.compile("^\\s*(?:pub\\s+|async\\s+|static\\s+|private\\s+|protected\\s+|public\\s+|override\\s+)*" +
            "(?:async\\s+)?(?:function\\s+|fn\\s+|def\\s+|func\\s+)(\\w+)");
    private static final java.util.regex.Pattern MEMBER_PAT =
        java.util.regex.Pattern.compile("^\\s*(?:public|private|protected|static|readonly|override|async|final|fun|val|var)\\b");

    public static StructureContext analyse(Editor editor, int offset) {
        Document doc = editor.getDocument();
        if (doc.getTextLength() == 0) return topLevel(editor, offset);

        int safeOffset = Math.min(offset, doc.getTextLength());
        int line       = doc.getLineNumber(safeOffset);
        int lookback   = Math.min(line, 150);

        int    braceDepth     = 0;
        int    containerLine  = -1;
        String containerType  = "";
        String containerName  = "";
        int    linesInside    = 0;
        int    memberCount    = 0;
        boolean hasConstructor = false;

        for (int i = line - 1; i >= line - lookback && i >= 0; i--) {
            String raw = doc.getLineCount() > i ? doc.getCharsSequence()
                .subSequence(doc.getLineStartOffset(i), doc.getLineEndOffset(i)).toString() : "";
            String trimmed = raw.trim();

            // Count braces right-to-left
            for (int ci = raw.length() - 1; ci >= 0; ci--) {
                char ch = raw.charAt(ci);
                if (ch == '}') { braceDepth++; }
                else if (ch == '{') {
                    braceDepth--;
                    if (braceDepth < 0) { containerLine = i; break; }
                }
            }

            if (containerLine == i) {
                // Classify container
                java.util.regex.Matcher m;
                if ((m = CLASS_PAT.matcher(trimmed)).find()) {
                    containerType = "class"; containerName = m.group(1);
                } else if ((m = IFACE_PAT.matcher(trimmed)).find()) {
                    containerType = "interface"; containerName = m.group(1);
                } else if ((m = ENUM_PAT.matcher(trimmed)).find()) {
                    containerType = "enum"; containerName = m.group(1);
                } else if ((m = FN_PAT.matcher(trimmed)).find()) {
                    containerType = "function"; containerName = m.group(1);
                }
                break;
            }

            if (braceDepth == 0) {
                linesInside++;
                if (MEMBER_PAT.matcher(trimmed).find()) { memberCount++; }
                if (trimmed.contains("constructor") || trimmed.contains("__init__")) { hasConstructor = true; }
            }
        }

        // Build signature string (up to 3 lines from container opening)
        String signature = "";
        if (containerLine >= 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = containerLine; i < Math.min(containerLine + 3, doc.getLineCount()); i++) {
                sb.append(doc.getCharsSequence()
                    .subSequence(doc.getLineStartOffset(i), doc.getLineEndOffset(i))).append("\n");
            }
            signature = sb.toString().trim();
        }

        // 20 lines of surrounding context
        int ctxStart = Math.max(0, line - 20);
        String surrounding = doc.getCharsSequence()
            .subSequence(doc.getLineStartOffset(ctxStart), safeOffset).toString();

        StructureKind kind;
        if (containerLine < 0) {
            kind = StructureKind.TOP_LEVEL;
        } else {
            kind = switch (containerType) {
                case "class"     -> StructureKind.CLASS_BODY;
                case "interface" -> StructureKind.INTERFACE_BODY;
                case "enum"      -> StructureKind.ENUM_BODY;
                case "function"  -> StructureKind.FUNCTION_BODY;
                default          -> StructureKind.UNKNOWN;
            };
        }

        SuggestionKind suggestion = pickSuggestion(kind, memberCount, hasConstructor);
        boolean isEmpty = linesInside < 2;

        return new StructureContext(kind, suggestion, containerName, containerType,
                                    signature, surrounding, isEmpty);
    }

    private static SuggestionKind pickSuggestion(StructureKind kind, int memberCount, boolean hasConstructor) {
        return switch (kind) {
            case CLASS_BODY     -> !hasConstructor && memberCount == 0 ? SuggestionKind.CONSTRUCTOR
                                 : memberCount > 0                     ? SuggestionKind.GETTER_SETTER
                                 : SuggestionKind.NEXT_METHOD;
            case INTERFACE_BODY -> SuggestionKind.NEXT_METHOD;
            case ENUM_BODY      -> SuggestionKind.ENUM_CASE;
            case FUNCTION_BODY  -> SuggestionKind.NEXT_STATEMENT;
            case TOP_LEVEL      -> SuggestionKind.NEXT_DECLARATION;
            default             -> SuggestionKind.GENERIC;
        };
    }

    private static StructureContext topLevel(Editor editor, int offset) {
        Document doc = editor.getDocument();
        int safeOffset = Math.min(offset, doc.getTextLength());
        int ctxStart = Math.max(0, safeOffset - 800);
        String surrounding = doc.getCharsSequence().subSequence(ctxStart, safeOffset).toString();
        return new StructureContext(StructureKind.TOP_LEVEL, SuggestionKind.NEXT_DECLARATION,
                                    "", "", "", surrounding, true);
    }
}
