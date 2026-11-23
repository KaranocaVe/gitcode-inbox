package io.github.karanocave.inbox.engine;

import java.util.List;

public record Page(int current, int size, int pages, int total, List<Notice> messages) {
    public Page {
        messages = List.copyOf(messages);
    }
}
