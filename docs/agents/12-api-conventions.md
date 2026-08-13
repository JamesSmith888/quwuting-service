# HTTP API 与统一响应格式

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## HTTP API 规范

**只允许 GET 和 POST，禁止使用 PUT、PATCH、DELETE。**

| 场景 | 方法 | 示例 |
|------|------|------|
| 列表查询 / 单条查询 | GET | `GET /venues?city=绍兴市&latitude=30.0&longitude=120.5&page=0&size=20` |
| 关键字 / 复杂条件搜索 | GET | `GET /venues/search?q=爵士` |
| 带复杂过滤的分页（参数过多） | POST | `POST /venues/search` |
| 创建资源 | POST | `POST /venues` |
| 逻辑更新（状态变更） | POST | `POST /venues/{id}/status` |
| 逻辑删除 | POST | `POST /venues/{id}/disable` |
| 信息纠错反馈 | POST | `POST /venues/{venueId}/feedbacks`（需登录） |
| 场所状态上报 | POST | `POST /venues/{venueId}/status-reports`（需登录，body 可空=快速上报） |
| 撤销状态上报 | POST | `POST /venues/{venueId}/status-reports/cancel`（需登录） |

URL 使用复数名词，全小写，单词间用 `-` 连接：`/job-posts`、`/venue-tags`。

---


---
## 统一响应格式

所有接口统一返回 `ApiResponse<T>`：

```java
// dto/response/ApiResponse.java
public record ApiResponse<T>(int code, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
```

- 成功：`code = 0`
- 业务错误：`code` 使用自定义错误码（`1xxx` 客户端错误，`5xxx` 服务端错误）
- HTTP 状态码约定（2026-08-10 修订，原"始终 200"已废弃——服务器错误以 200 返回会被
  监控/代理/前端 5xx 重试完全掩盖，见「连接池与数据库抖动韧性」）：
  - 业务错误（BusinessException / 参数校验）：HTTP `200` + code 区分（前端契约不变）
  - 未登录：HTTP `401` + code 1002（前端据此清凭证触发登录）
  - 路由不存在：HTTP `404` + code 1001
  - 数据库连接类瞬时故障（连接池超时/连接中断/数据库不可达）：HTTP `503` + code 5003
    （前端对幂等 GET 的 5xx 自动重试一次可自愈）
  - 其余未预期异常：HTTP `500` + code 5000（兜底，日志打完整堆栈）

### 错误码登记表（新增错误码必须避开已占用值）

| code | 含义 |
|------|------|
| 1001 | 参数校验失败 / 资源不存在 |
| 1002 | 未登录（token 缺失 / 无效 / 过期），HTTP 401 |
| 1003 | 权限不足（非管理员）/ 微信接口业务错误 |
| 1004 | 用户不存在 |
| 1005 | 文件校验失败（类型 / 大小超限） |
| 1006 | 操作过于频繁（评分防刷冷却期内 / 状态上报频率超限 / 用户上报 60s 冷却） |
| 1007 | 无效的评分维度 / Reaction 类型 |
| 1008 | 上报不存在 |
| 1009 | 无效的排序方式（VenueSortMode.from） |
| 5000 | 未知服务器错误（兜底），HTTP 500 |
| 5001 | 微信接口响应异常（无响应 / 解析失败） |
| 5002 | 文件保存失败（IO 异常） |
| 5003 | 数据库连接类瞬时故障（服务暂时不可用），HTTP 503 |

---

