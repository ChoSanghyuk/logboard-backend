package com.logboard.mcp;

import com.logboard.common.LogboardProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class McpConfig {

    /** Runs first on /mcp and everything below it. */
    @Bean
    FilterRegistrationBean<McpTokenFilter> mcpTokenFilter(LogboardProperties props) {
        FilterRegistrationBean<McpTokenFilter> reg = new FilterRegistrationBean<>(new McpTokenFilter(props));
        reg.addUrlPatterns("/mcp", "/mcp/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
}
