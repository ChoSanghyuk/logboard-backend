package com.logboard.crop;

import java.time.OffsetDateTime;

/**
 * An exception candidate: the entry at lines {@code anchorLine} (its header) .. {@code endLine}.
 * {@code exceptionClass} is null for an ERROR entry without a trace; then {@code message} is the header text.
 * {@code instanceId}: server instance whose raw file holds it (null until tagged, see {@link #withInstance}).
 */
public record Candidate(Long instanceId, int anchorLine, int endLine, OffsetDateTime time, String exceptionClass,
        String message) {

    public Candidate(int anchorLine, int endLine, OffsetDateTime time, String exceptionClass, String message) {
        this(null, anchorLine, endLine, time, exceptionClass, message);
    }

    public Candidate withInstance(long id) {
        return new Candidate(id, anchorLine, endLine, time, exceptionClass, message);
    }
}
