package id.ppob2.app.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Replay-protection nonce cache, PRD Section 23.2. In-memory for this scaffold — PRD Section 19.1
 * designates Redis as the shared nonce/session store, required before running more than one
 * app instance (an in-memory store does not protect against replay across instances).
 */
@Component
public class NonceStore {

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    public boolean registerIfAbsent(String clientId, String nonce, Duration window) {
        Instant now = Instant.now();
        evictExpired(now, window);
        String key = clientId + ":" + nonce;
        return seen.putIfAbsent(key, now) == null;
    }

    private void evictExpired(Instant now, Duration window) {
        seen.entrySet().removeIf(e -> Duration.between(e.getValue(), now).compareTo(window.multipliedBy(2)) > 0);
    }
}
