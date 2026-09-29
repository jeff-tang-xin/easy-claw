/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.xinl.easyclaw.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * MVC 函数式端点（RouterFunction）实现的 SPA fallback。
 *
 * <p>Any request that:
 * <ul>
 *   <li>Does NOT start with {@code /api}（全部 REST Controller 都在 /api 下）
 *   <li>Does NOT contain a file extension（静态资源 JS/CSS/图片直接走默认 resource handler）
 *   <li>Is NOT {@code /ws}（WebSocket 握手）
 *   <li>Is NOT {@code /error}（Spring Boot 错误转发：漏掉它会把 API 404/异常
 *       的 /error forward 抢答成 200 + index.html，前端把 HTML 当 JSON 解析直接炸）
 * </ul>
 * is forwarded to {@code /static/index.html} so the React router can handle navigation client-side.
 *
 * <p>响应带 no-cache：与 StaticResourceCacheConfig 的 index.html 策略对齐——
 * 否则浏览器对 / 与深链接走启发式缓存，发版后页面陈旧。
 */
@Configuration
public class WebConfig {

    @Bean
    public RouterFunction<ServerResponse> spaFallback() {
        ClassPathResource indexHtml = new ClassPathResource("/static/index.html");
        return RouterFunctions.route()
                .GET(
                        request -> {
                            String path = request.path();
                            return !path.startsWith("/api")
                                    && !path.contains(".") && !path.contains("/ws")
                                    && !path.equals("/error");
                        },
                        request ->
                                ServerResponse.ok()
                                        .contentType(MediaType.TEXT_HTML)
                                        .cacheControl(CacheControl.noCache())
                                        .body(indexHtml))
                .build();
    }
}
