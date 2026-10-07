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

> **2026-08-19 约定对齐（历史遗留漂移修复）**：舞伴域与门店状态关注曾违反本约定使用
> PUT/DELETE（`PUT /dancers/{id}`、`DELETE /dancers/{id}/photos/{photoId}`、
> `PUT /admin/dancers/...`、`PUT/DELETE /venues/{id}/status-watch`），现已全部迁移为
> POST 动作路径，端点示例：
>
> | 场景 | 方法 | 示例 |
> |------|------|------|
> | 编辑舞伴资料（全量覆盖，REJECTED 自动重审） | POST | `POST /dancers/{id}/update` |
> | 删除舞伴照片（软删） | POST | `POST /dancers/{id}/photos/{photoId}/remove` |
> | 管理端状态切换 / 照片审核 / 信息核验 | POST | `POST /admin/dancers/{id}/status`、`POST /admin/dancers/photos/{photoId}/status`、`POST /admin/dancers/{id}/verification` |
> | 开启 / 关闭关注门店状态 | POST | `POST /venues/{id}/status-watch`、`POST /venues/{id}/status-watch/cancel` |

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
  - 数据库连接类瞬时故障（连接池超时/连接中断/数据库不可达）：HTTP `503` + code 5003 + `Retry-After: 1`
  - 外部依赖暂不可用（配额耗尽 / 限流 / 凭证失效 / 上游超时）：HTTP `503` + code 5005 + `Retry-After: <秒>`
    （抛 `ExternalServiceUnavailableException`，见下方「重试契约」）
  - 其余未预期异常：HTTP `500` + code 5000（兜底，日志打完整堆栈）

### 重试契约（2026-10-01）

**「能不能重试」由服务端声明，客户端不猜。** 根因：小程序请求层曾对「GET 遇到任何 5xx」都自动
重试一次，把 500（确定性缺陷，重试必然再失败）与 503（暂时不可用）混为一谈——腾讯地图日配额
耗尽时 `/geo/reverse` 返回 500，每次定位真实外呼两次，配额被放大消耗、日志里同一故障翻倍。

| 响应 | 含义 | 客户端（`services/auth.ts` `isRetryableServerFailure`） |
|---|---|---|
| 503 + `Retry-After ≤ 2` | 瞬时不可用（5003 DB 抖动 / 5005 限流） | 幂等 GET 重试 1 次 |
| 503 + 更长 `Retry-After` | 暂不可用且短期不会恢复（5005 配额耗尽 → 次日 0 点） | 不重试，交调用方降级 |
| 502 / 504 | 网关层故障（服务端来不及表态） | 重试 1 次 |
| 500 | 未预期错误（程序缺陷） | **不重试** |

服务端规则：**可预期的外部失败一律抛 `ExternalServiceUnavailableException`（带 Retry-After），
禁止包成 `IllegalStateException` 落进兜底 500**——兜底 500 只留给真正的程序缺陷（ERROR 堆栈 =
需要修代码）。外部客户端负责把上游状态码翻译成这三类语义（先例：`geo/tencent/TencentLbsClient`：
121 日配额耗尽 ⇒ 熔断到次日 0 点、期间不再外呼）。

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
| 1010 | 招工内容含风险词，需管理员确认发布（PublishRecruitmentRequest.confirmed） |
| 1011 | 上报补充说明非法（「情况不明」必填 / 超长）/ 积分礼物参数非法 |
| 1012 | 上报类型与门店当前状态矛盾（如营业中报「恢复营业」） |
| 1013 | 今日赠送已达上限（积分礼物，用户维度） |
| 1014 | 该目标今日已达赠送上限（积分礼物，目标维度） |
| 1015 | 不能给自己管理的门店赠送礼物 |
| 1016 | 运营配置项不存在（管理端只能改值不能造键） |
| 1017 | 热度上报：门店不存在 |
| 1018 | 热度上报：门店当前未营业 |
| 1019 | 上报记录不存在或已删除 / 门店已被认领 |
| ~~1020~~ | ~~热度点赞：已过 6 小时有效窗口~~ | **2026-10-07 退役**：过期上报永远可点赞（窗口锁已取消，见 53 号文档 §4.1），前端不再有「过窗不可赞」分支 |
| 1021 | 订阅通知档位不合法 |
| 1022 | 到访上报过于频繁 / 门店无坐标 |
| 1030 | 账目同步载荷非法（枚举/金额/ts 校验不过，spend 域逐条归因 rejected） |
| 1031 | 月度参数非法（month 不是 yyyy-MM） |
| 1032 | 附近门店查询缺少定位参数 |
| 1033 | 营业活动不存在 / 已下线 |
| 1034 | 营业活动参数非法 |
| 1035 | 快讯越过内容红线（只写服务可得性，命中原因/事件/负面词；`BulletinContentPolicy`，2026-10-01） |
| 1036 | 批量置暂停影响面熔断（单城本批暂停占比超上限，需 dryRun 核对 + confirmedCities 确认；`SuspendBlastRadiusGuard`，2026-10-01） |
| 1040 | 计价规则快照载荷非法（空 / 超过 32KB，结构校验归客户端） |
| 1041 | 计时同步读数 / 规则 / 场次标识非法（`TimerShareService`，文案固定，细节只进日志；2026-10-07） |
| 1043 | 计时同步二维码不存在或不是自己的（主持方轮询；2026-10-07） |
| 5000 | 未知服务器错误（兜底），HTTP 500 |
| 5001 | 微信接口响应异常（无响应 / 解析失败） |
| 5002 | 文件保存失败（IO 异常） |
| 5003 | 数据库连接类瞬时故障（服务暂时不可用），HTTP 503 + Retry-After: 1 |
| 5004 | 门店营业管线正在运行中（单槽位并发，重复触发「拉取数据」） |
| 5005 | 外部依赖暂不可用（配额 / 限流 / 凭证 / 上游超时），HTTP 503 + Retry-After（2026-10-01） |

> 2026-10-01 补登 1011–1022、1032–1034（此前在用却未登记）。**新增错误码前先
> `grep -rhoE "BusinessException\(1[0-9]{3}" src/main/java | sort -u` 核对占用**，登记表不是唯一事实源。

---

