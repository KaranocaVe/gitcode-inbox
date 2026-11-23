package io.github.karanocave.inbox.engine;

import java.time.Instant;

public record Notice(
        String id, String groupId, Instant createdAt, String content, String sender,
        String action, String targetType, String url
) {}
