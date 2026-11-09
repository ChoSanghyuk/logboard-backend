package com.logboard.llm;

import com.logboard.common.ApiException;
import com.logboard.errorlog.ErrorLog;
import com.logboard.errorlog.ErrorLogRepository;
import com.logboard.errorlog.ErrorLogService;
import com.logboard.errorlog.ErrorLogStatusMachine;
import com.logboard.errorlog.ErrorLogStatusMachine.Action;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Design §7: render the template → save PENDING → call the LLM asynchronously ({@link AnalysisRunner}). */
@Service
public class AnalysisService {

    private final AnalysisRepository repo;
    private final ErrorLogRepository errorLogs;
    private final ErrorLogService errorLogService;
    private final LlmEndpointService endpoints;
    private final PromptTemplateService prompts;
    private final AnalysisRunner runner;
    private final TransactionTemplate tx;

    public AnalysisService(AnalysisRepository repo, ErrorLogRepository errorLogs, ErrorLogService errorLogService,
            LlmEndpointService endpoints, PromptTemplateService prompts, AnalysisRunner runner, TransactionTemplate tx) {
        this.repo = repo;
        this.errorLogs = errorLogs;
        this.errorLogService = errorLogService;
        this.endpoints = endpoints;
        this.prompts = prompts;
        this.runner = runner;
        this.tx = tx;
    }

    /** Only from CONFIRMED/ANALYZED (else 409 INVALID_STATUS). Returns the PENDING analysis; the call runs async. */
    public AnalysisDto start(String errorLogId, AnalysisRequest req) {
        AnalysisDto pending = tx.execute(s -> {
            ErrorLog log = errorLogs.findForUpdate(errorLogId).orElseThrow(() ->
                    new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Error log " + errorLogId + " not found"));
            ErrorLogStatusMachine.apply(log, Action.START_ANALYSIS);
            LlmEndpoint endpoint = endpoints.get(req.llmEndpointId());
            PromptTemplate prompt = prompts.get(req.promptTemplateId());
            String rendered = PromptRenderer.render(prompt.getContent(), Map.of(
                    "errorLog", errorLogService.cropText(log.getId()),
                    "serverName", log.getServer().getName(),
                    "errorTime", log.getRequestedTime()
                            .atZoneSameInstant(ZoneId.of(log.getServer().getTimezone()))
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "exceptionClass", log.getExceptionClass() != null ? log.getExceptionClass() : "(none)"));
            return AnalysisDto.of(repo.saveAndFlush(new Analysis(log, endpoint, prompt, rendered)));
        });
        runner.run(pending.id()); // after commit, so the runner sees the row
        return pending;
    }

    @Transactional(readOnly = true)
    public AnalysisDto get(long id) {
        return AnalysisDto.of(repo.findWithNamesById(id).orElseThrow(() -> Checks.notFound("Analysis", id)));
    }

    /** Newest first; 404 if the error log does not exist. */
    @Transactional(readOnly = true)
    public List<AnalysisDto> history(String errorLogId) {
        if (!errorLogs.existsById(errorLogId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Error log " + errorLogId + " not found");
        }
        return repo.findByErrorLogIdOrderByCreatedAtDescIdDesc(errorLogId).stream().map(AnalysisDto::of).toList();
    }
}
