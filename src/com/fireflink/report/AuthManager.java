package com.fireflink.report;

/**
 * Holds the current bearer token and re-authenticates on demand when a
 * request comes back 401 (token expired mid-run). Long executions (39
 * scripts, some with 80,000+ step records across dozens of paginated
 * requests) can easily outlive a single token's lifetime, so callers should
 * never cache the token themselves - always call getToken() fresh, and call
 * refresh() when a 401 is seen, then retry once.
 */
public class AuthManager {

    private final AuthClient authClient;
    private volatile String token;

    public AuthManager(AuthClient authClient) throws Exception {
        this.authClient = authClient;
        this.token = authClient.signIn();
    }

    public String getToken() {
        return token;
    }

    /** Re-authenticates and stores the new token. Safe to call from multiple threads later if this becomes concurrent. */
    public synchronized String refresh() throws Exception {
        System.out.println("[AUTH] Token expired/rejected (401) - re-authenticating...");
        token = authClient.signIn();
        System.out.println("[AUTH] Re-authenticated OK.");
        return token;
    }
}
