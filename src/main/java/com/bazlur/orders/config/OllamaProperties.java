package com.bazlur.orders.config;

import module java.base;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orders.ollama")
public record OllamaProperties(URI baseUrl, String model, Duration timeout) {}
