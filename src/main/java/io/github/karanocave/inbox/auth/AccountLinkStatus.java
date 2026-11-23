package io.github.karanocave.inbox.auth;

import io.github.karanocave.inbox.engine.Account;

@FunctionalInterface
public interface AccountLinkStatus {
    boolean linked(Account account);
}
