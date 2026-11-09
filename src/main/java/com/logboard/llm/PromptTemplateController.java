package com.logboard.llm;

import com.logboard.common.ApiError;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prompts")
public class PromptTemplateController {

    private final PromptTemplateService service;

    public PromptTemplateController(PromptTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public List<PromptTemplateDto> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "409", description = "DUPLICATE_NAME",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public PromptTemplateDto create(@Valid @RequestBody PromptTemplateUpsert body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "409", description = "DUPLICATE_NAME",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public PromptTemplateDto update(@PathVariable long id, @Valid @RequestBody PromptTemplateUpsert body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "409", description = "IN_USE (analyses reference it)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
