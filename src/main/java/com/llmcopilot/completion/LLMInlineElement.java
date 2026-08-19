package com.llmcopilot.completion;

import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.markup.TextAttributes;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * Ghost-text inlay renderer — Copilot-style dimmed italic.
 *
 * Uses raw {@code Inlay} and {@code @SuppressWarnings} so the same source
 * compiles against both the older 2024.x API (raw Inlay) and the newer
 * 2025/2026 API (generic Inlay<?>). The suppression is intentional.
 */
@SuppressWarnings({"rawtypes", "RedundantSuppression"})
public class LLMInlineElement implements EditorCustomElementRenderer {

    private final String  displayText;
    private final int     insertOffset;
    private final boolean isFirstLine;

    public LLMInlineElement(String displayText, int insertOffset, boolean isFirstLine) {
        this.displayText  = displayText;
        this.insertOffset = insertOffset;
        this.isFirstLine  = isFirstLine;
    }

    public String  getText()          { return displayText;  }
    public int     getInsertOffset()  { return insertOffset; }

    // ── EditorCustomElementRenderer ───────────────────────────────────────────

    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
        Editor      ed = inlay.getEditor();
        FontMetrics fm = ed.getContentComponent().getFontMetrics(ghostFont(ed));
        return fm.stringWidth(displayText) + 6;
    }

    @Override
    public void paint(@NotNull Inlay inlay, @NotNull Graphics g,
                      @NotNull Rectangle r, @NotNull TextAttributes textAttributes) {
        Editor ed = inlay.getEditor();
        g.setFont(ghostFont(ed));
        g.setColor(ghostColor(ed));
        g.drawString(displayText, r.x + 2, r.y + g.getFontMetrics().getAscent());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Font ghostFont(Editor ed) {
        return ed.getColorsScheme()
                  .getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
                  .deriveFont(Font.ITALIC);
    }

    private static Color ghostColor(Editor ed) {
        Color fg = ed.getColorsScheme().getDefaultForeground();
        Color bg = ed.getColorsScheme().getDefaultBackground();
        return new Color(
            (int)(fg.getRed()   * 0.35 + bg.getRed()   * 0.65),
            (int)(fg.getGreen() * 0.35 + bg.getGreen() * 0.65),
            (int)(fg.getBlue()  * 0.35 + bg.getBlue()  * 0.65),
            200
        );
    }
}
