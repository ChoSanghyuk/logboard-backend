package com.logboard.llm;

import com.logboard.errorlog.ErrorLog;
import com.logboard.errorlog.ErrorLogRepository;
import com.logboard.errorlog.ErrorLogStatusMachine;
import com.logboard.errorlog.ErrorLogStatusMachine.Action;
import com.logboard.llm.LlmClient.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one analysis outside any transaction: LLM call → DONE (error log → ANALYZED when the status machine allows it,
 * e.g. not after a terminate in the meantime) or FAILED (error log unchanged). On startup, analyses left PENDING by a
 * previous run are marked FAILED.
 */
@Component
public class AnalysisRunner implements SmartInitializingSingleton {

    static final String INTERRUPTED = "Interrupted: the server restarted before the LLM replied";
    private static final Logger log = LoggerFactory.getLogger(AnalysisRunner.class);

    private final AnalysisRepository repo;
    private final ErrorLogRepository errorLogs;
    private final LlmEndpointService endpoints;
    private final LlmClient llm;
    private final TransactionTemplate tx;

    public AnalysisRunner(AnalysisRepository repo, ErrorLogRepository errorLogs, LlmEndpointService endpoints,
            LlmClient llm, TransactionTemplate tx) {
        this.repo = repo;
        this.errorLogs = errorLogs;
        this.endpoints = endpoints;
        this.llm = llm;
        this.tx = tx;
    }

    private record Call(String baseUrl, String model, String apiKey, int timeoutSec, String prompt) {}

    @Async
    public void run(long analysisId) {
        String reply = null;
        String error = null;
        try {
            Call call = tx.execute(s -> {
                Analysis a = repo.findById(analysisId).orElseThrow();
                LlmEndpoint e = a.getLlmEndpoint();
                return new Call(e.getBaseUrl(), e.getModel(), endpoints.apiKey(e), e.getTimeoutSec(), a.getRenderedPrompt());
            });
            reply = llm.chat(call.baseUrl(), call.model(), call.apiKey(), call.timeoutSec(), call.prompt());
        } catch (LlmException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            log.error("Analysis {} failed unexpectedly", analysisId, e);
            error = "Unexpected error: " + e.getMessage();
        }
        finish(analysisId, reply, error);
    }

    private void finish(long analysisId, String reply, String error) {
        tx.executeWithoutResult(s -> {
            Analysis a = repo.findById(analysisId).orElseThrow();
            if (error != null) {
                a.failed(error);
                return;
            }
            a.done(reply);
            ErrorLog errorLog = errorLogs.findForUpdate(a.getErrorLog().getId()).orElseThrow();
            if (ErrorLogStatusMachine.allowed(errorLog.getStatus(), Action.ANALYSIS_DONE)) {
                ErrorLogStatusMachine.apply(errorLog, Action.ANALYSIS_DONE);
            }
        });
    }

    @Override
    public void afterSingletonsInstantiated() {
        failInterrupted();
    }

    /** Marks every PENDING analysis FAILED. Runs before the web server accepts requests. */
    int failInterrupted() {
        Integer n = tx.execute(s -> repo.failAllPending(INTERRUPTED));
        if (n != null && n > 0) {
            log.warn("Marked {} interrupted analyses as FAILED", n);
        }
        return n == null ? 0 : n;
    }
}
