package com.logboard.server;

import com.logboard.common.SecretCipher;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SSH/SFTP access to registered servers (Apache MINA SSHD): {@link #withSftp} and the connection test.
 * Host keys: trust on first use. The key is checked during key exchange, before any password is sent; the first
 * successful login pins its SHA256 fingerprint on the server, and a different key later fails with a clear message.
 */
@Component
public class SshConnector {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private static final AttributeRepository.AttributeKey<HostKeyCheck> HOST_KEY = new AttributeRepository.AttributeKey<>();

    private final SecretCipher cipher;
    private final ServerRepository servers;
    private final Duration timeout;
    private final SshClient client;

    /** Per connection: the trusted fingerprint (null = first use) and what the server presented. */
    private static final class HostKeyCheck {
        final String expected;
        volatile String presented;

        HostKeyCheck(String expected) {
            this.expected = expected;
        }

        boolean mismatch() {
            return expected != null && presented != null && !expected.equals(presented);
        }
    }

    @Autowired
    public SshConnector(SecretCipher cipher, ServerRepository servers) {
        this(cipher, servers, DEFAULT_TIMEOUT);
    }

    SshConnector(SecretCipher cipher, ServerRepository servers, Duration timeout) {
        this.cipher = cipher;
        this.servers = servers;
        this.timeout = timeout;
        this.client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((session, address, key) -> {
            AttributeRepository ctx = session.getConnectionContext();
            HostKeyCheck check = ctx != null ? ctx.getAttribute(HOST_KEY) : null;
            if (check == null) {
                return false;
            }
            check.presented = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key);
            return !check.mismatch();
        });
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER); // never use this host's ~/.ssh keys
        client.start();
    }

    @PreDestroy
    void stop() throws IOException {
        client.stop();
    }

    /** Connect, authenticate, SFTP-stat the logPath. Every failure becomes {@code ok=false} with a clear message. */
    public ConnectionTestResult test(Server server) {
        try {
            return withSftp(server, sftp -> {
                SftpClient.Attributes attrs = sftp.stat(server.getLogPath());
                if (!attrs.isRegularFile()) {
                    return new ConnectionTestResult(false,
                            "Connected, but logPath is not a regular file: " + server.getLogPath(), false, null);
                }
                return new ConnectionTestResult(true, "Connected to " + target(server) + ". Log file found ("
                        + attrs.getSize() + " bytes). Host key " + server.getHostKeyFingerprint(), true, attrs.getSize());
            });
        } catch (SshFailure e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }

    /** Work done on an open SFTP channel. May throw {@link SshFailure} to report its own message. */
    @FunctionalInterface
    public interface SftpWork<T> {
        T run(SftpClient sftp) throws Exception;
    }

    /** A connect/auth/SFTP failure with a message meant for the user. */
    public static class SshFailure extends RuntimeException {
        public SshFailure(String message) {
            super(message);
        }
    }

    /**
     * Connects, authenticates, opens SFTP and runs {@code work}; closes everything afterwards.
     * @throws SshFailure for every failure (timeout, refused, unknown host, auth, SFTP error), with a clear message
     */
    public <T> T withSftp(Server server, SftpWork<T> work) {
        if (server.getSecretEnc() == null) {
            throw new SshFailure("No password or private key stored for this server");
        }
        String step = "connect";
        HostKeyCheck hostKey = new HostKeyCheck(server.getHostKeyFingerprint());
        ClientSession session = null;
        try {
            session = client.connect(server.getUsername(), server.getHost(), server.getSshPort(),
                    AttributeRepository.ofKeyValuePair(HOST_KEY, hostKey), null).verify(timeout.toMillis()).getSession();
            step = "key";
            String secret = cipher.decrypt(server.getSecretEnc());
            if (server.getAuthType() == AuthType.PASSWORD) {
                session.addPasswordIdentity(secret);
            } else {
                for (KeyPair kp : loadKey(server, secret)) {
                    session.addPublicKeyIdentity(kp);
                }
            }
            step = "auth";
            session.auth().verify(timeout.toMillis());
            if (hostKey.expected == null && hostKey.presented != null) {
                pin(server, hostKey.presented);
            }
            step = "sftp";
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                return work.run(sftp);
            }
        } catch (SshFailure e) {
            throw e;
        } catch (Exception e) {
            if (hostKey.mismatch()) {
                throw new SshFailure("Host key of " + server.getHost() + ":" + server.getSshPort() + " changed: trusted "
                        + hostKey.expected + ", but the server presented " + hostKey.presented + ". If the server was "
                        + "reinstalled, reset the host key for this server and test again; otherwise the connection "
                        + "may be intercepted. No credentials were sent.");
            }
            throw new SshFailure(describe(e, step, target(server), server));
        } finally {
            if (session != null) {
                session.close(true);
            }
        }
    }

    /** Trust on first use. Unsaved servers (no id) are only updated in memory. */
    private void pin(Server server, String fingerprint) {
        if (server.getId() != null && servers != null) {
            servers.pinHostKey(server.getId(), fingerprint);
        }
        server.setHostKeyFingerprint(fingerprint);
    }

    private static String target(Server server) {
        return server.getUsername() + "@" + server.getHost() + ":" + server.getSshPort();
    }

    private static Iterable<KeyPair> loadKey(Server server, String privateKey)
            throws IOException, GeneralSecurityException {
        Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(server.getName()),
                new ByteArrayInputStream(privateKey.getBytes(StandardCharsets.UTF_8)), null);
        if (keys == null || !keys.iterator().hasNext()) {
            throw new GeneralSecurityException("no key found");
        }
        return keys;
    }

    private String describe(Exception e, String step, String target, Server server) {
        if (isTimeout(e)) {
            return "Timed out after " + timeout.toSeconds() + "s ("
                    + (step.equals("connect") ? "connecting to " : "talking to ") + target + ")";
        }
        return switch (step) {
            case "connect" -> {
                Throwable root = rootCause(e);
                if (root instanceof UnknownHostException || root instanceof UnresolvedAddressException) {
                    yield "Unknown host: " + server.getHost();
                }
                if (root instanceof ConnectException) {
                    yield "Connection refused by " + server.getHost() + ":" + server.getSshPort();
                }
                yield "Cannot connect to " + target + ": " + message(root);
            }
            case "key" -> "Private key could not be read (OpenSSH or PEM format without passphrase expected)";
            case "auth" -> "Authentication failed for " + target + " (check username and "
                    + (server.getAuthType() == AuthType.PASSWORD ? "password" : "private key") + ")";
            default -> {
                if (e instanceof SftpException sftpEx) {
                    if (sftpEx.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                        yield "Connected, but log file not found: " + server.getLogPath();
                    }
                    if (sftpEx.getStatus() == SftpConstants.SSH_FX_PERMISSION_DENIED) {
                        yield "Connected, but permission denied for " + server.getLogPath();
                    }
                }
                yield "Connected, but SFTP failed: " + message(rootCause(e));
            }
        };
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t instanceof SocketTimeoutException
                    || t instanceof InterruptedByTimeoutException
                    || (t.getMessage() != null && t.getMessage().toLowerCase().contains("timeout"))) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private static String message(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
