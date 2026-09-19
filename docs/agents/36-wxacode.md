# 36 · 分享小程序码域（wxacode）

> 2026-09-07 新增；2026-09-19 静态资产物化（V30）。非微信平台分享桥接：用户在小程序内
> 生成/保存小程序码图片（官方 getwxacodeunlimit 能力），贴到微信以外平台扫码回流。
> 前端权威文档 = quwuting/docs/agents/40-wxacode-share.md（入口/组件/scene 落地解析/合规辨析）。

## 一、端点契约（公开软鉴权，image/jpeg 二进制直出）

| 端点 | scene | page | 说明 |
|------|-------|------|------|
| `GET /venues/{id}/wxacode.jpg` | `id=<venueId>`（登录追加 `&s=<uid>`） | `pages/venue-detail/venue-detail` | 门店码；门店不存在/已删除 → 1001；微信失败 → 5001 |
| `GET /wxacode/home.jpg` | `home` | `pages/index/index` | 平台码；首页不消费 scene |

- 响应：`Content-Type: image/jpeg` + `Cache-Control: max-age=86400`（与服务端缓存
  TTL 对齐）；**非 ApiResponse JSON**——图片直出给前端 image 直连/落盘。
- 微信接口失败 → 5001 JSON（image 直连表现为加载失败，前端 binderror 兜底）
  ——注意：静态码通道仅在**首次物化**时可能走到这条路径，此后不再外呼。
- 归因 uid = `UserContext.getCurrentUserId()` 服务端权威注入，请求参数不暴露。

## 二、两类码分层（2026-09-19 根因修复，V30）

|  | 静态码（平台码） | 个性化码（门店码） |
|---|---|---|
| 生成输入 | `(appId, page, scene, envVersion)`，无用户维度 | 同上 + scene 携带 uid 归因 |
| 键空间 | 环境数 × 场景数（≈3） | 门店 × 活跃用户（乘积型） |
| 载体 | **DB 物化（永久只读）** | 内存缓存（24h TTL） |
| 失效条件 | 仅内容指纹变化 | TTL / 进程重启（可再生，无损失） |

**根因**（用户报障「它是静态的就不该每次都重新生成」）——重构前两类码共用一张
`page|scene → bytes` 的进程内缓存：① 生命周期错配（缓存失效条件 TTL/重启/清空与内容
失效条件无关 ⇒ 部署重启后首个请求必然外呼微信）；② `MAX_CACHE_SIZE=500` 超限整体
`clear()`，1 个键的静态码被乘积型键空间的门店码误伤；③ 键缺 `env_version` ⇒ 改
`wechat.qrcode-env` 后 24h 内静默返回旧环境的码。

**判据**：「内容永不变」与「内容按需再生」是两种生命周期，**不能共用同一套失效策略**。
静态内容物化成资产（键 = 内容指纹）后，失效条件才对齐到内容本身。

## 三、实现要点

- 新域 `org.quwuting.quwutingservice.wxacode`：`WxacodeShareService` +
  `WxacodeShareController`（方法级 @GetMapping，类级无 @RequestMapping——
  `/wxacode` 根路径已被舞伴域 `dancershare/WxacodeController`（`GET /wxacode?dancerId=`）
  占用；`/wxacode/home.jpg` 与其精确匹配不冲突）。
- **`WxacodeSpec`（service 包内不可变值对象）**：`(appId, page, scene, envVersion)`，
  `fingerprint()` = `appId|envVersion|page|scene` 是**内存缓存键与资产主键的唯一派生处**
  ——新增输入维度只改这里。紧凑构造器拒绝空值与分隔符（拼接指纹只有单射才配当内容标识）。
  本次缺陷 ③ 与 `51-位置真值服务`（距离 memo 键漏坐标维度）同族，故把「派生缓存键必须
  含全部输入维度」从文档纪律升格为结构约束（派生函数与输入清单同处一地）。
- **`WxacodeAssetRepository`（JdbcTemplate，原生 SQL）**：`findImageBytes`（主键点查）+
  `insertIfAbsent`（`INSERT ... ON DUPLICATE KEY UPDATE asset_key = asset_key`，对齐
  `PointsUnlockRepository#insertIfAbsent` 范式：主代码零 catch 唯一键冲突——Hibernate 在
  DataIntegrityViolationException 后会把事务标记 rollback-only，catch 吞掉仍会炸
  UnexpectedRollbackException，见 22 号文档）。**不建 JPA 实体**：`ddl-auto=validate` 下
  实体 BLOB 类型映射会与 mediumblob 产生无谓方言耦合，而本表无实体级行为需求。
- **击穿防护**：`assetLocks`（指纹 → 锁）+ 锁内双检读库，同指纹首次物化只有一个请求
  外呼微信。
- 微信调用**复用** `auth/WechatService.getUnlimitedQrCode(scene, page, envVersion)`
  + 其 access_token 内存缓存（单例；check_path=false、JSON 错误 → 5001 已内部处理）。
- env_version = `wechat.qrcode-env`（application.yaml 默认 release；本地
  application-mysql.yaml 覆盖 develop——码打开对应 env 的小程序版本，对齐
  web-auth.qrcode-env 先例）；appId 由 `wechat.appid` 注入（指纹维度之一）。
- 迁移 `db/migration-mysql/V30__wxacode_assets.sql`：`qwt_wxacode_assets`
  （`asset_key` 主键 = 内容指纹 + 四维冗余列 + `image_bytes` mediumblob + `created_at`）。
  无 `deleted` 列（有意例外：资产由系统管理、无用户可见删除语义，重置 = 按指纹 DELETE
  一行；软删会让 insertIfAbsent 的唯一键语义复杂化而收益为零）。行数上界 = 环境数 ×
  静态场景数，无额外索引。
- 与舞伴域 `dancershare/WxacodeService` 同构但独立：舞伴海报码 page/scene 契约不同，
  且舞伴域前端已删处于休眠，不合并以免耦合休眠代码。

## 四、验证边界

- `./mvnw -q clean test-compile` 全绿（静态语法层面，按验证深度红线不启动服务）。
- 真机扫码行为 = 交用户验证；release 码扫开线上正式版，scene 落地解析需新版本发布后
  才在扫码链路生效（体验版联调临时改 trial——**改后指纹变化 ⇒ 新码即刻生效**）。
- 微信侧若更换码图样式/内容策略，指纹不变故不会重取（内容寻址的固有边界，判定可接受）。
