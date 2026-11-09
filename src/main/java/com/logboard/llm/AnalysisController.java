package com.logboard.llm;

import com.logboard.common.ApiError;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AnalysisController {

    private final AnalysisService service;

    public AnalysisController(AnalysisService service) {
        this.service = service;
    }

    /** Poll {@code GET /analyses/{id}} until the status is not PENDING. */
    @PostMapping("/error-logs/{id}/analyses")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ApiResponse(responseCode = "202", description = "Analysis created (PENDING); the LLM call runs in the background")
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS (only from CONFIRMED or ANALYZED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AnalysisDto start(@PathVariable String id, @Valid @RequestBody AnalysisRequest body) {
        return service.start(id, body);
    }

    @GetMapping("/analyses/{id}")
    public AnalysisDto get(@PathVariable long id) {
        return service.get(id);
    }

    /** Newest first. */
    @GetMapping("/error-logs/{id}/analyses")
    public List<AnalysisDto> history(@PathVariable String id) {
        return service.history(id);
    }
}
