package com.xinl.easyclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

@Configuration
public class HttpDebugConfig {

    private static final Logger log = LoggerFactory.getLogger("HTTP");

    @Bean
    @ConditionalOnProperty(name = "easy-claw.http.debug", havingValue = "true", matchIfMissing = true)
    public RestClient.Builder restClientDebugBuilder() {
        return RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory())
                .requestInterceptor((request, body, execution) -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("\n========== HTTP REQUEST ==========\n");
                    sb.append("URL: ").append(request.getMethod()).append(' ').append(request.getURI()).append('\n');
                    sb.append("Headers:\n");
                    request.getHeaders().forEach((k, v) -> {
                        if (k.equalsIgnoreCase("Authorization")) {
                            sb.append("  ").append(k).append(": Bearer ****").append('\n');
                        } else {
                            sb.append("  ").append(k).append(": ").append(v).append('\n');
                        }
                    });
                    if (body != null && body.length > 0) {
                        sb.append("Body: ").append(new String(body, StandardCharsets.UTF_8)).append('\n');
                    }
                    sb.append("==================================");
                    log.info(sb.toString());
                    return execution.execute(request, body);
                });
    }
}
