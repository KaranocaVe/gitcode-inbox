package io.github.karanocave.inbox;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;

@EnableScheduling
@SpringBootApplication
public class InboxApplication {
    public static void main(String[] args) {
        SpringApplication.run(InboxApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    DataSource inboxDataSource(@Value("${INBOX_DB:./data/inbox.sqlite}") String path) throws IOException {
        Path database = Path.of(path).toAbsolutePath().normalize();
        Files.createDirectories(database.getParent());
        SQLiteConfig settings = new SQLiteConfig();
        settings.setBusyTimeout(8_000);
        settings.setJournalMode(SQLiteConfig.JournalMode.WAL);
        settings.enforceForeignKeys(true);
        SQLiteDataSource sqlite = new SQLiteDataSource(settings);
        sqlite.setUrl("jdbc:sqlite:" + database);
        HikariConfig pool = new HikariConfig();
        pool.setDataSource(sqlite);
        pool.setMaximumPoolSize(1);
        pool.setConnectionTimeout(10_000);
        return new HikariDataSource(pool);
    }
}
