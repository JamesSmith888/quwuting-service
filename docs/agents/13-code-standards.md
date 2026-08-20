# 代码规范（JPA / Repository / DTO / 异常 / 序列化等）

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## JPA 实体规范

```java
// ✅ 正确：Entity 只用 @Getter/@Setter，不用 @Data（避免 equals/hashCode 问题）
@Entity
@Table(name = "venue")
@Getter
@Setter
public class Venue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    private VenueStatus status;   // 枚举用 STRING，禁止用 ORDINAL

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- 主键统一用 `Long id`，策略 `IDENTITY`
- 时间戳字段用 `LocalDateTime`，配合 `@CreationTimestamp` / `@UpdateTimestamp`
- **`@CreationTimestamp` 属性不可变（2026-08-10 根因教训）**：Hibernate 视其为不可变属性，实体 setter 在 UPDATE 时被静默忽略（WARN HHH000502），UPDATE 语句不含该列——需要"更新 createdAt"的场景（如 TTL 续期）禁止用实体 setter，必须经 `@Modifying` JPQL 批量更新直写列（见 status-report 模块 `renewCreatedAt` 与根因注记）
- 枚举映射用 `@Enumerated(EnumType.STRING)`，禁止 `ORDINAL`
- 逻辑删除字段命名 `deleted`（`boolean`），不物理删除数据
- 禁止在 Entity 中写业务方法

---


---
## 多值字段（JSON 数组列）

标签（`tags`）、相册（`photos`）等"一对多、无独立元数据、仅整体读写"的字段，以 **JSON 数组字符串**存储于单个 `varchar` 列（如 `["爵士","商务"]`），不建关联表：

- Entity 中为 `String` 类型，`@Column(length = ...)` 按上限估算（如 photos 9 张 × 500 URL → 5000）
- 序列化/反序列化统一收敛在 `mapper/` 组件与 Service 的私有工具方法中（注入 `ObjectMapper`），新增同类字段时复用 `serializeStringList` / `serializeList` / `deserializeList`，不另起炉灶
- Response DTO 中为 `List<String>`，**空数据返回空列表而非 null**（前端无需判空两套逻辑）
- Request DTO 中为 `List<String>`，用 `@Size(max = N)` 限制数量、`List<@Size(max = 500) String>` 限制单元素长度，与列长度约束呼应
- 若字段未来需要独立查询、排序、元数据（如图片描述、上传者），再升级为关联表——新表走 `db/migration/V{n}` 迁移脚本创建（见「Schema 演进（自动更新优先）」章节）

**结构化对象列表**同理：门票（`tickets`）、舞伴费用（`partnerFees`）以 JSON 对象数组存储，DTO 定义为 `venue/dto/` 下的共享 record（`TicketEntry` / `PartnerFeeEntry`，请求与响应复用），Request 中用 `List<@Valid TicketEntry>` 触发嵌套校验，跨字段约束在 Service 层校验。

- `TicketEntry`：`{"label":"晚场","type":"FIXED","price":30}`——label 为条件自由文本，type 为枚举（FIXED/FREE）
- `PartnerFeeEntry`：`{"label":"5点前","unit":"MINUTE","minutes":5,"price":20}`——label 为条件自由文本（可空），unit 为计量单位枚举（`PartnerFeeUnit`：MINUTE 按分钟 / SONG 按曲数），minutes 为计量数量（unit=SONG 时语义为曲数，字段名保留以兼容存量数据）。请求中 unit 可省略（Service 层 `normalizePartnerFees` 默认 MINUTE），存储时始终写入显式 unit 值。设计动机：江浙沪按时长阶梯、西安等地按连曲计费，同一店内可有时段差异——与 TicketEntry 的"label + 类型"模式对齐，新增计费形态只需扩展 unit 枚举。

---


---
## Repository 规范

```java
// ✅ 继承 JpaRepository，复杂查询用 @Query JPQL
public interface VenueRepository extends JpaRepository<Venue, Long> {

