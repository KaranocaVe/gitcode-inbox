package io.github.karanocave.inbox.engine;

import java.util.List;

public interface NotificationSource {
    Page page(Account account, int number);

    void markRead(Account account, List<String> ids);
}
