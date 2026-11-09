package com.logboard.llm;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI-compatible chat completions: {@code POST {baseUrl}/v1/chat/completions
 * { model, messages: [{ role: "user", content }] }} → {@code choices[0].message.content}.
 * {@code Authorization: Bearer} only when an apiKey is given. {@code timeoutSec} bounds the whole response.
 */
@Component
public class LlmClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_BODY_IN_MESSAGE = 300;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** A failed call, with a message meant for the user. */
    public static class LlmException extends Exception {
        public LlmException(String message) {
            super(message);
        }
    }

    public String chat(String baseUrl, String model, String apiKey, int timeoutSec, String prompt) throws LlmException {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSec));
        String url = baseUrl + "/v1/chat/completions";
        String request = JSON.writeValueAsString(
                Map.of("model", model, "messages", List.of(Map.of("role", "user", "content", prompt))));
        Response res;
        try {
            res = RestClient.builder().requestFactory(factory).build()
                    .post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (apiKey != null) {
                            h.setBearerAuth(apiKey);
                        }
                    })
                    .body(request)
                    .exchange((req, r) -> new Response(r.getStatusCode().value(),
                            new String(r.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException e) {
            throw new LlmException(describe(e, url, timeoutSec));
        }
        if (res.status() == 401 || res.status() == 403) {
            throw new LlmException("Authentication failed (HTTP " + res.status() + "): check the apiKey");
        }
        if (res.status() < 200 || res.status() >= 300) {
            throw new LlmException("LLM endpoint returned HTTP " + res.status() + ": " + abbreviate(res.body()));
        }
        try {
            JsonNode content = JSON.readTree(res.body()).path("choices").path(0).path("message").path("content");
            if (!content.isString()) {
                throw new LlmException("Response is not a chat completion (no choices[0].message.content): "
                        + abbreviate(res.body()));
            }
            return content.asString();
        } catch (JacksonException e) {
            throw new LlmException("Response is not valid JSON: " + abbreviate(res.body()));
        }
    }

    private record Response(int status, String body) {}

    private static String describe(ResourceAccessException e, String url, int timeoutSec) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException) {
                return "No response within " + timeoutSec + "s from " + url;
            }
            if (t instanceof ConnectException) {
                return "Cannot connect to " + url + " (connection refused)";
            }
            if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
                return "Unknown host in " + url;
            }
        }
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return "Request to " + url + " failed: " + (root.getMessage() != null ? root.getMessage()
                : root instanceof IOException ? "I/O error" : root.getClass().getSimpleName());
    }

    private static String abbreviate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_BODY_IN_MESSAGE ? oneLine : oneLine.substring(0, MAX_BODY_IN_MESSAGE) + "…";
    }
}
