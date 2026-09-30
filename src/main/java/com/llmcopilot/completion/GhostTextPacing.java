package com.llmcopilot.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The state that makes ghost text feel deliberate rather than reflexive: what
 * the author has already refused, whether they are typing forward or deleting,
 * how fast the model has actually been, and what is already on screen.
 *
 * <p>None of it belongs to a single editor — the handler and the ghost-text
 * manager both consult it, and the pacing has to be measured across every
 * completion in the session to mean anything — so it lives here as a set of
 * small classes with no IDE dependencies, which also makes it testable.
 *
 * <p>Mirrors the classes of the same names in {@code suggestionGate.ts}.
 */
public final class GhostTextPacing {

    private GhostTextPacing() { }

    // ─── Dismissal memory ────────────────────────────────────────────────────

    /**
     * What the author has already said no to.
     *
     * <p>Re-offering a suggestion the moment after it was dismissed is the
     * single most irritating thing an inline completion can do, and it happens
     * easily: pressing Escape does not change the document, so every heuristic
     * that keys on the document still thinks a suggestion belongs. This holds
     * the line, and releases it once the line has changed enough to be a
     * different question — or after a timeout, so a dismissal never becomes
     * permanent.
     */
    public static final class DismissalMemory {

        private record Entry(String prefix, long at) { }

        private final Map<String, Entry> entries = Collections.synchronizedMap(new LinkedHashMap<>());
        private final long ttlMs;
        /** Characters of new typing that make it a fresh question. */
        private final int releaseAfterChars;

        public DismissalMemory() { this(30_000, 6); }

        public DismissalMemory(long ttlMs, int releaseAfterChars) {
            this.ttlMs = ttlMs;
            this.releaseAfterChars = releaseAfterChars;
        }

        public void record(String file, int line, String linePrefix) {
            entries.put(key(file, line), new Entry(linePrefix, System.currentTimeMillis()));
            evict();
        }

        /** Has this caret been dismissed, and not yet typed past? */
        public boolean isDismissed(String file, int line, String linePrefix) {
            String key = key(file, line);
            Entry entry = entries.get(key);
            if (entry == null) return false;

            if (System.currentTimeMillis() - entry.at() >= ttlMs) {
                entries.remove(key);
                return false;
            }

            // Typing on past what was refused makes it a new question; deleting
            // back behind it does too, since the line is visibly being rewritten.
            if (!linePrefix.startsWith(entry.prefix())) {
                entries.remove(key);
                return false;
            }
            if (linePrefix.length() - entry.prefix().length() >= releaseAfterChars) {
                entries.remove(key);
                return false;
            }

            return true;
        }

        public void clear() { entries.clear(); }

        private void evict() {
            if (entries.size() < 64) return;
            long cutoff = System.currentTimeMillis() - ttlMs;
            synchronized (entries) {
                Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
                while (it.hasNext()) {
                    if (it.next().getValue().at() < cutoff) it.remove();
                }
            }
        }

        private static String key(String file, int line) { return file + "|" + line; }
    }

    // ─── Typing state ────────────────────────────────────────────────────────

    /**
     * Whether the author is typing forward, as opposed to deleting, undoing or
     * pasting. Ghost text after a deletion is the worst case for
     * intrusiveness: the author is removing something, and the IDE answers by
     * proposing more.
     */
    public static final class TypingTracker {

        private volatile long lastInsertAt = 0;
        private volatile boolean lastWasInsert = false;

        /** Called for every document change. */
        public void note(int insertedLength, int removedLength) {
            lastWasInsert = insertedLength > 0 && removedLength == 0;
            if (lastWasInsert) lastInsertAt = System.currentTimeMillis();
        }

        public boolean isTypingForward() { return isTypingForward(5_000); }

        public boolean isTypingForward(long windowMs) {
            return lastWasInsert && System.currentTimeMillis() - lastInsertAt < windowMs;
        }

        /** An explicit invoke is intent, whatever the last edit was. */
        public void noteExplicitInvoke() {
            lastWasInsert = true;
            lastInsertAt = System.currentTimeMillis();
        }
    }

