package com.llmcopilot.chat;

import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Renders a proposed code block inside the chat with Accept / Discard buttons.
 * Mirrors the Junie AI "diff view" — shows the proposed code clearly and
 * lets the user apply it to the editor with one click.
 */
public class CodeProposalPanel extends JPanel {

    private final String proposedCode;
    private final String language;

    public CodeProposalPanel(Project project, String proposedCode, String language,
                             EditorContextProvider.EditorContext capturedCtx,
                             Consumer<String> onAccept,
                             Runnable onDiscard) {
        super(new BorderLayout(0, 4));
        this.proposedCode = proposedCode;
        this.language     = language;
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(100, 160, 100), 1, true),
            JBUI.Borders.empty(6)));
        setBackground(new Color(240, 248, 240));

        // ── Header bar ────────────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout(4, 0));
        header.setOpaque(false);
        JLabel title = new JLabel("  Proposed code (" + language + ")");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 11f));
        title.setForeground(new Color(40, 100, 40));
        header.add(title, BorderLayout.WEST);

        // ── Code display ──────────────────────────────────────────────────────
        JTextPane codePane = new JTextPane();
        codePane.setEditable(false);
        codePane.setBackground(new Color(30, 30, 30));
        codePane.setForeground(new Color(212, 212, 212));
        codePane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        codePane.setMargin(JBUI.insets(8));

        // Simple syntax-colourless display — monospaced on dark bg reads well
        StyledDocument doc = codePane.getStyledDocument();
        Style style = doc.addStyle("code", null);
        StyleConstants.setFontFamily(style, Font.MONOSPACED);
        StyleConstants.setFontSize(style, 12);
        StyleConstants.setForeground(style, new Color(212, 212, 212));
        try {
            doc.insertString(0, proposedCode, style);
        } catch (BadLocationException ignored) {}

        JBScrollPane codeScroll = new JBScrollPane(codePane);
        codeScroll.setBorder(BorderFactory.createEmptyBorder());
        // Limit height to ~12 lines; user can scroll for more
        int lineHeight = codePane.getFontMetrics(codePane.getFont()).getHeight();
        int lines = Math.min(proposedCode.split("\n").length, 12);
        codeScroll.setPreferredSize(new Dimension(0, lines * lineHeight + 20));

        // ── Action buttons ────────────────────────────────────────────────────
        JButton acceptBtn  = new JButton("✓ Accept");
        JButton discardBtn = new JButton("✗ Discard");
        JButton copyBtn    = new JButton("Copy");

        acceptBtn.setBackground(new Color(76, 153, 76));
        acceptBtn.setForeground(Color.WHITE);
        acceptBtn.setFocusPainted(false);
        acceptBtn.setFont(acceptBtn.getFont().deriveFont(Font.BOLD));

        discardBtn.setBackground(new Color(200, 60, 60));
        discardBtn.setForeground(Color.WHITE);
        discardBtn.setFocusPainted(false);

        acceptBtn.addActionListener(e -> {
            onAccept.accept(proposedCode);
            setVisible(false);          // hide proposal after accepting
        });
        discardBtn.addActionListener(e -> {
            onDiscard.run();
            setVisible(false);
        });
        copyBtn.addActionListener(e -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(proposedCode), null);
        });

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnRow.setOpaque(false);
        btnRow.add(acceptBtn);
        btnRow.add(discardBtn);
        btnRow.add(copyBtn);
        if (capturedCtx.hasSelection) {
            JLabel hint = new JLabel("  Will replace selection in " + capturedCtx.fileName);
            hint.setFont(hint.getFont().deriveFont(10f));
            hint.setForeground(Color.GRAY);
            btnRow.add(hint);
        }

        add(header,     BorderLayout.NORTH);
        add(codeScroll, BorderLayout.CENTER);
        add(btnRow,     BorderLayout.SOUTH);
    }
}
