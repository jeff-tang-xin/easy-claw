package com.xinl.easyclaw.agent.team;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Spring 容器级装配验证：FileBaseStore 有两个 public 构造器（@Value 装配版 + 测试用
 * Path 版），多构造器时 Spring 无法自动选择，必须 @Autowired 显式标注——否则容器
 * 启动报 {@code No default constructor found}。单测直接 new 不走容器，测不出该故障
 * （2026-10-22 jar 启动事故的回归防护）。
 */
class FileBaseStoreWiringTest {

    @Test
    void springContainerResolvesMultiConstructorBean() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(PropertySourcesPlaceholderConfigurer.class);
            ctx.register(FileBaseStore.class);
            ctx.refresh();
            FileBaseStore store = ctx.getBean(FileBaseStore.class);
            assertNotNull(store, "多构造器 bean 应能经 @Autowired 构造器完成装配");
        }
    }
}
