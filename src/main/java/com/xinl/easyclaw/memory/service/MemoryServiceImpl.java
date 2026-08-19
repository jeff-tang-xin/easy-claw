package com.xinl.easyclaw.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.memory.config.MemoryProperties;
import com.xinl.easyclaw.memory.entity.PropositionEntity;
import com.xinl.easyclaw.memory.entity.PropositionType;
import com.xinl.easyclaw.memory.repository.PropositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 长期记忆服务实现
 * <p>
 * 召回评分基于多维度加权（无向量依赖）：
 * <ul>
 *   <li>recency - 时间近因：越久未访问分越低</li>
 *   <li>content - 内容相关：查询词与命题内容的重叠度</li>
 *   <li>context - 上下文匹配：话题标签的重叠度</li>
 *   <li>emotional - 情感强度：情感标签数量</li>
 *   <li>associative - 关联强度：引用次数</li>
 *   <li>insight - 洞察价值：置信度</li>
 * </ul>
 */
@Service
public class MemoryServiceImpl implements MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryServiceImpl.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final PropositionRepository repository;
    private final MemoryProperties properties;

    public MemoryServiceImpl(PropositionRepository repository, MemoryProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    @Transactional
    public PropositionEntity remember(
            String userId,
            String workspaceId,
            String content,
            PropositionType type,
            Double confidence,
            List<String> emotionTags,
            List<String> topics,
            String conversationId
    ) {
        PropositionEntity entity = PropositionEntity.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .content(content)
                .type(type)
                .confidence(confidence)
                .emotionTagsJson(toJson(emotionTags))
                .topicsJson(toJson(topics))
                .conversationId(conversationId)
                .build();
        return repository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PropositionEntity> recall(String userId, String workspaceId, String queryText, List<String> topics) {
        List<PropositionEntity> candidates = repository.findByUserId(userId).stream()
                .filter(p -> workspaceId == null || workspaceId.isBlank() || workspaceId.equals(p.getWorkspaceId()))
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }

        Set<String> queryTokens = tokenize(queryText);
        Set<String> topicFilter = topics != null ? Set.copyOf(topics) : Set.of();

        MemoryProperties.Weights w = properties.getWeights();
        int limit = properties.getRecallLimit();
        Instant now = Instant.now();

        return candidates.stream()
                .map(p -> new Scored(p, score(p, queryTokens, topicFilter, w, now)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(limit)
                .map(Scored::entity)
                .toList();
    }

    @Override
    @Transactional
    public void touch(Long propositionId) {
        repository.findById(propositionId).ifPresent(p -> {
            p.setLastAccessed(Instant.now());
            int ref = p.getReferenceCount() != null ? p.getReferenceCount() : 0;
            p.setReferenceCount(ref + 1);
            repository.save(p);
        });
    }

    @Override
    @Transactional
    public int forget(String userId, String workspaceId) {
        Instant threshold = Instant.now().minus(properties.getForgetDays(), ChronoUnit.DAYS);
        List<PropositionEntity> stale = repository
                .findByUserIdAndLastAccessedBeforeAndReferenceCountLessThan(
                        userId, threshold, properties.getMinReferenceCount()
                ).stream()
                .filter(p -> workspaceId == null || workspaceId.isBlank() || workspaceId.equals(p.getWorkspaceId()))
                .toList();
        if (stale.isEmpty()) {
            return 0;
        }
        repository.deleteAll(stale);
        log.info("遗忘清理: userId={}, 清理 {} 条过期记忆", userId, stale.size());
        return stale.size();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(String userId, String workspaceId) {
        return repository.findByUserId(userId).stream()
                .filter(p -> workspaceId == null || workspaceId.isBlank() || workspaceId.equals(p.getWorkspaceId()))
                .count();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PropositionEntity> findRecent(String userId, String workspaceId, Instant since) {
        return repository.findRecentByUserId(userId, since).stream()
                .filter(p -> workspaceId == null || workspaceId.isBlank() || workspaceId.equals(p.getWorkspaceId()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PropositionEntity> findByUserId(String userId, String workspaceId) {
        return repository.findByUserId(userId).stream()
                .filter(p -> workspaceId == null || workspaceId.isBlank() || workspaceId.equals(p.getWorkspaceId()))
                .toList();
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("记忆不存在: id=" + id);
        }
        repository.deleteById(id);
        log.info("删除记忆: id={}", id);
    }

    // ==================== 评分逻辑 ====================

    private double score(
            PropositionEntity p,
            Set<String> queryTokens,
            Set<String> topicFilter,
            MemoryProperties.Weights w,
            Instant now
    ) {
        List<String> pTopics = fromJson(p.getTopicsJson());
        if (!topicFilter.isEmpty()) {
            boolean hasTopic = pTopics.stream().anyMatch(topicFilter::contains);
            if (!hasTopic) {
                return -1.0;
            }
        }

        double recencyScore = scoreRecency(p, now);
        double contentScore = scoreContent(p, queryTokens);
        double contextScore = scoreContext(p, topicFilter);
        double emotionalScore = scoreEmotional(p);
        double associativeScore = scoreAssociative(p);
        double insightScore = scoreInsight(p);

        return w.getRecency() * recencyScore
                + w.getContent() * contentScore
                + w.getContext() * contextScore
                + w.getEmotional() * emotionalScore
                + w.getAssociative() * associativeScore
                + w.getInsight() * insightScore;
    }

    private double scoreRecency(PropositionEntity p, Instant now) {
        Instant lastAccessed = p.getLastAccessed();
        if (lastAccessed == null) {
            return 0.0;
        }
        long daysElapsed = ChronoUnit.DAYS.between(lastAccessed, now);
        if (daysElapsed < 0) {
            return 1.0;
        }
        return Math.max(0.0, 1.0 - (daysElapsed / 30.0));
    }

    private double scoreContent(PropositionEntity p, Set<String> queryTokens) {
        if (queryTokens.isEmpty() || p.getContent() == null) {
            return 0.0;
        }
        Set<String> contentTokens = tokenize(p.getContent());
        if (contentTokens.isEmpty()) {
            return 0.0;
        }
        long overlap = contentTokens.stream().filter(queryTokens::contains).count();
        return (double) overlap / queryTokens.size();
    }

    private double scoreContext(PropositionEntity p, Set<String> topicFilter) {
        if (topicFilter.isEmpty()) {
            return 0.0;
        }
        List<String> pTopics = fromJson(p.getTopicsJson());
        if (pTopics.isEmpty()) {
            return 0.0;
        }
        long overlap = pTopics.stream().filter(topicFilter::contains).count();
        return (double) overlap / topicFilter.size();
    }

    private double scoreEmotional(PropositionEntity p) {
        List<String> tags = fromJson(p.getEmotionTagsJson());
        if (tags.isEmpty()) {
            return 0.0;
        }
        return Math.min(1.0, tags.size() / 5.0);
    }

    private double scoreAssociative(PropositionEntity p) {
        int ref = p.getReferenceCount() != null ? p.getReferenceCount() : 0;
        return Math.min(1.0, ref / 10.0);
    }

    private double scoreInsight(PropositionEntity p) {
        return p.getConfidence() != null ? p.getConfidence() : 0.0;
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(text.toLowerCase().split("[^a-z0-9\\u4e00-\\u9fa5]+"))
                .filter(t -> t.length() >= 2)
                .collect(Collectors.toSet());
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return mapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.warn("JSON 序列化失败", e);
            return "[]";
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("JSON 反序列化失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private record Scored(PropositionEntity entity, double score) {}
}
