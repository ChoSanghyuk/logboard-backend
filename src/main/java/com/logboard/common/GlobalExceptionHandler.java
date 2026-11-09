package com.logboard.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception to {@link ApiError}.
 * <ul>
 *   <li>{@link ApiException} → its own status and code.</li>
 *   <li>Bean validation failure → 400 {@code VALIDATION_FAILED}, {@code details.fields = {field: message}}.</li>
 *   <li>Other Spring MVC errors (bad JSON, 404 route, 405, …) → their status; code = HTTP status name.</li>
 *   <li>Anything else → 500 {@code INTERNAL_ERROR} (logged, message not leaked).</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ApiError(ex.getCode(), ex.getMessage(), ex.getDetails()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "Unexpected server error", Map.of()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        List<String> global = ex.getBindingResult().getGlobalErrors().stream()
                .map(e -> e.getDefaultMessage()).toList();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fields", fields);
        if (!global.isEmpty()) {
            details.put("errors", global);
        }
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_FAILED", "Request validation failed", details));
    }

    /** Converts Spring's default ProblemDetail bodies into ApiError. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = body instanceof ProblemDetail pd && pd.getDetail() != null ? pd.getDetail() : ex.getMessage();
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String code = resolved != null ? resolved.name() : "HTTP_" + status.value();
        return super.handleExceptionInternal(ex, new ApiError(code, message, Map.of()), headers, status, request);
    }
}
