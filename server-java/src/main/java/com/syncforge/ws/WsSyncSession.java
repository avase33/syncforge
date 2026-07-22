package com.syncforge.ws;

import com.syncforge.room.SyncSession;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Adapts a Spring {@link WebSocketSession} to the room's {@link SyncSession}
 * view. A single WebSocket connection must not have two threads writing to it at
 * once — {@code sendMessage} is not concurrency-safe — so every send goes
 * through a per-connection lock. Fan-out to many sessions still runs in
 * parallel; only writes to the <em>same</em> socket serialise.
 */
public final class WsSyncSession implements SyncSession {

    private final WebSocketSession delegate;
    private final String site;
    private final ReentrantLock writeLock = new ReentrantLock();

    public WsSyncSession(WebSocketSession delegate, String site) {
        this.delegate = delegate;
        this.site = site;
    }

    @Override
    public String id() {
        return delegate.getId();
    }

    @Override
    public String site() {
        return site;
    }

    @Override
    public void send(String frame) {
        writeLock.lock();
        try {
            if (delegate.isOpen()) {
                delegate.sendMessage(new TextMessage(frame));
            }
        } catch (IOException e) {
            throw new RuntimeException("send failed on session " + id(), e);
        } finally {
            writeLock.unlock();
        }
    }
}
