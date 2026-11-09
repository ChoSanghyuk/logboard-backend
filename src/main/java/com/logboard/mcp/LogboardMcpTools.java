package com.logboard.mcp;

import com.logboard.common.ApiException;
import com.logboard.errorlog.ErrorLog;
import com.logboard.errorlog.ErrorLogRepository;
import com.logboard.errorlog.ErrorLogService;
import com.logboard.errorlog.ErrorStatus;
import com.logboard.errorlog.ResolvedBy;
import com.logboard.llm.AnalysisRepository;
import com.logboard.llm.AnalysisStatus;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP tools for external code agents (design §8). Results are JSON text. Failures are tool errors
 * ({@code isError=true}) with a message telling the agent what to do. Nothing is thrown through MCP.
 * Writes go through {@link ErrorLogService} and so through the status machine.
 */
@Component
public class LogboardMcpTools {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    private static final Pattern ID = Pattern.compile("^\\d{8}-\\d{3,}$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ErrorLogRepository errorLogs;
    private final AnalysisRepository analyses;
    private final ErrorLogService errorLogService;
    private final TransactionTemplate tx;

    public LogboardMcpTools(ErrorLogRepository errorLogs, AnalysisRepository analyses, ErrorLogService errorLogService,
            TransactionTemplate tx) {
        this.errorLogs = errorLogs;
        this.analyses = analyses;
        this.errorLogService = errorLogService;
        this.tx = tx;
    }

    record QueueItem(String id, String serverName, String exceptionClass, String exceptionMessage,
                     OffsetDateTime createdAt, String note) {}

    record ServerRef(String name, String host) {}

    record AnalysisRef(String prompt, String reply) {}

    record ErrorLogView(String id, ServerRef server, OffsetDateTime errorTime, String exceptionClass, String message,
                        String status, String userNote, String croppedLog, AnalysisRef latestAnalysis) {}

    record Terminated(String id, String status) {}

    @McpTool(name = "list_dev_required_errors", description = """
            List production errors that a Logboard user marked "developer needed" (status DEV_REQUIRED). \
            This is your work queue: call it first, then get_error_log for details. Oldest first. \
            Returns a JSON array of {id, serverName, exceptionClass, exceptionMessage, createdAt (UTC), note}; \
            "note" is what the user wants the developer to look at. IDs look like 20261108-001 (YYYYMMDD-NNN).""")
    public CallToolResult listDevRequiredErrors(
            @McpToolParam(required = false, description = "Only errors from this Logboard server (exact name, "
                    + "case-insensitive). Omit for all servers.") String serverName,
            @McpToolParam(required = false, description = "Max items, 1-100. Default 20.") Integer limit) {
        int n = limit == null ? DEFAULT_LIMIT : limit;
        if (n < 1 || n > MAX_LIMIT) {
            return error("limit must be between 1 and " + MAX_LIMIT + " (got " + n + ").");
        }
        List<QueueItem> items = tx.execute(s -> {
            Specification<ErrorLog> spec = (r, q, cb) -> {
                var status = cb.equal(r.get("status"), ErrorStatus.DEV_REQUIRED);
                return serverName == null || serverName.isBlank() ? status
                        : cb.and(status, cb.equal(cb.lower(r.get("server").get("name")), serverName.strip().toLowerCase()));
            };
            return errorLogs.findAll(spec, PageRequest.of(0, n, Sort.by("createdAt", "id"))).stream()
                    .map(e -> new QueueItem(e.getId(), e.getServer().getName(), e.getExceptionClass(),
                            e.getExceptionMessage(), e.getCreatedAt(), e.getNote()))
                    .toList();
        });
        return ok(items);
    }

    @McpTool(name = "get_error_log", description = """
            Get everything Logboard knows about one error: the cropped log (the exception's stack trace with \
            surrounding lines, the main evidence), server, errorTime (in the server's timezone), exception class \
            and message, status, the user's note, and the latest successful LLM analysis {prompt, reply} (null if \
            none). Works for any status. errorId format: YYYYMMDD-NNN, e.g. 20261108-001.""")
    public CallToolResult getErrorLog(
            @McpToolParam(description = "Error log ID, YYYYMMDD-NNN (e.g. 20261108-001)") String errorId) {
        String problem = checkId(errorId);
        if (problem != null) {
            return error(problem);
        }
        ErrorLogView view = tx.execute(s -> errorLogs.findWithServerById(errorId.strip()).map(e -> {
            AnalysisRef analysis = analyses.findFirstByErrorLogIdAndStatusOrderByCreatedAtDescIdDesc(e.getId(),
                    AnalysisStatus.DONE).map(a -> new AnalysisRef(a.getRenderedPrompt(), a.getReply())).orElse(null);
            return new ErrorLogView(e.getId(), new ServerRef(e.getServer().getName(), e.getServer().getHost()),
                    e.getRequestedTime().atZoneSameInstant(ZoneId.of(e.getServer().getTimezone())).toOffsetDateTime(),
                    e.getExceptionClass(), e.getExceptionMessage(), e.getStatus().name(), e.getNote(),
                    errorLogService.cropText(e.getId()), analysis);
        }).orElse(null));
        return view != null ? ok(view) : error(unknown(errorId));
    }

    @McpTool(name = "terminate_error", description = """
            Close an error after you fixed it: DEV_REQUIRED → TERMINATED, recorded as resolved by the agent. \
            Only works on errors in status DEV_REQUIRED (from list_dev_required_errors). "resolution" is required: \
            summarize the root cause and the fix, and include the commit hash or PR link so the user can verify it. \
            Returns {id, status: "TERMINATED"}.""")
    public CallToolResult terminateError(
            @McpToolParam(description = "Error log ID, YYYYMMDD-NNN (e.g. 20261108-001)") String errorId,
            @McpToolParam(description = "Root cause and fix summary, with commit hash or PR link. Required.")
                    String resolution) {
        String problem = checkId(errorId);
        if (problem != null) {
            return error(problem);
        }
        try {
            var detail = errorLogService.terminate(errorId.strip(), resolution, ResolvedBy.AGENT);
            return ok(new Terminated(detail.id(), detail.status().name()));
        } catch (ApiException e) {
            return switch (e.getCode()) {
                case "NOT_FOUND" -> error(unknown(errorId));
                case "INVALID_STATUS" -> error("Error log " + errorId.strip() + " is " + e.getDetails().get("status")
                        + ", not DEV_REQUIRED. terminate_error only closes errors waiting for a developer; "
                        + "the others are handled by the user in Logboard.");
                case "VALIDATION_FAILED" -> error("resolution is required: describe the root cause and the fix, "
                        + "with the commit hash or PR link.");
                default -> error(e.getMessage());
            };
        }
    }

    private static String checkId(String errorId) {
        if (errorId == null || !ID.matcher(errorId.strip()).matches()) {
            return "Invalid errorId '" + errorId + "': expected YYYYMMDD-NNN, e.g. 20261108-001.";
        }
        return null;
    }

    private static String unknown(String errorId) {
        return "No error log with id " + errorId.strip() + ". Use list_dev_required_errors to find IDs.";
    }

    private static CallToolResult ok(Object value) {
        return CallToolResult.builder().addTextContent(JSON.writeValueAsString(value)).isError(false).build();
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }
}
