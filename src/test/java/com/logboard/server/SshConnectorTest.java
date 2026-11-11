package com.logboard.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.logboard.common.SecretCipher;
import com.logboard.common.LogboardProperties;
import com.logboard.server.ConnectionTestResult.InstanceTestResult;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Network failure mapping without an SSH server. */
class SshConnectorTest {

    final SecretCipher cipher = new SecretCipher(new LogboardProperties(null, new LogboardProperties.Crypto("k"), null, null));
    final SshConnector ssh = new SshConnector(cipher, null, Duration.ofSeconds(1));

    @AfterEach
    void stop() throws Exception {
        ssh.stop();
    }

    ServerInstance server(String host, int port) {
        ServerInstance s = new ServerInstance();
        s.setHost(host);
        s.setSshPort(port);
        s.setUsername("u");
        s.setAuthType(AuthType.PASSWORD);
        s.setSecretEnc(cipher.encrypt("pw"));
        s.setLogPath("/a.log");
        return s;
    }

    @Test
    void silentServerTimesOut() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) { // accepts TCP (backlog) but never speaks SSH
            InstanceTestResult r = ssh.test(server("127.0.0.1", silent.getLocalPort()));
            assertThat(r.ok()).isFalse();
            assertThat(r.logFileFound()).isFalse();
            assertThat(r.message()).startsWith("Timed out after 1s");
        }
    }

    @Test
    void closedPortIsConnectionRefused() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        InstanceTestResult r = ssh.test(server("127.0.0.1", port));
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).isEqualTo("Connection refused by 127.0.0.1:" + port);
    }

    @Test
    void inParallelKeepsOrderAndWaitsForAll() {
        java.util.List<Integer> in = java.util.stream.IntStream.range(0, 20).boxed().toList();
        assertThat(SshConnector.inParallel(in, i -> {
            try {
                Thread.sleep(20 - i);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return i * 2;
        })).containsExactlyElementsOf(in.stream().map(i -> i * 2).toList());
    }

    @Test
    void inParallelRethrowsRuntimeExceptions() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SshConnector.inParallel(java.util.List.of(1, 2), i -> {
            if (i == 2) throw new IllegalArgumentException("boom");
            return i;
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("boom");
    }

    @Test
    void unknownHost() {
        InstanceTestResult r = ssh.test(server("no-such-host.invalid", 22));
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).isEqualTo("Unknown host: no-such-host.invalid");
    }
}
