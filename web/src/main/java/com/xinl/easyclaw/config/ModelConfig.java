package com.xinl.easyclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope 配置
 * <p>
 * AgentScope 2.0 使用 ModelRegistry 自动解析字符串模型 ID（如 "openai:gpt-4o-mini"），
 * 无需手动构建 Model Bean。API Key 通过环境变量自动读取。
 */
@Configuration
@EnableConfigurationProperties(AgentScopeProperties.class)
public class ModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

    public ModelConfig(AgentScopeProperties props) {
        log.info("AgentScope 配置加载完成: provider={}, modelName={}",
                props.getModel().getProvider(), props.getModel().getModelName());
        log.info("请设置环境变量 {}_API_KEY 以使用对应模型",
                props.getModel().getProvider().toUpperCase());
    }
}
