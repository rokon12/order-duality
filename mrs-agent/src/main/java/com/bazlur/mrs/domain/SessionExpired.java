package com.bazlur.mrs.domain;

/** The service rejected a fresh sign-in, so this session cannot continue. */
public final class SessionExpired extends RuntimeException {
    public SessionExpired() {
        super("Session expired and signing in again failed");
    }
}
