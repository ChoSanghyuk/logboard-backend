package com.logboard.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.logboard.llm.LlmClient.LlmException;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** OpenAI-compatible chat completions against WireMock. */
class LlmClientTest {

    static final WireMockServer wm = new WireMockServer(options().dynamicPort());
    static final String PATH = "/v1/chat/completions";
    static final String OK = """
            {"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"## Cause\\nNPE"}}]}""";

    final LlmClient client = new LlmClient();

    @BeforeAll
    static void start() {
        wm.start();
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    @BeforeEach
    void reset() {
        wm.resetAll();
    }

    String base() {
        return "http://localhost:" + wm.port();
    }

    @Test
    void successSendsModelPromptAndBearer() throws Exception {
        wm.stubFor(post(PATH).willReturn(okJson(OK)));
        assertThat(client.chat(base(), "qwen", "sk-1", 5, "explain")).isEqualTo("## Cause\nNPE");
        wm.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer sk-1"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"model":"qwen","messages":[{"role":"user","content":"explain"}]}""")));
    }

    @Test
    void withoutApiKeyNoAuthorizationHeader() throws Exception {
        wm.stubFor(post(PATH).willReturn(okJson(OK)));
        client.chat(base(), "qwen", null, 5, "explain");
        wm.verify(postRequestedFor(urlEqualTo(PATH)).withoutHeader("Authorization"));
    }

    @Test
    void plainHttp11NoH2cUpgrade() throws Exception {
        // vLLM/uvicorn drops the body of an h2c upgrade request (B11)
        wm.stubFor(post(PATH).willReturn(okJson(OK)));
        client.chat(base(), "qwen", null, 5, "explain");
        wm.verify(postRequestedFor(urlEqualTo(PATH))
                .withoutHeader("Upgrade")
                .withoutHeader("HTTP2-Settings")
                .withRequestBody(equalToJson("""
                        {"model":"qwen","messages":[{"role":"user","content":"explain"}]}""")));
    }

    @Test
    void timeout() {
        wm.stubFor(post(PATH).willReturn(okJson(OK).withFixedDelay(3000)));
        assertThatThrownBy(() -> client.chat(base(), "m", null, 1, "p"))
                .isInstanceOf(LlmException.class).hasMessage("No response within 1s from " + base() + PATH);
    }

    @Test
    void serverError() {
        wm.stubFor(post(PATH).willReturn(aResponse().withStatus(500).withBody("{\"error\":\"model overloaded\"}")));
        assertThatThrownBy(() -> client.chat(base(), "m", null, 5, "p"))
                .hasMessage("LLM endpoint returned HTTP 500: {\"error\":\"model overloaded\"}");
    }

    @Test
    void authFailure() {
        wm.stubFor(post(PATH).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> client.chat(base(), "m", "bad", 5, "p"))
                .hasMessage("Authentication failed (HTTP 401): check the apiKey");
    }

    @Test
    void malformedResponses() {
        wm.stubFor(post(PATH).willReturn(aResponse().withStatus(200).withBody("<html>gateway</html>")));
        assertThatThrownBy(() -> client.chat(base(), "m", null, 5, "p"))
                .hasMessage("Response is not valid JSON: <html>gateway</html>");

        wm.stubFor(post(PATH).willReturn(okJson("{\"choices\":[]}")));
        assertThatThrownBy(() -> client.chat(base(), "m", null, 5, "p"))
                .hasMessageStartingWith("Response is not a chat completion (no choices[0].message.content)");

        wm.stubFor(post(PATH).willReturn(okJson("{\"choices\":[{\"message\":{\"content\":null}}]}")));
        assertThatThrownBy(() -> client.chat(base(), "m", null, 5, "p"))
                .hasMessageStartingWith("Response is not a chat completion");
    }

    @Test
    void connectionRefused() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        assertThatThrownBy(() -> client.chat("http://localhost:" + port, "m", null, 5, "p"))
                .hasMessage("Cannot connect to http://localhost:" + port + PATH + " (connection refused)");
    }
}