    // 简单条件：方法命名推导
    Page<Venue> findByStatusAndCityOrderByCreatedAtDesc(
        VenueStatus status, String city, Pageable pageable);

    // 复杂条件：显式 JPQL（禁止 native SQL，除非无法实现）
    @Query("SELECT v FROM Venue v WHERE v.status = :status AND " +
           "(:keyword IS NULL OR v.name LIKE %:keyword%)")
    Page<Venue> search(@Param("status") VenueStatus status,
                       @Param("keyword") String keyword,
                       Pageable pageable);
}
```

- 优先方法命名推导，方法名超过 4 个条件时改用 `@Query`
- 禁止 `@Query(nativeQuery = true)`，除非 JPQL 无法实现且已注释原因
- 分页统一使用 `Pageable` 参数，返回 `Page<T>`
- **单行多列聚合查询禁止以 `Object[]` 为返回类型，必须使用接口投影（Interface-based Closed Projection）**。Spring Data JPA 4.x 将 `Object[]` 解释为"行数组"而非"列值数组"，导致 `ClassCastException`。标准做法：在 Repository 内定义嵌套投影接口（getter 名与 SELECT alias 对应），查询方法返回该接口类型。多行查询仍用 `List<Object[]>`（不受影响）。

```java
// ✅ 正确：接口投影（类型安全，跨版本稳定）
public interface VenueViewRepository extends JpaRepository<VenueView, Long> {
    interface PvUvStats {
        Long getPv();
        Long getUv();
    }

    @Query("SELECT COUNT(v) as pv, COUNT(DISTINCT v.userId) as uv FROM VenueView v " +
           "WHERE v.venueId = :venueId AND v.viewDate >= :since")
    PvUvStats countPvAndUvByVenueIdSince(@Param("venueId") Long venueId, @Param("since") LocalDate since);
}

// ❌ 禁止：Object[] 接收单行多列（Spring Data JPA 4.x 语义变更致 ClassCastException）
@Query("SELECT COUNT(v), COUNT(DISTINCT v.userId) FROM VenueView v ...")
Object[] countPvAndUv(...);
```

投影接口命名约定：嵌套于 Repository 接口内部（co-located，不新增文件）；JPQL 用 `as alias` 与 getter 名对应；native query 的 alias 使用全小写（PostgreSQL 对未加引号标识符做小写折叠），getter 名同步全小写（如 `getRatingcount()`）。

### 投影接口 getter 类型必须匹配 Hibernate 的实际映射类型（2026-07-31 热度接口 500 事故根因）

投影接口的 getter 返回类型必须与 Hibernate 对该 SQL 列类型的**实际映射类型**一致，而非直觉上"看起来对应"的 JDBC 遗留类型。Hibernate 6+（Spring Boot 4.x 默认版本）对原生查询结果的默认类型映射已改为优先 `java.time.*`：

| SQL 类型 | Hibernate 6+ 默认映射 | 禁止使用的历史遗留类型 |
|----------|----------------------|----------------------|
| `DATE` | `java.time.LocalDate` | `java.sql.Date` |
| `TIMESTAMP` | `java.time.LocalDateTime` | `java.sql.Timestamp` |

若投影接口的 getter 声明为遗留类型（如 `java.sql.Date getDay()`），`ProjectingMethodInterceptor` 在把 Hibernate 实际返回的 `LocalDate` 转换为声明类型时找不到匹配的 `Converter`，直接抛 `UnsupportedOperationException: Cannot project java.time.LocalDate to java.sql.Date`——**编译期不报错，只在运行时首次命中该查询才炸**，且异常堆栈指向调用方（`VenueHeatService`）而非真正的根因（Repository 投影接口）。

**规则**：新增/修改任何原生查询（`nativeQuery = true`）的投影接口时，DATE/TIMESTAMP 列一律用 `java.time.LocalDate`/`java.time.LocalDateTime` 声明 getter，禁止使用 `java.sql.*` 包下的类型。JPQL 查询同理（Hibernate 对 JPQL 结果的映射规则一致）。

**`List<Object[]>` / `Page<Object[]>` 多行查询的行内强转同理（2026-08-08 管理端舞伴列表 500 事故根因）**：多行查询虽允许 `Object[]` 返回，但服务层对行内 DATE/TIMESTAMP 列的强转也必须用 `java.time.*` 类型（如 `(LocalDate) row[0]` / `(LocalDateTime) row[7]`），禁止 `(java.sql.Date)` / `(java.sql.Timestamp)` 再 `.toLocalDate()`/`.toLocalDateTime()`——Hibernate 7 实际返回的就是 `java.time.*`，按遗留类型强转运行时抛 `ClassCastException`（编译期不报错，首次命中才炸）。若同时还需要把行映射为 DTO，直接整体改用投影接口更稳。


### 分页参数安全（强制）

Controller 接收的 `page` / `size` 参数必须在 **Service 层**钳制后再构造 `PageRequest`，防止客户端传入极端值导致 OOM 或异常：

```java
private static final int MAX_PAGE_SIZE = 50;

