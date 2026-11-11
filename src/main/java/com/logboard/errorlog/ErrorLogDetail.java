package com.logboard.errorlog;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.logboard.crop.LineKind;
import com.logboard.llm.AnalysisDto;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** {@code ErrorLogSummary} + crop, candidates, fetch failures and resolution fields. Times are UTC. */
public record ErrorLogDetail(
        @Schema(requiredMode = REQUIRED, example = "20261108-001") String id,
        @Schema(requiredMode = REQUIRED) long serverId,
        @Schema(requiredMode = REQUIRED) String serverName,
        @Schema(requiredMode = REQUIRED, description = "IANA timezone of the server (also for soft-deleted servers), "
                + "for displaying times", example = "Asia/Seoul") String serverTimezone,
        @Schema(requiredMode = REQUIRED, description = "Instance the crop comes from") InstanceRef instance,
        @Schema(requiredMode = REQUIRED) OffsetDateTime requestedTime,
        @Schema(description = "Top-level exception class; null for an ERROR entry without a trace") String exceptionClass,
        String exceptionMessage,
        @Schema(requiredMode = REQUIRED) ErrorStatus status,
        @Schema(requiredMode = REQUIRED) OffsetDateTime createdAt,
        @Schema(requiredMode = REQUIRED) int windowMin,
        String note,
        String resolution,
        ResolvedBy resolvedBy,
        @Schema(description = "When it was terminated (UTC); null until then") OffsetDateTime resolvedAt,
        @Schema(requiredMode = REQUIRED) CropDto crop,
        @Schema(requiredMode = REQUIRED, description = "All candidates in the window over all fetched instances: "
                + "by instance position, then line") List<CandidateDto> candidates,
        @Schema(requiredMode = REQUIRED, description = "Instances that could not be read during the search; [] if none")
                List<FetchFailure> fetchFailures,
        @Schema(description = "Newest analysis (any status); `null` if none (always present)") AnalysisDto latestAnalysis) {

    /**
     * Line numbers are 1-based in the raw copy of {@code instance}. {@code expandStep} = logboard.crop.expand-step,
     * {@code maxLines} = logboard.crop.max-lines (the UI disables expand when the crop has that many lines).
     */
    public record CropDto(
            @Schema(requiredMode = REQUIRED) int startLine,
            @Schema(requiredMode = REQUIRED) int endLine,
            @Schema(requiredMode = REQUIRED) int anchorLine,
            @Schema(requiredMode = REQUIRED) int totalLines,
            @Schema(requiredMode = REQUIRED) int expandStep,
            @Schema(requiredMode = REQUIRED) int maxLines,
            @Schema(requiredMode = REQUIRED) List<CropLineDto> lines) {}

    public record CropLineDto(
            @Schema(requiredMode = REQUIRED) int no,
            @Schema(requiredMode = REQUIRED) String text,
            @Schema(requiredMode = REQUIRED) LineKind kind) {}

    public record CandidateDto(
            @Schema(requiredMode = REQUIRED) long instanceId,
            @Schema(requiredMode = REQUIRED, description = "Host of the candidate's instance") String host,
            @Schema(requiredMode = REQUIRED, description = "Line in the raw copy of that instance") int anchorLine,
            @Schema(requiredMode = REQUIRED) OffsetDateTime time,
            String exceptionClass,
            String message) {}
}
