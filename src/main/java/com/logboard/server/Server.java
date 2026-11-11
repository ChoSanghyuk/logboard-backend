package com.logboard.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A monitored WAS server (design §5). Its SSH targets are {@link ServerInstance}s (1-20). */
@Entity
@Table(name = "servers")
public class Server {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String rotatedPattern;
    private String logPatternKey;
    private String customLogRegex;
    private String timezone;
    private String charset;
    private OffsetDateTime deletedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRotatedPattern() { return rotatedPattern; }
    public void setRotatedPattern(String rotatedPattern) { this.rotatedPattern = rotatedPattern; }
    public String getLogPatternKey() { return logPatternKey; }
    public void setLogPatternKey(String logPatternKey) { this.logPatternKey = logPatternKey; }
    public String getCustomLogRegex() { return customLogRegex; }
    public void setCustomLogRegex(String customLogRegex) { this.customLogRegex = customLogRegex; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
