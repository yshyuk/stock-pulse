package com.stockpulse.notification;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a long message body into chunks no larger than a channel's per-message limit.
 *
 * <p>Prefers to break on line boundaries so tables/sections stay intact; a single line
 * longer than the limit is hard-split as a last resort.
 */
public final class MessageSplitter {

    private MessageSplitter() {
    }

    public static List<String> split(String body, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            chunks.add("");
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            // A single line longer than the limit must be hard-split.
            while (line.length() > maxChars) {
                flush(chunks, current);
                chunks.add(line.substring(0, maxChars));
                line = line.substring(maxChars);
            }
            int extra = current.isEmpty() ? line.length() : line.length() + 1; // +1 for '\n'
            if (current.length() + extra > maxChars) {
                flush(chunks, current);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        flush(chunks, current);
        return chunks;
    }

    private static void flush(List<String> chunks, StringBuilder current) {
        if (current.length() > 0) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }
}
