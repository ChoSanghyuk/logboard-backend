package com.logboard.llm;

import com.logboard.common.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Shared error builders for the llm package (same shapes as the server registry). */
final class Checks {

    private Checks() {}

    static ApiException notFound(String what, long id) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + id + " not found");
    }

    static ApiException duplicateName(String name) {
        return new ApiException(HttpStatus.CONFLICT, "DUPLICATE_NAME", "The name '" + name + "' is already used",
                Map.of("field", "name"));
    }

    static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                Map.of("fields", Map.of(field, message)));
    }

    static ApiException inUse(String what) {
        return new ApiException(HttpStatus.CONFLICT, "IN_USE", what + " is used by analyses and cannot be deleted");
    }
}
