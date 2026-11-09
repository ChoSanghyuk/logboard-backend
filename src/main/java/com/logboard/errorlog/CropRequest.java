package com.logboard.errorlog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

/**
 * {@code PATCH /error-logs/{id}/crop}. Give at least one field. {@code anchorLine} switches to that candidate
 * (default crop around it); {@code expandBefore}/{@code expandAfter} then widen the crop by that many lines.
 */
public record CropRequest(
        @Schema(description = "Lines to add above (the UI sends crop.expandStep)") @Positive Integer expandBefore,
        @Schema(description = "Lines to add below (the UI sends crop.expandStep)") @Positive Integer expandAfter,
        @Schema(description = "anchorLine of one of the candidates") @Positive Integer anchorLine) {}
