---
name: devops-cicd
description: DevOps 与 CI/CD 实践——流水线、Docker、GitHub Actions、部署策略、安全扫描
---

# DevOps & CI/CD 实践

## 流水线设计

### 基础流水线
```
Push → Lint → Test → Build → Security Scan → Deploy
```

### 关键节点
- **Lint**：PR 门槛，不通过不让合
- **Test**：单元测试必跑，覆盖率门槛 ≥ 70%
- **Build**：产物可复现（锁定依赖版本）
- **Security**：SAST（静态扫描）+ SCA（依赖漏洞）
- **Deploy**：蓝绿 / 金丝雀，一键回滚

## Docker 最佳实践

### Dockerfile
```dockerfile
# 多阶段构建
FROM maven:3.9-eclipse-temurin-21 AS build
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
COPY --from=build /target/*.jar app.jar
EXPOSE 8080
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 红线
- 不跑容器当 root
- 镜像 < 200MB
- 不把 secrets 打进镜像（用 env / secret manager）
- 固定基础镜像版本（不用 latest）

## GitHub Actions 模板
- 缓存依赖（~/.m2 / node_modules）
- matrix 测试多版本
- artifact 上传保留 7 天
- 分支保护：main 必须 passing

## 部署策略
| 策略 | 适用场景 | 回滚速度 |
|------|----------|----------|
| 蓝绿 | 停机敏感 | 秒级 |
| 金丝雀 | 大用户量 | 分钟级 |
| Rolling | 简单系统 | 分钟级 |
| Feature Flag | 功能灰度 | 即时 |

## 安全扫描
- Trivy：镜像/文件系统漏洞
- OWASP Dependency-Check：依赖 CVE
- Gitleaks：硬编码密钥检测（pre-commit）
