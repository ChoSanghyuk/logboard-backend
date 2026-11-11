package com.logboard.errorlog;

import com.logboard.common.ApiError;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/error-logs")
public class ErrorLogController {

    private final ErrorLogService service;

    public ErrorLogController(ErrorLogService service) {
        this.service = service;
    }

    @PostMapping("/search")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "Exception found and cropped (status CROPPED). Instances that "
            + "could not be read are listed in fetchFailures")
    @ApiResponse(responseCode = "404", description = "NO_EXCEPTION_FOUND (details: errorTime, windowMin, from, to, "
            + "searchedInstances: InstanceRef[], failures: FetchFailure[]) or NOT_FOUND (server)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "502", description = "FETCH_FAILED: every instance failed (details.failures: FetchFailure[])",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ErrorLogDetail search(@Valid @RequestBody SearchRequest body) {
        return service.search(body);
    }

    @GetMapping
    public ErrorLogPage list(
            @Parameter(description = "Repeatable") @RequestParam(required = false) List<ErrorStatus> status,
            @RequestParam(required = false) Long serverId,
            @Parameter(description = "requestedTime >= from (ISO-8601 with offset; URL-encode '+')")
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "requestedTime <= to (ISO-8601 with offset; URL-encode '+')")
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "1..100") @RequestParam(defaultValue = "20") int size) {
        return service.list(status, serverId, from, to, page, size);
    }

    @GetMapping("/{id}")
    public ErrorLogDetail get(@PathVariable String id) {
        return service.get(id);
    }

    @PatchMapping("/{id}/crop")
    @ApiResponse(responseCode = "200", description = "Re-cropped")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (fields.anchorLine: not a candidate; "
            + "fields.instanceId: without anchorLine)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS (not CROPPED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ErrorLogDetail crop(@PathVariable String id, @Valid @RequestBody CropRequest body) {
        return service.recrop(id, body);
    }

    @PostMapping("/{id}/confirm")
    @ApiResponse(responseCode = "200", description = "CROPPED → CONFIRMED")
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ErrorLogDetail confirm(@PathVariable String id) {
        return service.confirm(id);
    }

    @PostMapping("/{id}/terminate")
    @ApiResponse(responseCode = "200", description = "CONFIRMED/ANALYZED/DEV_REQUIRED → TERMINATED (resolvedBy USER)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (fields.resolution)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ErrorLogDetail terminate(@PathVariable String id, @Valid @RequestBody TerminateRequest body) {
        return service.terminate(id, body.resolution(), ResolvedBy.USER);
    }

    @PostMapping("/{id}/dev-required")
    @ApiResponse(responseCode = "200", description = "ANALYZED → DEV_REQUIRED")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (fields.note)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ErrorLogDetail devRequired(@PathVariable String id, @Valid @RequestBody DevRequiredRequest body) {
        return service.devRequired(id, body.note());
    }

    @PostMapping("/{id}/discard")
    @ApiResponse(responseCode = "200", description = "CROPPED → DISCARDED")
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ErrorLogDetail discard(@PathVariable String id) {
        return service.discard(id);
    }
}
