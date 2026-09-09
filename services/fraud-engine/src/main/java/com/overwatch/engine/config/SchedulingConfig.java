package com.overwatch.engine.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the timer that refreshes rule configuration. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