    // ─── Latency-led debounce ────────────────────────────────────────────────

    /**
     * How long to wait before asking, given how fast the answers have been
     * coming.
     *
     * <p>A fixed debounce is a guess at a number that depends entirely on the
     * model behind it. A local small model answers in 120ms, where waiting
     * 600ms first is most of the latency the author feels; a hosted frontier
     * model takes two seconds, where firing early just burns requests on carets
     * the author has already left. Measuring removes the guess.
     *
     * <p>The median is used rather than the mean because one cold start should
     * not move the setting for the rest of the session.
     */
    public static final class AdaptiveDebounce {

        private static final int WINDOW = 12;

        private final List<Long> samples = Collections.synchronizedList(new ArrayList<>());
        private volatile int floorMs;
        private volatile int ceilingMs;

        public AdaptiveDebounce(int floorMs, int ceilingMs) {
            configure(floorMs, ceilingMs);
        }

        public void configure(int floorMs, int ceilingMs) {
            this.floorMs = Math.max(0, floorMs);
            this.ceilingMs = Math.max(this.floorMs, ceilingMs);
        }

        /** Record one completed round trip. */
        public void observe(long latencyMs) {
            if (latencyMs <= 0) return;
            synchronized (samples) {
                samples.add(latencyMs);
                while (samples.size() > WINDOW) samples.remove(0);
            }
        }

        /**
         * The wait to use now. With nothing measured the configured ceiling
         * stands; once the model has shown itself fast, the wait drops towards
         * the floor in proportion, so suggestions arrive sooner without the
         * request rate rising on a slow backend.
         */
        public int currentMs() {
            long median = medianMs();
            if (median == 0) return ceilingMs;

            int quick = ceilingMs / 4;
            if (median <= quick) return floorMs;

            double span = Math.max(1, ceilingMs - quick);
            double ratio = Math.min(1.0, (median - quick) / span);
            return (int) Math.round(floorMs + ratio * (ceilingMs - floorMs));
        }

        /** Median observed latency; zero until there are enough samples. */
        public long medianMs() {
            synchronized (samples) {
                if (samples.size() < 3) return 0;
                List<Long> sorted = new ArrayList<>(samples);
                Collections.sort(sorted);
                return sorted.get(sorted.size() / 2);
            }
        }

        public void reset() { samples.clear(); }
    }

    // ─── Typing through a suggestion ─────────────────────────────────────────

    /**
     * The cheapest suggestion is the one already on screen.
     *
     * <p>When the author types the characters a suggestion was proposing, the
     * honest answer to the next request is the rest of that same suggestion,
     * and it can be given with no round trip at all. Without this, every
     * keystroke through a suggestion re-asks the model, which costs the latency
     * and risks the answer changing under the author's hands mid-word.
     */
    public static final class ContinuationCache {

        private record Entry(String file, int line, String prefix, String remaining, long at) { }

        private final long ttlMs;
        private volatile Entry entry = null;

        public ContinuationCache() { this(45_000); }

        public ContinuationCache(long ttlMs) { this.ttlMs = ttlMs; }

        public void remember(String file, int line, String linePrefix, String suggestion) {
            entry = new Entry(file, line, linePrefix, suggestion, System.currentTimeMillis());
        }

        /**
         * The unsaid remainder, when what the author has typed since is exactly
         * the head of what was suggested. Null the moment they diverge.
         */
        public String continuation(String file, int line, String linePrefix) {
            Entry held = entry;
            if (held == null) return null;
            if (!held.file().equals(file) || held.line() != line) return null;
            if (System.currentTimeMillis() - held.at() > ttlMs) { entry = null; return null; }
            if (!linePrefix.startsWith(held.prefix())) { entry = null; return null; }

            String typed = linePrefix.substring(held.prefix().length());
            if (typed.isEmpty()) return held.remaining();
            if (!held.remaining().startsWith(typed)) { entry = null; return null; }

            String remaining = held.remaining().substring(typed.length());
            if (remaining.isBlank()) { entry = null; return null; }
            return remaining;
        }

        public void clear() { entry = null; }
    }
}
