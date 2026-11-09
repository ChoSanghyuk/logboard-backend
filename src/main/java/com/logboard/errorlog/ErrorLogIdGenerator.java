package com.logboard.errorlog;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Error log IDs {@code YYYYMMDD-NNN}: the creation date in Asia/Seoul plus a daily counter (≥ 3 digits, may pass 999).
 * The counter row in {@code error_log_seq} is incremented with an upsert, which row-locks it until the caller's
 * transaction commits, so parallel creates get distinct numbers.
 */
@Component
public class ErrorLogIdGenerator {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;

    public ErrorLogIdGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next() {
        return next(LocalDate.now(SEOUL));
    }

    String next(LocalDate date) {
        Integer seq = jdbc.queryForObject("""
                INSERT INTO error_log_seq (seq_date, last_seq) VALUES (?, 1)
                ON CONFLICT (seq_date) DO UPDATE SET last_seq = error_log_seq.last_seq + 1
                RETURNING last_seq""", Integer.class, date);
        return date.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%03d", seq);
    }
}
