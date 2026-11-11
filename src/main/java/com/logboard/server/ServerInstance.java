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

/** One (host, port, log path, login) of a server (multi-instance design §3). Soft-deleted when error logs refer to it. */
@Entity
@Table(name = "server_instances")
public class ServerInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long serverId;
    /** Order given by the user (display order, anchor tie-break). */
    private int position;
    private String host;
    private int sshPort;
    private String logPath;
    private String username;

    @Enumerated(EnumType.STRING)
    private AuthType authType;

    /** AES-GCM ciphertext (see SecretCipher) of the password or private key, depending on authType. */
    private String secretEnc;

    /** Trusted host key ("SHA256:…"), pinned on the first successful login; null = not trusted yet. */
    private String hostKeyFingerprint;

    private OffsetDateTime deletedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public long getServerId() { return serverId; }
    public void setServerId(long serverId) { this.serverId = serverId; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getSshPort() { return sshPort; }
    public void setSshPort(int sshPort) { this.sshPort = sshPort; }
    public String getLogPath() { return logPath; }
    public void setLogPath(String logPath) { this.logPath = logPath; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public AuthType getAuthType() { return authType; }
    public void setAuthType(AuthType authType) { this.authType = authType; }
    public String getSecretEnc() { return secretEnc; }
    public void setSecretEnc(String secretEnc) { this.secretEnc = secretEnc; }
    public String getHostKeyFingerprint() { return hostKeyFingerprint; }
    public void setHostKeyFingerprint(String hostKeyFingerprint) { this.hostKeyFingerprint = hostKeyFingerprint; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
