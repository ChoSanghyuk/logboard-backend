package com.logboard.crop;

import java.time.OffsetDateTime;

/**
 * An exception candidate: the entry at lines {@code anchorLine} (its header) .. {@code endLine}.
 * {@code exceptionClass} is null for an ERROR entry without a trace; then {@code message} is the header text.
 */
public record Candidate(int anchorLine, int endLine, OffsetDateTime time, String exceptionClass, String message) {}
