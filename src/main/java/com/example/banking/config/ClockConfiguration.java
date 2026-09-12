package com.example.banking.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    /** Every business timestamp comes from this clock, so tests can control time (day boundaries, expiry). */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
