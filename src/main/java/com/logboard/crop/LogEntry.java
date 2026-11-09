package com.logboard.crop;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One log entry: a header line plus its continuation lines (stack trace etc.), lines {@code firstLine..endLine}.
 * The preamble (continuation lines before the first header) is an entry with {@code header == null}.
 * {@code time} is null if unknown (preamble, unparseable ts, or time-only ts without a file date).
 * {@code headerRest} = the header text after the matched header regex.
 */
public record LogEntry(int firstLine, int endLine, OffsetDateTime time, String level, String header,
                       String headerRest, List<String> continuation) {}
