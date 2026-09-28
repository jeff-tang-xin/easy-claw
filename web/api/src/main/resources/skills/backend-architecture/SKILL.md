---
name: backend-architecture
description: 后端架构标准——API 设计、错误处理、数据层、安全、可观测性
---

# 后端架构标准

## API 设计（RESTful）

### URL 规范
- 资源用名词复数：`GET /api/users` 而非 `/api/getUsers`
- 嵌套资源：`GET /api/users/{id}/orders`
- 版本化：`/api/v1/users`

### HTTP 方法语义
| 方法 | 用途 | 幂等 |
|------|------|------|
| GET | 读取 | ✅ |
| POST | 创建 | ❌ |
| PUT | 全量更新 | ✅ |
| PATCH | 部分更新 | ❌ |
| DELETE | 删除 | ✅ |

### 响应格式
```json
{
  "code": 0,
  "data": { },
  "message": "",
  "requestId": "uuid-for-tracing"
}
```

## 错误处理
- 业务异常用自定义 Exception + @ControllerAdvice
- 不暴露堆栈给前端（生产环境）
- 错误响应包含 requestId 方便排查

## 数据层
- Repository 只做 CRUD，业务逻辑放 Service
- 分页参数统一：page, size（默认 20，最大 100）
- 软删除（deleted_at）+ 唯一索引注意

## 安全红线
- 输入校验（@Valid + @Size/@Pattern）
- SQL 注入：用参数化查询，禁止字符串拼接
- 敏感数据脱敏（日志、API 响应）
- 权限校验在 Service 层（不是 Controller）

## 可观测性
- 结构化日志（JSON 格式）
- 关键指标暴露（/actuator/prometheus）
- requestId 贯穿全链路（MDC）
