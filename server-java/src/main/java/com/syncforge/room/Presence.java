package com.syncforge.room;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ephemeral awareness state: where each participant's cursor is, what colour
 * they are, what they have selected. It is intentionally <em>not</em> a CRDT and
 * <em>not</em> part of the board. There is nothing to converge — a cursor
 * position from a moment ago is simply wrong, and the newest value per site
 * fully replaces the old one. When a session leaves, its entry is dropped and
 * the client renders the cursor away.
 */
public final class Presence {

    /** site id -> latest cursor frame (raw JSON string), last-write-wins. */
    private final Map<String, String> cursors = new ConcurrentHashMap<>();

    public void update(String site, String cursorFrame) {
        cursors.put(site, cursorFrame);
    }

    public void remove(String site) {
        cursors.remove(site);
    }

    public Map<String, String> snapshot() {
        return Map.copyOf(cursors);
    }

    public int size() {
        return cursors.size();
    }
}
