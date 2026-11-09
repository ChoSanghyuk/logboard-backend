package com.logboard.errorlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ErrorLogIdGeneratorTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final LocalDate DAY = LocalDate.of(2026, 11, 8);

    @Autowired ErrorLogIdGenerator ids;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    long serverId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM error_logs");
        jdbc.update("DELETE FROM error_log_seq");
        jdbc.update("DELETE FROM servers");
        serverId = jdbc.queryForObject("""
                INSERT INTO servers (name, host, username, auth_type, log_path, log_pattern_key, created_at, updated_at)
                VALUES ('s', 'h', 'u', 'PASSWORD', '/a.log', 'SPRING_BOOT_3', now(), now()) RETURNING id""", Long.class);
    }

    /** One "create": a transaction that takes an ID and inserts the error log row with it. */
    String create() {
        return tx.execute(s -> {
            String id = ids.next(DAY);
            jdbc.update("""
                    INSERT INTO error_logs (id, server_id, requested_time, window_min, raw_file_path, crop_start_line,
                        crop_end_line, anchor_line, anchor_end_line, total_lines, candidates, status, created_at, updated_at)
                    VALUES (?, ?, now(), 5, 'raw', 1, 1, 1, 1, 1, '[]', 'CROPPED', now(), now())""", id, serverId);
            return id;
        });
    }

    @Test
    void twentyParallelCreatesGetDistinctSequentialIds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return create();
            }));
        }
        start.countDown();
        List<String> created = new ArrayList<>();
        for (Future<String> f : futures) {
            created.add(f.get());
        }
        pool.shutdown();

        assertThat(created).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(
                IntStream.rangeClosed(1, 20).mapToObj(n -> String.format("20261108-%03d", n)).toList());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM error_logs", Integer.class)).isEqualTo(20);
    }

    @Test
    void counterIsPerDayAndGrowsPast999() {
        jdbc.update("INSERT INTO error_log_seq (seq_date, last_seq) VALUES (?, 999)", DAY);
        assertThat(create()).isEqualTo("20261108-1000");
        String nextDay = tx.execute(s -> ids.next(DAY.plusDays(1)));
        assertThat(nextDay).isEqualTo("20261109-001");
    }

    @Test
    void requiresATransaction() {
        assertThatThrownBy(() -> ids.next()).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void usesSeoulDate() {
        String id = tx.execute(s -> ids.next());
        assertThat(id).startsWith(LocalDate.now(ErrorLogIdGenerator.SEOUL)
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-");
    }
}
