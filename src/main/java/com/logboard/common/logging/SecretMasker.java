package com.logboard.common.logging;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets in log output (see {@link MaskingEncoder}). Two layers:
 * <ul>
 *   <li>Known values: every secret that passes through SecretCipher (SSH passwords/keys, LLM apiKeys), the master key
 *       and the MCP token are registered and replaced wherever they appear.</li>
 *   <li>Patterns: {@code Bearer …}, {@code password=…} / {@code "apiKey":"…"}-style pairs (also JSON-escaped), and
 *       PEM private key blocks.</li>
 * </ul>
 */
public final class SecretMasker {

    public static final String MASK = "****";
    /** Shorter values are only masked by the patterns (literal masking would hit ordinary words). */
    static final int MIN_LITERAL_LENGTH = 8;
    private static final int MAX_KNOWN = 10_000;

    private static final Set<String> KNOWN = ConcurrentHashMap.newKeySet();

    private static final String KEYS = "(?:password|passwd|pwd|privateKey|private_key|private-key|apiKey|api_key|api-key"
            + "|secret|clientSecret|token|accessToken|masterKey|master_key|master-key|passphrase)";
    private static final List<Pattern> QUOTED = List.of(Pattern.compile(
            "(?i)(" + KEYS + "\\\\?\"?\\s*[:=]\\s*\\\\?\")([^\"\\\\]+)(\\\\?\")"));
    private static final Pattern UNQUOTED = Pattern.compile(
            "(?i)(" + KEYS + "\\\\?\"?\\s*[:=]\\s*)([^\\s\"\\\\,;}&)\\]]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]{4,}");
    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]*)PRIVATE KEY-----.*?-----END \\1PRIVATE KEY-----", Pattern.DOTALL);
    /** A BEGIN header whose END is missing: mask the rest of the event. */
    private static final Pattern PEM_OPEN = Pattern.compile(
            "(-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----)(?!\\Q" + MASK + "\\E-----END)[\\s\\S]*");

    private SecretMasker() {}

    /** Registers a secret value so it is masked wherever it appears in logs. */
    public static void register(String secret) {
        if (secret == null || secret.length() < MIN_LITERAL_LENGTH || KNOWN.size() >= MAX_KNOWN) {
            return;
        }
        KNOWN.add(secret);
        String trimmed = secret.strip();
        if (!trimmed.equals(secret) && trimmed.length() >= MIN_LITERAL_LENGTH) {
            KNOWN.add(trimmed);
        }
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (String secret : KNOWN) {
            if (out.contains(secret)) {
                out = out.replace(secret, MASK);
            }
        }
        out = PEM.matcher(out).replaceAll(m ->
                Matcher.quoteReplacement("-----BEGIN " + m.group(1) + "PRIVATE KEY-----" + MASK
                        + "-----END " + m.group(1) + "PRIVATE KEY-----"));
        out = PEM_OPEN.matcher(out).replaceAll("$1" + MASK);
        out = BEARER.matcher(out).replaceAll("$1" + MASK);
        for (Pattern p : QUOTED) {
            out = p.matcher(out).replaceAll("$1" + MASK + "$3");
        }
        out = UNQUOTED.matcher(out).replaceAll(m -> m.group(2).equals(MASK) ? Matcher.quoteReplacement(m.group())
                : Matcher.quoteReplacement(m.group(1) + MASK));
        return out;
    }
}
