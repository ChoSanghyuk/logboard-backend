package com.logboard.mcp;

import com.logboard.common.LogboardProperties;
import com.logboard.common.logging.SecretMasker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Authorization: Bearer <logboard.mcp.token>} is required on /mcp/** (design §8). Anything else → 401.
 * The token is compared in constant time. A blank token can't happen: startup fails (LogboardProperties @NotBlank).
 */
public class McpTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final byte[] token;

    public McpTokenFilter(LogboardProperties props) {
        this.token = props.mcp().token().getBytes(StandardCharsets.UTF_8);
        SecretMasker.register(props.mcp().token());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        byte[] presented = header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())
                ? header.substring(PREFIX.length()).trim().getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        if (!MessageDigest.isEqual(presented, token)) { // constant time for equal-length inputs
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"logboard-mcp\"");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid bearer token for /mcp\",\"details\":{}}");
            return;
        }
        chain.doFilter(request, response);
    }
}
