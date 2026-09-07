# 36 · 分享小程序码域（wxacode）

> 2026-09-07 新增。非微信平台分享桥接：用户在小程序内生成/保存小程序码图片（官方
> getwxacodeunlimit 能力），贴到微信以外平台扫码回流。前端权威文档 =
> quwuting/docs/agents/40-wxacode-share.md（入口/组件/scene 落地解析/合规辨析）。

## 一、端点契约（公开软鉴权，image/jpeg 二进制直出）

| 端点 | scene | page | 说明 |
|------|-------|------|------|
| `GET /venues/{id}/wxacode.jpg` | `id=<venueId>`（登录追加 `&s=<uid>`） | `pages/venue-detail/venue-detail` | 门店码；门店不存在/已删除 → 1001；微信失败 → 5001 |
| `GET /wxacode/home.jpg` | `home` | `pages/index/index` | 平台码；首页不消费 scene |

- 响应：`Content-Type: image/jpeg` + `Cache-Control: max-age=86400`（与服务端缓存
  TTL 对齐）；**非 ApiResponse JSON**——图片直出给前端 image 直连/落盘。
- 微信接口失败 → 5001 JSON（image 直连表现为加载失败，前端 binderror 兜底）。
- 归因 uid = `UserContext.getCurrentUserId()` 服务端权威注入，请求参数不暴露。

## 二、实现要点

- 新域 `org.quwuting.quwutingservice.wxacode`：`WxacodeShareService` +
  `WxacodeShareController`（方法级 @GetMapping，类级无 @RequestMapping——
  `/wxacode` 根路径已被舞伴域 `dancershare/WxacodeController`（`GET /wxacode?dancerId=`）
  占用；`/wxacode/home.jpg` 与其精确匹配不冲突）。
- 微信调用**复用** `auth/WechatService.getUnlimitedQrCode(scene, page, envVersion)`
  + 其 access_token 内存缓存（单例；check_path=false、JSON 错误 → 5001 已内部处理）。
- env_version = `wechat.qrcode-env`（application.yaml 默认 release；本地
  application-mysql.yaml 覆盖 develop——码打开对应 env 的小程序版本，对齐
  web-auth.qrcode-env 先例）。
- 缓存：`page|scene` → 码图字节 24h（ConcurrentHashMap，同 scene 码相同防重复外呼）；
  容量护栏 500 超限整体清空（键空间 = 门店 × 活跃用户，当前规模远不可达，防御性兜底）。
- 零 DB 迁移。
- 与舞伴域 `dancershare/WxacodeService` 同构但独立：舞伴海报码 page/scene 契约不同，
  且舞伴域前端已删处于休眠，不合并以免耦合休眠代码。

## 三、验证边界

- `./mvnw -q clean test-compile` 全绿（静态语法层面，按验证深度红线不启动服务）。
- 真机扫码行为 = 交用户验证；release 码扫开线上正式版，scene 落地解析需新版本发布后
  才在扫码链路生效（体验版联调临时改 trial）。
