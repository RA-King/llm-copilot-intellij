package com.llmcopilot.util;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.*;
import java.util.regex.*;

public class LanguageUtils {

    private static final Map<String, Set<String>> KEYWORDS = new HashMap<>();

    static {
        Set<String> tsKw = new HashSet<>(Arrays.asList(
            "function","async","class","interface","enum","type","namespace","abstract",
            "export","import","const","let","var","constructor","get","set","static",
            "private","public","protected","override","readonly","if","else","for",
            "while","do","switch","try","catch","finally","module","declare"));
        KEYWORDS.put("typescript",      tsKw);
        KEYWORDS.put("typescriptreact", tsKw);
        KEYWORDS.put("javascript",      new HashSet<>(Arrays.asList(
            "function","async","class","const","let","var","import","export",
            "constructor","get","set","static","if","else","for","while","do","switch","try","catch","finally")));
        KEYWORDS.put("python", new HashSet<>(Arrays.asList(
            "def","async","class","import","from","if","elif","else","for","while",
            "with","try","except","finally","return","yield","lambda","pass")));
        KEYWORDS.put("java", new HashSet<>(Arrays.asList(
            "public","private","protected","static","final","abstract","class",
            "interface","enum","void","if","else","for","while","do","switch","try","catch","finally","import","package")));
        KEYWORDS.put("kotlin", new HashSet<>(Arrays.asList(
            "fun","class","interface","object","enum","val","var","override","abstract",
            "open","private","public","if","else","for","while","when","try","catch","finally")));
        KEYWORDS.put("go", new HashSet<>(Arrays.asList(
            "func","type","struct","interface","var","const","import","package",
            "if","else","for","switch","select","go","defer")));
        KEYWORDS.put("rust", new HashSet<>(Arrays.asList(
            "fn","async","pub","struct","enum","impl","trait","type","mod","use",
            "let","const","static","if","else","for","while","loop","match")));
        KEYWORDS.put("cpp", KEYWORDS.get("java"));
        KEYWORDS.put("csharp", new HashSet<>(Arrays.asList(
            "public","private","protected","internal","static","readonly","abstract",
            "virtual","override","sealed","async","class","struct","interface","enum",
            "void","var","if","else","for","foreach","while","do","switch","try","catch","finally","using","namespace")));
    }

    /** Get the language ID for an editor (based on file extension) */
    public static String getLanguageId(Editor editor) {
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (vf == null) return "text";
        String ext = vf.getExtension();
        if (ext == null) return "text";
        return switch (ext.toLowerCase()) {
            case "ts","tsx"     -> "typescript";
            case "js","jsx","mjs" -> "javascript";
            case "py"           -> "python";
            case "java"         -> "java";
            case "kt","kts"     -> "kotlin";
            case "go"           -> "go";
            case "rs"           -> "rust";
            case "cpp","cc","cxx","h","hpp" -> "cpp";
            case "cs"           -> "csharp";
            case "rb"           -> "ruby";
            case "php"          -> "php";
            case "swift"        -> "swift";
            default             -> ext.toLowerCase();
        };
    }

    /** Returns the keyword if line prefix is exactly a trigger keyword (+ optional space), else null */
    public static String extractKeyword(String linePrefix, Editor editor) {
        if (linePrefix == null) return null;
        String trimmed = linePrefix.stripLeading();
        if (trimmed.isEmpty()) return null;
        // Match first word token
        Matcher m = Pattern.compile("^([#@]|[a-zA-Z_$][\\w$]*)").matcher(trimmed);
        if (!m.find()) return null;
        String token = m.group(1);
        String rest  = trimmed.substring(token.length());
        // Only trigger when line is JUST the keyword (+ optional space)
        if (!rest.isEmpty() && !rest.equals(" ") && !rest.equals("\t")) return null;
        String lang = getLanguageId(editor);
        Set<String> kws = KEYWORDS.get(lang);
        if (kws == null) return null;
        return kws.contains(token) ? token : null;
    }
}
