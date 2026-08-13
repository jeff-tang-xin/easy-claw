package com.xinl.easyclaw.agent.embabel.subagent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.core.CoreToolGroups;
import com.embabel.agent.domain.io.UserInput;
import com.xinl.easyclaw.agent.embabel.domain.ChatResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

/**
 * 网络搜索子智能体
 * <p>
 * 职责：处理所有需要联网的任务——搜索最新信息、抓取网页内容、获取实时数据。
 * <p>
 * 工具依赖：CoreToolGroups.WEB（需配置 BRAVE_SEARCH_API_KEY）。
 */
@Agent(
        name = "web-agent",
        description = "专注网络信息检索的 AI 智能体：搜索引擎查询、网页内容抓取、实时数据获取"
)
public class WebAgent {

    private static final Logger log = LoggerFactory.getLogger(WebAgent.class);

    private final boolean enableWeb;

    public WebAgent(
            @Value("${easy-claw.tools.web.enabled:true}") boolean enableWeb) {
        this.enableWeb = enableWeb;
    }

    @Action(
            description = "分析搜索意图，确定查询策略",
            pre = {"user_request_available"}
    )
    public UserInput understandQuery(UserInput input, OperationContext context) {
        log.debug("WebAgent.understandQuery: {}", input.getContent());
        return input;
    }

    @Action(
            description = "执行网络搜索和网页抓取",
            pre = {"user_request_available"},
            post = {"information_retrieved"},
            readOnly = true
    )
    public ChatResult searchWeb(UserInput input, OperationContext context) {
        if (!enableWeb) {
            return new ChatResult(
                    "🌐 网络搜索未启用。请设置 `easy-claw.tools.web.enabled=true` 并配置 `BRAVE_SEARCH_API_KEY` 环境变量。",
                    List.of("web-disabled"),
                    "default"
            );
        }

        String reply = context.ai()
                .withDefaultLlm()
                .withSystemPrompt("""
                        你是 WebAgent，一个专业的信息检索助手。
                        
                        可用工具：
                        - web.search(query, numResults): 搜索网页
                        - fetch.url(url): 抓取指定 URL 的内容
                        
                        回答规范：
                        1. 综合多源信息，给出准确、有依据的回答
                        2. 每个关键论点标注信息来源（URL）
                        3. 如果信息存在矛盾，列出不同观点
                        4. 注意时效性——标注信息获取时间
                        5. 使用中文回答，除非用户明确要求其他语言
                        """)
                .withToolGroup(CoreToolGroups.WEB)
                .creating(String.class)
                .fromPrompt("请搜索并回答以下问题，标注信息来源：\n\n" + input.getContent());

        return new ChatResult(
                reply,
                List.of("网络搜索: " + input.getContent().substring(0, Math.min(40, input.getContent().length()))),
                "default"
        );
    }
}
