package com.xinl.easyclaw.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Easy-Claw 云端 hub：主子架构的控制面服务。
 *
 * <p>独立于 agent 运行时（agent-core/agents/modes），提供账号/组织/项目与后续
 * LLM 网关、内容服务、blackboard 等云端能力；自带 React 控制台（frontend/ 产物进 static/）。
 */
@SpringBootApplication
public class HubApplication {

    public static void main(String[] args) {
        SpringApplication.run(HubApplication.class, args);
    }
}
