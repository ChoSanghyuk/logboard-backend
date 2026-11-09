package com.logboard.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.logboard.common.SecretCipher;
import com.logboard.common.LogboardProperties;
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

    Server server(String host, int port) {
        Server s = new Server();
        s.setName("t");
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
            ConnectionTestResult r = ssh.test(server("127.0.0.1", silent.getLocalPort()));
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
        ConnectionTestResult r = ssh.test(server("127.0.0.1", port));
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).isEqualTo("Connection refused by 127.0.0.1:" + port);
    }

    @Test
    void unknownHost() {
        ConnectionTestResult r = ssh.test(server("no-such-host.invalid", 22));
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).isEqualTo("Unknown host: no-such-host.invalid");
    }
}
