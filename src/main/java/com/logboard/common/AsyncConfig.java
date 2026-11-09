package com.logboard.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** {@code @Async} (LLM analyses) on Spring Boot's applicationTaskExecutor. */
@Configuration
@EnableAsync
public class AsyncConfig {}
