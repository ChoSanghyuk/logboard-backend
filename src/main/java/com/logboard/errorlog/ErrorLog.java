package com.logboard.errorlog;

import com.logboard.server.Server;
import com.logboard.server.ServerInstance;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;

/** A cropped exception (design §5). Status changes go through {@link ErrorLogStatusMachine} only. */
@Entity
@Table(name = "error_logs")
public class ErrorLog implements Persistable<String> {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_id")
    private Server server;

    /** Instance whose raw copy holds the current anchor/crop ({@code rawFilePath}, {@code totalLines}, lines). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instance_id")
    private ServerInstance instance;

    private OffsetDateTime requestedTime;
    private int windowMin;
    /** Relative to {@code logboard.storage.root}. */
    private String rawFilePath;
    private int cropStartLine;
    private int cropEndLine;
    private int anchorLine;
    private int anchorEndLine;
    private int totalLines;
    /** JSON array of {@link com.logboard.crop.Candidate} (of all instances, each with its instanceId). */
    private String candidates;
    /** JSON object instanceId → {@link RawFile}: one raw copy per fetched instance. */
    private String rawFiles;
    /** JSON array of {@link FetchFailure}; "[]" if every instance was fetched. */
    private String fetchFailures;
    private String exceptionClass;
    private String exceptionMessage;

    @Enumerated(EnumType.STRING)
    private ErrorStatus status;

    private String note;

    @Enumerated(EnumType.STRING)
    private ResolvedBy resolvedBy;

    private String resolution;
    private OffsetDateTime resolvedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    @Transient
    private boolean isNew;

    protected ErrorLog() {}

    ErrorLog(String id, Server server, ServerInstance instance, OffsetDateTime requestedTime, int windowMin,
            String rawFilePath, int totalLines, String candidates, String rawFiles, String fetchFailures) {
        this.id = id;
        this.server = server;
        this.instance = instance;
        this.requestedTime = requestedTime;
        this.windowMin = windowMin;
        this.rawFilePath = rawFilePath;
        this.totalLines = totalLines;
        this.candidates = candidates;
        this.rawFiles = rawFiles;
        this.fetchFailures = fetchFailures;
        this.status = ErrorStatus.CROPPED;
        this.isNew = true;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    @Override
    public String getId() { return id; }
    public Server getServer() { return server; }
    public ServerInstance getInstance() { return instance; }
    public OffsetDateTime getRequestedTime() { return requestedTime; }
    public int getWindowMin() { return windowMin; }
    public String getRawFilePath() { return rawFilePath; }
    public int getCropStartLine() { return cropStartLine; }
    public int getCropEndLine() { return cropEndLine; }
    public int getAnchorLine() { return anchorLine; }
    public int getAnchorEndLine() { return anchorEndLine; }
    public int getTotalLines() { return totalLines; }
    public String getCandidates() { return candidates; }
    public String getRawFiles() { return rawFiles; }
    public String getFetchFailures() { return fetchFailures; }
    public String getExceptionClass() { return exceptionClass; }
    public String getExceptionMessage() { return exceptionMessage; }
    public ErrorStatus getStatus() { return status; }
    void setStatus(ErrorStatus status) { this.status = status; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public ResolvedBy getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(ResolvedBy resolvedBy) { this.resolvedBy = resolvedBy; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    /** Moves the crop to another instance's raw copy (candidate switch). */
    void setInstance(ServerInstance instance, String rawFilePath, int totalLines) {
        this.instance = instance;
        this.rawFilePath = rawFilePath;
        this.totalLines = totalLines;
    }

    /** Sets the crop range and the anchor candidate it is built around. */
    void setCrop(int startLine, int endLine, int anchorLine, int anchorEndLine, String exceptionClass,
            String exceptionMessage) {
        this.cropStartLine = startLine;
        this.cropEndLine = endLine;
        this.anchorLine = anchorLine;
        this.anchorEndLine = anchorEndLine;
        this.exceptionClass = exceptionClass;
        this.exceptionMessage = exceptionMessage;
    }
}
