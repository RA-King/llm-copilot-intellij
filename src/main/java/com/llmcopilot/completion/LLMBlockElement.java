package com.llmcopilot.completion;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.List;

/**
 * The continuation lines of a multi-line suggestion, drawn as one block below
 * the caret's line.
 *
 * <p>The alternative — one after-line-end inlay per suggested line — paints the
 * suggestion over the lines that follow, which already hold the author's own
 * code. A block element occupies vertical space of its own instead: the real
 * code below is pushed down while the suggestion is showing and springs back
 * the moment it is dismissed, so nothing is ever obscured and the suggestion
 * reads as a single unit rather than as text scattered down the file.
 *
 * <p>Styled to match {@link LLMInlineElement}, since the two halves of one
 * suggestion have to look like one suggestion.
 */
@SuppressWarnings({"rawtypes", "RedundantSuppression"})
public class LLMBlockElement implements EditorCustomElementRenderer {

    private final List<String> lines;

    public LLMBlockElement(List<String> lines) {
        this.lines = List.copyOf(lines);
    }

    public List<String> getLines() { return lines; }

    // ── EditorCustomElementRenderer ──────────────────────────────────────────

    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
        Editor editor = inlay.getEditor();
        FontMetrics fm = editor.getContentComponent().getFontMetrics(ghostFont(editor));
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, fm.stringWidth(line));
        }
        return widest + 6;
    }

    @Override
    public int calcHeightInPixels(@NotNull Inlay inlay) {
        return lines.size() * inlay.getEditor().getLineHeight();
    }

    @Override
    public void paint(@NotNull Inlay inlay, @NotNull Graphics g,
                      @NotNull Rectangle r, @NotNull TextAttributes textAttributes) {
        Editor editor = inlay.getEditor();
        g.setFont(ghostFont(editor));
        g.setColor(ghostColor(editor));

        int lineHeight = editor.getLineHeight();
        int ascent = g.getFontMetrics().getAscent();

        for (int i = 0; i < lines.size(); i++) {
            g.drawString(lines.get(i), r.x + 2, r.y + i * lineHeight + ascent);
        }
    }

    // ── Styling ──────────────────────────────────────────────────────────────
    //
    // Duplicated from LLMInlineElement rather than shared, because the two
    // implement different halves of EditorCustomElementRenderer and a shared
    // base class would be more indirection than the six lines are worth.

    private static Font ghostFont(Editor editor) {
        return editor.getColorsScheme().getFont(EditorFontType.PLAIN).deriveFont(Font.ITALIC);
    }

    private static Color ghostColor(Editor editor) {
        Color fg = editor.getColorsScheme().getDefaultForeground();
        Color bg = editor.getColorsScheme().getDefaultBackground();
        return new Color(
            (int) (fg.getRed()   * 0.35 + bg.getRed()   * 0.65),
            (int) (fg.getGreen() * 0.35 + bg.getGreen() * 0.65),
            (int) (fg.getBlue()  * 0.35 + bg.getBlue()  * 0.65),
            200);
    }
}
