package com.logboard.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A monitored WAS server reached over SSH/SFTP (design §5). */
@Entity
@Table(name = "servers")
public class Server {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String host;
    private int sshPort;
    private String username;

    @Enumerated(EnumType.STRING)
    private AuthType authType;

    /** AES-GCM ciphertext (see SecretCipher) of the password or private key, depending on authType. */
    private String secretEnc;

    private String logPath;
    private String rotatedPattern;
    private String logPatternKey;
    private String customLogRegex;
    private String timezone;
    private String charset;
    private OffsetDateTime deletedAt;
    /** Trusted host key ("SHA256:…"), pinned on the first successful login; null = not trusted yet. */
    private String hostKeyFingerprint;

    @CreationTimestamp
    @Column(updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getSshPort() { return sshPort; }
    public void setSshPort(int sshPort) { this.sshPort = sshPort; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public AuthType getAuthType() { return authType; }
    public void setAuthType(AuthType authType) { this.authType = authType; }
    public String getSecretEnc() { return secretEnc; }
    public void setSecretEnc(String secretEnc) { this.secretEnc = secretEnc; }
    public String getLogPath() { return logPath; }
    public void setLogPath(String logPath) { this.logPath = logPath; }
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
    public String getHostKeyFingerprint() { return hostKeyFingerprint; }
    public void setHostKeyFingerprint(String hostKeyFingerprint) { this.hostKeyFingerprint = hostKeyFingerprint; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
