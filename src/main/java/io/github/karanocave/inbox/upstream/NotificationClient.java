package io.github.karanocave.inbox.upstream;

import io.github.karanocave.inbox.engine.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Component
public class NotificationClient implements NotificationSource {
    private final WebSessions sessions;
    private final List<Notice> demo;
    private static final int DEMO_PAGE = 10;

    public NotificationClient(WebSessions sessions, Clock clock, @Value("${INBOX_MODE:production}") String mode) {
        this.sessions = sessions;
        this.demo = "demo".equals(mode) ? demoMessages(clock.instant()) : null;
    }

    public boolean demo() { return demo != null; }

    @Override
    public Page page(Account account, int number) {
        if (demo == null) return sessions.page(account, number);
        synchronized (demo) {
            int start = Math.min((number - 1) * DEMO_PAGE, demo.size());
            int end = Math.min(start + DEMO_PAGE, demo.size());
            return new Page(number, DEMO_PAGE, (demo.size() + DEMO_PAGE - 1) / DEMO_PAGE, demo.size(),
                    List.copyOf(demo.subList(start, end)));
        }
    }

    @Override
    public void markRead(Account account, List<String> ids) {
        if (demo == null) {
            sessions.markRead(account, ids);
        } else {
            synchronized (demo) {
                var selected = new HashSet<>(ids);
                demo.removeIf(message -> selected.contains(message.id()));
            }
        }
    }

    private static List<Notice> demoMessages(Instant now) {
        record Example(String target, String title, String sender, String action, int count, int minutes) {}
        var samples = List.of(
                new Example("nebula/runtime/pr/418", "修复流式响应中的取消处理", "lin", "comment", 8, 4),
                new Example("nebula/runtime/issue/207", "连接复用时偶发请求超时", "mira", "open", 6, 12),
                new Example("nebula/toolkit/pr/91", "为离线构建补充缓存配置", "build-bot", "review", 4, 28),
                new Example("nebula/toolkit/issue/84", "导出命令缺少 JSON 示例", "wen", "comment", 3, 43),
                new Example("atlas/docs/pr/36", "整理本地开发环境说明", "qiao", "open", 2, 67),
                new Example("atlas/docs/issue/31", "移动端目录遮挡正文", "mira", "open", 1, 98),
                new Example("atlas/runner/pr/12", "提高任务日志的时间精度", "build-bot", "review", 2, 130));
        var messages = new ArrayList<Notice>();
        int index = 0;
        for (var example : samples) {
            var target = Target.parseGroupId(example.target());
            for (int n = 0; n < example.count(); n++) {
                boolean mention = n == 1;
                String kind = target.kind().equals("pr") ? "merge_requests" : "issue";
                String link = n == 0 ? target.url() : target.url() + "#discussion-" + n;
                if (mention && kind.equals("merge_requests")) link = link.replace("/pull/", "/merge_requests/");
                messages.add(new Notice("demo-" + index + "-" + n, target.id(),
                        now.minusSeconds((example.minutes() + n) * 60L),
                        mention ? example.sender() + " 在「" + example.title() + "」中提及你" : example.title(),
                        example.sender(), mention ? "mention" : example.action(), mention ? "mention" : kind, link));
            }
            index++;
        }
        messages.add(new Notice("demo-system", null, now, "你的项目邀请正在等待确认",
                "GitCode", "invite", "invitation", ""));
        messages.sort(Comparator.comparing(Notice::createdAt).reversed().thenComparing(Notice::id));
        return messages;
    }
}
