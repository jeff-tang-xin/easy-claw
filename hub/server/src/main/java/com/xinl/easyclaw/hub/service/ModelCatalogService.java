package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.contract.provider.ModelCatalogDto;
import com.xinl.easyclaw.hub.contract.provider.UpsertModelCatalogRequest;
import com.xinl.easyclaw.hub.entity.ModelCatalogEntity;
import com.xinl.easyclaw.hub.repository.ModelCatalogRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型目录：平台级模型清单与积分比例（platformAdmin 维护）。
 * provider 的 models 清单按名称引用目录模型；网关扣积分按请求模型名取 credit_cost，
 * 未登记模型默认 1 分/次。creditCost 统一 1 位小数、多余位数舍弃不进位（DOWN）。
 */
@Service
public class ModelCatalogService {

    private final ModelCatalogRepository repo;
    private final AuditService auditService;

    public ModelCatalogService(ModelCatalogRepository repo, AuditService auditService) {
        this.repo = repo;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<ModelCatalogDto> list() {
        return repo.findAllByOrderByModelNameAsc().stream().map(this::toDto).toList();
    }

    @Transactional
    public ModelCatalogDto create(Long actorId, UpsertModelCatalogRequest req) {
        String modelName = req.modelName().trim();
        if (modelName.isEmpty()) {
            throw ApiException.validation("模型名不能为空");
        }
        if (repo.existsByModelName(modelName)) {
            throw ApiException.conflict("模型已存在：" + modelName);
        }
        ModelCatalogEntity e = new ModelCatalogEntity();
        e.setModelName(modelName);
        e.setCreditCost(truncate1(req.creditCost()));
        e.setRemark(emptyToNull(req.remark()));
        repo.save(e);
        auditService.record(AuditModule.MODEL_CATALOG, "create_model_catalog", actorId, null, "model_catalog",
                String.valueOf(e.getId()), "modelName=" + modelName + ",creditCost=" + e.getCreditCost(),
                AuditModule.SUCCESS);
        return toDto(e);
    }

    @Transactional
    public ModelCatalogDto update(Long actorId, Long id, UpsertModelCatalogRequest req) {
        ModelCatalogEntity e = repo.findById(id).orElseThrow(() -> ApiException.notFound("模型不存在"));
        String modelName = req.modelName().trim();
        if (modelName.isEmpty()) {
            throw ApiException.validation("模型名不能为空");
        }
        repo.findByModelName(modelName)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw ApiException.conflict("模型已存在：" + modelName);
                });
        e.setModelName(modelName);
        e.setCreditCost(truncate1(req.creditCost()));
        e.setRemark(emptyToNull(req.remark()));
        repo.save(e);
        auditService.record(AuditModule.MODEL_CATALOG, "update_model_catalog", actorId, null, "model_catalog",
                String.valueOf(id), "modelName=" + modelName + ",creditCost=" + e.getCreditCost(),
                AuditModule.SUCCESS);
        return toDto(e);
    }

    @Transactional
    public void delete(Long actorId, Long id) {
        ModelCatalogEntity e = repo.findById(id).orElseThrow(() -> ApiException.notFound("模型不存在"));
        repo.delete(e);
        auditService.record(AuditModule.MODEL_CATALOG, "delete_model_catalog", actorId, null, "model_catalog",
                String.valueOf(id), "modelName=" + e.getModelName(), AuditModule.SUCCESS);
    }

    /** 积分值统一 1 位小数、多余位数舍弃不进位（DOWN）。 */
    private BigDecimal truncate1(BigDecimal v) {
        return v.setScale(1, java.math.RoundingMode.DOWN);
    }

    private String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private ModelCatalogDto toDto(ModelCatalogEntity e) {
        return new ModelCatalogDto(e.getId(), e.getModelName(), e.getCreditCost(), e.getRemark(), e.getCreatedAt());
    }
}
