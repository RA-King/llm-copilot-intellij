package com.llmcopilot.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits an assistant reply into the alternating prose and fenced-code sections that
 * the chat view renders as separate bubbles.
 *
 * <p>Deliberately free of Swing and of any open editor, so the splitting rules can be
 * exercised without an IDE fixture.
 */
final class AssistantMessageParser {

    private static final Pattern FENCE = Pattern.compile("(?s)```(\\w*)\n(.*?)```");

    private AssistantMessageParser() {}

    /** What a segment should be rendered as. */
    enum Kind { PROSE, CODE }

    /**
     * One section of a reply. {@code language} carries the fence tag for {@link Kind#CODE}
     * and is empty for prose; it is empty for code too when the block opened with a bare
     * fence, leaving the caller to choose a fallback.
     */
    record Segment(Kind kind, String text, String language) {
        boolean isCode() { return kind == Kind.CODE; }
    }

    /**
     * Splits {@code text} in document order. Blank sections are dropped, so an empty result
     * means the reply held nothing worth showing. Text following an unterminated fence stays
     * prose, which is what the model actually sent.
     */
    static List<Segment> parse(String text) {
        List<Segment> segments = new ArrayList<>();
        if (text == null) return segments;

        Matcher m   = FENCE.matcher(text);
        int lastEnd = 0;
        while (m.find()) {
            addProse(segments, text.substring(lastEnd, m.start()));
            String code = m.group(2).trim();
            if (!code.isEmpty()) segments.add(new Segment(Kind.CODE, code, m.group(1)));
            lastEnd = m.end();
        }
        addProse(segments, text.substring(lastEnd));
        return segments;
    }

    private static void addProse(List<Segment> segments, String raw) {
        String prose = raw.trim();
        if (!prose.isEmpty()) segments.add(new Segment(Kind.PROSE, prose, ""));
    }
}
