package io.github.karanocave.inbox.engine;

import java.util.Objects;

public record Account(String id, String username) {
    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(username, "username");
        if (id.isBlank() || username.isBlank()) {
            throw new IllegalArgumentException("Account identity cannot be blank");
        }
    }
}
