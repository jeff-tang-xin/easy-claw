package com.xinl.easyclaw.config;

import com.xinl.easyclaw.interceptor.entity.InterceptorConfigEntity;
import com.xinl.easyclaw.interceptor.service.InterceptorManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 拦截器配置初始化
 * <p>
 * 应用启动时初始化默认拦截器配置
 */
@Configuration
public class InterceptorConfig {

    private static final Logger log = LoggerFactory.getLogger(InterceptorConfig.class);

    @Bean
    public CommandLineRunner initDefaultInterceptors(InterceptorManagementService interceptorService) {
        return args -> {
            if (interceptorService.findAll().isEmpty()) {
                log.info("初始化默认拦截器配置...");

                interceptorService.create(InterceptorConfigEntity.builder()
                        .name("permission-default")
                        .type("PERMISSION")
                        .orderNum(1)
                        .enabled(true)
                        .config("{\"roles\": [\"USER\", \"ADMIN\"], \"deniedTools\": []}")
                        .conditions("true")
                        .build());

                interceptorService.create(InterceptorConfigEntity.builder()
                        .name("audit-default")
                        .type("AUDIT")
                        .orderNum(2)
                        .enabled(true)
                        .config("{\"logLevel\": \"INFO\", \"logBody\": true}")
                        .conditions("true")
                        .build());

                interceptorService.create(InterceptorConfigEntity.builder()
                        .name("rate-limit-default")
                        .type("RATE_LIMIT")
                        .orderNum(3)
                        .enabled(false)
                        .config("{\"maxRequestsPerMinute\": 60, \"burstSize\": 10}")
                        .conditions("true")
                        .build());

                log.info("默认拦截器初始化完成");
            }
        };
    }
}
