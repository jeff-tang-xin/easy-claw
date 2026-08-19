package com.xinl.easyclaw.agent.embabel;

import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.scope.AgentScopeBuilder;
import com.embabel.agent.core.AgentPlatform;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RoleAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(RoleAgentFactory.class);

    private final AgentPlatform platform;
    private final ApplicationContext applicationContext;
    private final Map<String, com.embabel.agent.core.Agent> agentCache = new LinkedHashMap<>();

    public RoleAgentFactory(AgentPlatform platform, ApplicationContext applicationContext) {
        this.platform = platform;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        Map<String, Object> agentBeans = applicationContext.getBeansWithAnnotation(Agent.class);
        for (Map.Entry<String, Object> entry : agentBeans.entrySet()) {
            Object bean = entry.getValue();
            Class<?> clazz = bean.getClass();
            Agent agentAnn = AnnotationUtils.findAnnotation(clazz, Agent.class);
            if (agentAnn == null) continue;
            if (!agentAnn.scan()) continue;

            String agentName = agentAnn.name();
            if (agentName == null || agentName.isBlank()) {
                agentName = clazz.getSimpleName();
            }
            String agentType = agentName.replaceAll("-agent$", "").toLowerCase();

            register(agentName, bean);
            register(agentType, bean);

            if ("orchestrator-agent".equals(agentName)) {
                register("orchestrator", bean);
                register("default", bean);
            }

            log.debug("注册 Agent: name={}, type={}, class={}", agentName, agentType, clazz.getSimpleName());
        }
        log.info("RoleAgentFactory 已注册 {} 个 Agent 映射: {}", agentCache.size(), agentCache.keySet());
    }

    private void register(String roleName, Object pojo) {
        if (agentCache.containsKey(roleName.toLowerCase())) return;
        com.embabel.agent.core.Agent agent = AgentScopeBuilder.fromInstance(pojo)
                .createAgentScope()
                .createAgent("easy-claw", "2.0.0", "xinl");
        agentCache.put(roleName.toLowerCase(), agent);
        platform.deploy(agent);
    }

    public com.embabel.agent.core.Agent resolveAgent(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return agentCache.get("orchestrator");
        }
        com.embabel.agent.core.Agent agent = agentCache.get(skillName.toLowerCase());
        if (agent != null) {
            return agent;
        }
        log.warn("未找到 skill/role 对应的 Agent: '{}'，回退到 orchestrator", skillName);
        return agentCache.get("orchestrator");
    }
}
