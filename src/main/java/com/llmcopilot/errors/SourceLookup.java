package com.llmcopilot.errors;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Turns the paths a stack trace printed into files in this project, and reads
 * the source around the lines it named.
 *
 * <p>Absolute paths are checked as they stand. Anything relative is tried
 * against the project root and then, as a last resort, searched for by file
 * name — a Java trace only ever names {@code Order.java}, never where it lives.
 */
final class SourceLookup {

    private SourceLookup() { }

    /** A frame that resolved, with the code around it. */
    record FrameContext(
        VirtualFile file,
        ErrorParser.Frame frame,
        /** Zero-based line the snippet starts at, for labelling. */
        int startLine,
        String snippet,
        String language
    ) { }

    /** How many frames are worth sending source for. */
    private static final int MAX_FRAMES = 2;

    static List<FrameContext> gather(Project project, ErrorParser.ParsedError parsed, int contextLines) {
        int span = Math.max(10, contextLines);
        List<FrameContext> found = new ArrayList<>();

        for (ErrorParser.Frame frame : ErrorParser.rankFrames(parsed.frames())) {
            if (found.size() >= MAX_FRAMES) break;

            VirtualFile file = resolve(project, frame.file());
            if (file == null) continue;

            boolean already = found.stream().anyMatch(
                f -> f.file().equals(file) && f.frame().line() == frame.line());
            if (already) continue;

            FrameContext context = read(project, file, frame, span);
            if (context != null) found.add(context);
        }

        return found;
    }

    private static VirtualFile resolve(Project project, String rawPath) {
        String path = rawPath.replaceFirst("^file://", "").replace('\\', '/');
        if (path.isEmpty() || path.startsWith("node:") || path.contains("<")) return null;

        LocalFileSystem fs = LocalFileSystem.getInstance();

        if (new File(path).isAbsolute()) {
            VirtualFile file = fs.findFileByPath(path);
            if (file != null && !file.isDirectory()) return file;
        }

        String base = project.getBasePath();
        if (base != null) {
            VirtualFile file = fs.findFileByPath(base + "/" + path);
            if (file != null && !file.isDirectory()) return file;
        }

        return searchByName(project, path.substring(path.lastIndexOf('/') + 1), path);
    }

    /**
     * The file-name index only answers in smart mode; while the project is still
     * indexing the frame is simply left unresolved and the prompt goes out with
     * the trace alone.
     */
    private static VirtualFile searchByName(Project project, String name, String tail) {
        if (name.isEmpty() || !name.contains(".") || DumbService.isDumb(project)) return null;

        Collection<VirtualFile> matches = ReadAction.compute(() ->
            FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project)));
        if (matches.isEmpty()) return null;

        // Prefer a match whose tail is the path the trace printed.
        for (VirtualFile candidate : matches) {
            if (candidate.getPath().endsWith(tail)) return candidate;
        }
        return matches.iterator().next();
    }

    private static FrameContext read(Project project, VirtualFile file, ErrorParser.Frame frame, int span) {
        return ReadAction.compute(() -> {
            Document doc = FileDocumentManager.getInstance().getDocument(file);
            if (doc == null || doc.getLineCount() == 0) return null;

            int centre = Math.min(Math.max(0, frame.line() - 1), doc.getLineCount() - 1);
            int start = Math.max(0, centre - span / 2);
            int end = Math.min(doc.getLineCount() - 1, centre + span / 2);
            String snippet = doc.getText(new com.intellij.openapi.util.TextRange(
                doc.getLineStartOffset(start), doc.getLineEndOffset(end)));

            String extension = file.getExtension();
            return new FrameContext(file, frame, start, snippet, extension == null ? "text" : extension);
        });
    }
}