page = Math.max(0, page);
size = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
PageRequest pageable = PageRequest.of(page, size);
```

新增任何分页接口时必须遵循此模式。

---


---
## DTO 规范

```java
// 请求体：Java record（Spring 6+ 支持 record 绑定）
public record VenueSearchRequest(
    String keyword,
    String city,
    String district,
    @Min(0) int page,
    @Max(50) @Min(1) int size
) {}

// 响应体：Java record，不暴露内部 id 以外的敏感字段
public record VenueResponse(
    Long id,
    String name,
    String address,
    String phone,
    String businessHours,
    List<String> tags
) {}
```

- 请求/响应 DTO 均用 Java **record**，禁止用 Lombok `@Data`
- Service 层负责 Entity → DTO 转换，禁止在 Controller 层转换
- 响应 DTO 字段分类原则：
  - **系统内部字段**（`deleted`、`createdAt`）：禁止暴露，仅用于审计/运维
  - **用户决策时效字段**（`updatedAt`）：必须暴露——黄页产品中场所开关状态不稳定，用户需要"数据最后更新时间"判断信息可靠度，这是信任信号而非内部审计字段

---


---
## 异常处理

```java
// 自定义业务异常
public class BusinessException extends RuntimeException {
    private final int code;
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}

// 全局处理器
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handle(BusinessException ex) {
        return ApiResponse.fail(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<Void> handle(ConstraintViolationException ex) {
        return ApiResponse.fail(1001, "参数校验失败: " + ex.getMessage());
    }
}
```

- 禁止在 Service 层 catch 后吞掉异常
- Controller 层禁止 try-catch，统一由 `GlobalExceptionHandler` 处理
- 日志用 `@Slf4j`，错误级别：业务异常用 `warn`，系统异常用 `error`

### 并发写入竞态处理（upsert 优先，唯一约束 catch 兜底）

有唯一约束的写入分两类，处理方式不同：

**① 纯幂等插入（不需要已有行状态）→ `ON CONFLICT DO NOTHING` upsert，首选**。单次 DB 往返完成"不存在则插入、存在则忽略"，去重与并发竞态全部由库内唯一索引/约束兜底，无并发窗口：

```java
@Query(value = "INSERT INTO ... VALUES (...) ON CONFLICT (<唯一键列清单>) DO NOTHING",
       nativeQuery = true)
void upsertXxx(...);
```

**冲突目标必须用列清单推断（`ON CONFLICT (col1, col2, ...)`），禁止 `ON CONFLICT ON CONSTRAINT`**：后者只匹配 UNIQUE/EXCLUDE **约束**，不匹配 `CREATE UNIQUE INDEX` 创建的唯一**索引**——V1 基线 qwt_venue_views 用唯一索引形态，旧 upsert 的 ON CONSTRAINT 写法在生产库每次抛错且被 fire-and-forget 静默吞掉（浏览来源折线全 0 的潜在根因，V21 修复）。列推断按列集合匹配，对索引/约束两种形态均健壮（2026-08-13 教训沉淀）。

实例：`VenueViewService.recordView`（浏览记录按天按来源去重，唯一键 = venue_id, user_id, view_date, source）。早期实现为 check-then-act（先 SELECT 存在性再 INSERT），多一次跨洲往返且 SELECT 与 INSERT 之间存在并发窗口——upsert 同时消除两者。

**② 需要依据已有行状态分支（恢复软删 / toggle / 频率限制）→ find-then-modify + 唯一约束 catch 兜底**。此类场景 SELECT 是"必要读"（要拿到已有行才能决定更新/恢复/切换），不属于冗余往返；check-then-act 的并发窗口由 catch 收口：

```java
try {
    repository.save(entity);
} catch (DataIntegrityViolationException e) {
    // 并发竞态：另一请求已插入，幂等忽略
    log.debug("并发冲突，幂等忽略: ...");
}
```

实例：`FavoriteService.addFavorite`（软删恢复）、`TagInteractionService.score`（冷却检查）、`VenueReactionService.toggle`（软删恢复/切换）。若 INSERT 后还需后续操作（如 toggle），冲突时应重新查询已有记录再执行后续逻辑。
<b>注意（2026-08-20 起）</b>：`StatusReportService.submitReport` 已迁出本模式（V34 追加式 + `insertReport` 原子 upsert，见 07-feedback-and-reporting）——旧指引「catch 后必须 `entityManager.clear()`」已废止（PG 语句失败后事务中止 25P02，catch 后同事务查询必然 500，见 15-governance 错误表）。新代码禁止再用 catch+clear 表达幂等。

---


---
## 请求耗时日志（慢请求定位）

2026-08 接口慢排查后确立。项目早期请求级埋点为零，"慢"无法归因（后端处理 / 网络传输 / 客户端排队无从区分）。慢请求定位依赖以下两层埋点，前后端日志经同一个 `X-Request-Id` 关联。

### RequestTimingFilter（config/RequestTimingFilter.java）

Servlet Filter（继承 `OncePerRequestFilter`，`@Component` 自动注册 + `@Order(Ordered.HIGHEST_PRECEDENCE)`），统一记录所有请求的端到端处理耗时：

```
INFO  [http] GET /venues/14/tags/stats -> 200 cost=9ms rid=r3-m1abc
WARN  [http] GET /venues/14 -> 200 cost=2412ms rid=r4-m1abd [SLOW]
```

- `cost` 覆盖 Filter → AuthInterceptor → Controller → Service 全链路，即"服务端处理耗时"。前端同 rid 日志的 cost − 后端 cost ≈ 网络传输开销（含 Cloudflare Tunnel）
- `SLOW_THRESHOLD_MS`（当前 1000ms，依据单次跨洲 DB 往返 ~300-500ms 定档）及以上升级为 WARN，便于日志中快速筛出慢请求
- `rid` 读取前端 `X-Request-Id` 请求头（小程序 `services/requestPerf.ts` 生成）；无此头的请求（curl 等）自动生成 `s` 前缀 ID
- 必须保持最高优先级：若其他 Filter 排在其前，计时将漏掉前置处理

### AuthInterceptor 用户缓存计时

软鉴权对 JWT 验签后，用户实体通过**内嵌 Caffeine 缓存**（2min TTL，maxSize=500）查询，不再每个请求都查库。`preHandle` 对 JWT 验签与用户查找分别计时，输出 `[auth] uid=.. jwtVerify=..ms lookup=..ms`。`lookup` 在缓存命中时 <1ms，未命中时 = 完整 DB 往返（300~700ms）。role 取自 DB（经缓存），不取自 JWT payload——保证管理员调整角色后 2 分钟内生效。新增鉴权链路逻辑时保持该计时结构。

### 已知慢请求基线（2026-08 二轮优化后实测）

- 服务器 ↔ Supabase（AWS ap-south-1）单次 DB 往返约 300~500ms——应用层无法改变的单次成本，只能压缩往返次数
- **缓存层**：AuthInterceptor 用户缓存（2min）消除每请求鉴权查库；VenueLookupService 场所缓存（60s，sync 单飞）消除详情页重复场所查询；热门场所 ID 缓存（5min）消除列表窗口函数全表扫描；热度/标签聚合为内嵌 LoadingCache（refresh-ahead，见「查询性能优化」）
- 二轮优化后实测（本地开发机，缓存命中 vs 冷启动）：
  - `GET /venues/{id}/heat`：~3ms（缓存命中）/ ~870ms（冷启动，mega-query+趋势 2~4 往返）vs 一轮优化后冷启动 ~2100ms
  - `GET /venues/{venueId}/tags/stats`：~10ms（聚合缓存被详情请求预热共享）/ ~500ms（冷）
  - `GET /venues/{id}`（详情）：~480ms（venue+聚合缓存命中，仅 detailStats 1 往返）/ ~1200ms（冷）vs 优化前 ~1600ms
  - `POST /venues/{id}/view`：~500ms（upsert 1 往返）vs 优化前 ~800ms
  - `GET /favorites`（收藏）：~1000ms（联查+标签批量 2 往返）vs 优化前 ~1160ms
  - `GET /venues`（列表）：~1200-2400ms（主查询+count+标签批量 3 往返，冷启动含 hotVenueIds 窗口函数）
  - 详情页首屏（前端 onLoad 四请求并发）：≈ max(各请求耗时)，不再串行叠加；聚合缓存单飞使并发请求共享回源
- **剩余根因级优化（待决策）**：DB 迁移至近区（如 ap-southeast-1 / ap-northeast-1），单次往返可降至 60~100ms，所有接口延迟按比例下降。应用层往返压缩已接近形态下限，迁库是下一个数量级的收益
- 定位顺序：先筛 WARN 的 `[SLOW]` 日志确定后端 cost → 与前端 `[http]` 日志同 rid 对比确定网络占比 → 看 `[auth]` 日志确定 lookup 是缓存命中还是 DB 回源

---


---
## JSON 序列化（Jackson 3.x）

Spring Boot 4.x 自动配置的是 **Jackson 3.x**（`tools.jackson`），不再是 Jackson 2.x。

```java
// ✅ 正确：使用 Jackson 3.x 包名
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

// ✅ 注解仍在旧包下（jackson-annotations 未迁移）
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
```

- `ObjectMapper`、`TypeReference`、`JsonProcessingException` 等核心类在 `tools.jackson.*` 包下
- `@JsonFormat`、`@JsonProperty`、`@JsonIgnore` 等注解仍在 `com.fasterxml.jackson.annotation` 包下
- 禁止在 pom.xml 中显式引入 `com.fasterxml.jackson.core:jackson-databind`（2.x），会覆盖 Spring Boot 管理的 3.x 版本
- 禁止 import `com.fasterxml.jackson.databind.*` 或 `com.fasterxml.jackson.core.*`（2.x 包名）
- 需要 `ObjectMapper` 时直接注入 Spring 容器中的 Bean，禁止手动 `new ObjectMapper()`

---


---
## 命名规范

| 类别 | 规则 | 示例 |
|------|------|------|
| 类名 | PascalCase | `VenueController`, `VenueService` |
| 方法名 | camelCase，动词开头 | `findVenueById`, `searchVenues` |
| 变量名 | camelCase | `venueList`, `pageResult` |
| 常量 | SCREAMING_SNAKE_CASE | `MAX_PAGE_SIZE` |
| 数据库表名 | `qwt_` 前缀 + snake_case 复数 | `qwt_venues`, `qwt_job_posts` |
| 数据库索引名 | `qwt_idx_` 前缀 + snake_case | `qwt_idx_city`, `qwt_idx_status` |
| 数据库列名 | snake_case | `business_hours`, `created_at` |
| URL 路径 | 复数名词，kebab-case | `/venues`, `/job-posts` |

---

