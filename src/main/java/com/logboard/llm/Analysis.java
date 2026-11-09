package com.logboard.llm;

import com.logboard.errorlog.ErrorLog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/** One LLM call for an error log: PENDING → DONE | FAILED. */
@Entity
@Table(name = "analyses")
public class Analysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "error_log_id")
    private ErrorLog errorLog;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "llm_endpoint_id")
    private LlmEndpoint llmEndpoint;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prompt_template_id")
    private PromptTemplate promptTemplate;

    private String renderedPrompt;
    private String reply;

    @Enumerated(EnumType.STRING)
    private AnalysisStatus status;

    private String errorMessage;

    @CreationTimestamp
    @Column(updatable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime finishedAt;

    protected Analysis() {}

    Analysis(ErrorLog errorLog, LlmEndpoint llmEndpoint, PromptTemplate promptTemplate, String renderedPrompt) {
        this.errorLog = errorLog;
        this.llmEndpoint = llmEndpoint;
        this.promptTemplate = promptTemplate;
        this.renderedPrompt = renderedPrompt;
        this.status = AnalysisStatus.PENDING;
    }

    void done(String reply) {
        this.status = AnalysisStatus.DONE;
        this.reply = reply;
        this.finishedAt = OffsetDateTime.now();
    }

    void failed(String errorMessage) {
        this.status = AnalysisStatus.FAILED;
        this.errorMessage = errorMessage;
        this.finishedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public ErrorLog getErrorLog() { return errorLog; }
    public LlmEndpoint getLlmEndpoint() { return llmEndpoint; }
    public PromptTemplate getPromptTemplate() { return promptTemplate; }
    public String getRenderedPrompt() { return renderedPrompt; }
    public String getReply() { return reply; }
    public AnalysisStatus getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
}
