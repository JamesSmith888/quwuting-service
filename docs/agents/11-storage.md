# 文件存储（storage 模块）

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 文件存储（storage 模块）

### 架构：前端直传对象存储（双 provider，2026-09-17 起）

后端**不接收文件流**，仅签发上传凭证。前端凭凭证直传对象存储，上传成功后将公开 URL 写入业务字段随表单提交。2026-09-17 起支持双 provider（`storage.provider` 切换，详见下「OSS 切回国内」）：

```
前端 wx.chooseMedia 选图
  → GET /storage/upload-token（后端校验登录态 + 文件类型/大小 → 生成唯一路径 → 按 provider 签发凭证）
  → provider=supabase：wx.uploadFile 直传 Supabase Storage（Authorization: Bearer anonKey）
    provider=oss：wx.uploadFile PostObject 直传 https://{bucket}.{endpoint}（formData 表单签名，无 Authorization）
  → 上传成功 → publicUrl 写入业务字段（imageUrl / photos / wechatQr）
```

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/storage/upload-token` | 需登录 | 参数：category, fileName, fileSize → 返回 UploadTokenResponse（超集形态，`provider` 判别 supabase / oss 两套字段；NON_NULL 序列化，老客户端向后兼容） |

### 文件分类（FileCategory）

| 枚举值 | 路径前缀 | 用途 |
|--------|----------|------|
| VENUE_COVER | `venue-covers/` | 场所封面图 |
| VENUE_PHOTO | `venue-photos/` | 场所相册 |
| VENUE_QR | `venue-qr/` | 微信二维码 |

上传路径格式：`{prefix}/{userId}/{uuid}.{ext}`（按用户隔离，UUID 保证唯一；**两套 provider 路径格式一致**，存量对象迁移保持路径不变）。

### 配置

```yaml
# Supabase 通道（过渡期默认；与 DB 解耦的历史配置）
supabase:
  storage:
    project-url: ${SUPABASE_PROJECT_URL:}   # 如 https://xxxx.supabase.co
    anon-key: ${SUPABASE_ANON_KEY:}         # 公开密钥，RLS 策略控制访问
    bucket: ${SUPABASE_STORAGE_BUCKET:qwt-public}  # 公开读 bucket
    max-file-size: 5242880                  # 5MB
    allowed-extensions: .jpg,.jpeg,.png,.webp

# 对象存储 Provider 切换（2026-09-17 OSS 切回国内新增）
storage:
  provider: ${STORAGE_PROVIDER:supabase}    # supabase（默认）| oss
  oss:
    endpoint: ${OSS_ENDPOINT:}              # 如 oss-cn-hangzhou.aliyuncs.com（须与 ECS 同地域）
    bucket: ${OSS_BUCKET:}                  # 公共读 bucket
    instance-role-name: ${OSS_INSTANCE_ROLE_NAME:}   # ECS 实例角色名（生产主路径，无 AK）
    sts-endpoint: ${OSS_STS_ENDPOINT:https://sts.aliyuncs.com/}
    assume-role-arn: ${OSS_ASSUME_ROLE_ARN:}         # acs:ram::<UID>:role/<role>（本机兜底）
    assume-role-access-key-id: ${OSS_ASSUME_ROLE_AK:}     # 仅授予 sts:AssumeRole
    assume-role-access-key-secret: ${OSS_ASSUME_ROLE_SK:}
    internal-endpoint: ${OSS_INTERNAL_ENDPOINT:}  # 校验下载走内网免流量费；本地开发留空
    max-file-size: 5242880                  # 与 supabase 通道一致
    allowed-extensions: .jpg,.jpeg,.png,.webp
```

### OSS 切回国内（2026-09-17，双 provider 设计）

**动机**：DB 已迁阿里云 RDS MySQL（08-31），对象存储为最后一项海外依赖——① supabase.co 无法 ICP 备案（微信合法域名硬伤）；② 详情页大图跨洲加载慢（联系方式卡卡顿实证根因）；③ ImageContentValidator 跨洲下载校验 300-500ms。切杭州 OSS 后校验走内网（免费+毫秒级），且后端/ECS/RDS/OSS 归一一张账单。

**OSS 直传模型（服务端签名 PostObject，无 SDK 依赖；凭证 = ECS 实例角色 STS 临时凭证，生产无长期 AK）**：

- **凭证恒为 STS 临时凭证**（2026-09-18 统一：本地/生产同一套代码路径，无"长期 AK 直签"分支，`OssCredentialService` 单点解析）：<br>
  <b>① 优先 ECS 实例角色</b>（生产主路径）——ECS 绑定实例角色后，后端从实例元数据端点（http://100.100.100.200/latest/meta-data/ram/security-credentials/{role}）取临时凭证（AccessKeyId/Secret/SecurityToken），无需任何 AccessKey、由阿里云自动轮转；<br>
  <b>② 不可达（本机开发等非 ECS 环境）自动兜底 AssumeRole</b>——用仅具 `sts:AssumeRole` 权限（无任何 OSS 权限）的 RAM 子账号 AK 调 STS（V1 RPC 签名，零依赖）换同一角色的临时凭证；元数据失败后抑制 5min 重试窗口，避免本机每次凭证请求都等超时；<br>
  两条路径产出同构凭证（都带 SecurityToken），因此本地与生产前端直传代码完全一致；凭证剩余 &lt;5min 同步刷新；
- `/storage/upload-token` 在 `provider=oss` 时签发：`policy`（JSON：UTC 过期 15min + `{"bucket":...}` + `["eq","$key",uploadPath]` + `["content-length-range",1,limit]`）Base64 后以 **AccessKeySecret HmacSHA1 签名**（`StorageService.base64HmacSha1`，JDK 内置零依赖）；
- Secret 永不出后端；policy 限定精确 key，凭证泄露也只能写那一个对象，15min 过期；实例角色模式下 policy 附加 `["eq","$x-oss-security-token",token]` 条件；
- 前端 formData：`key / policy / OSSAccessKeyId / signature / success_action_status=200`（实例角色模式加 `x-oss-security-token`）+ file（**file 必须为最后字段**，wx.uploadFile 与浏览器 FormData 均天然满足）；
- 上传 URL = `https://{bucket}.{endpoint}`（虚拟主机式），publicUrl = host + `/` + uploadPath。

**URL 白名单与内网校验（ImageContentValidator）**：
- 白名单 = Supabase 前缀（projectUrl + legacyProjectUrls）+ OSS 前缀（`https://{bucket}.{endpoint}/`，**oss 配置完整即生效，与 provider 开关无关**——过渡期两代 URL 并存）；
- 配置 `internal-endpoint` 后，OSS URL 的校验下载改走内网；**内网不可达自动兜底公网重试**（本地开发不致校验失败）；
- 下载失败（non-200/网络异常）不再缓存 false（旧逻辑会把瞬时故障固化 10min），内容无效仍缓存。

**切换 runbook（与 08-22 Supabase 项目切换同款先例，全程可回退）**：
1. 建桶（杭州/标准/LRS/公共读）+ **创建 RAM 角色并附加最小权限策略**：角色的信任策略同时勾选「阿里云服务 = ECS」（生产实例角色）和（可选）指定 RAM 用户（本机 AssumeRole 用），再在 ECS 实例上授予该角色（控制台最佳实践：生产完全不建长期 AK）+ bucket CORS（admin-web 域）+ 微信合法域名加 bucket endpoint（**保留 supabase.co 域**）；
2. 部署本版后端（`provider=supabase` 默认值，零行为变化）+ 发版小程序/admin-web（按 provider 分支，新旧两端自适配）；
3. 全量后切流：服务器 config 填 OSS 四件套 + `STORAGE_PROVIDER=oss` 重启；
4. 对象迁移：`scripts/migrate_supabase_to_oss.py`（零依赖，枚举 Supabase → 下载 → V1 签名 PUT OSS，同路径；覆盖过渡窗口落 Supabase 的直传对象）；
5. URL 改写：`scripts/migrate_oss_url_rewrite_mysql.sql`（MySQL 方言；**列清单比 08-22 PG 版多 4 列**：group_chats.qr_code_url / app_feedbacks.image_url / venue_photos.url / dancer_photos.cover_url；先核对后改写再复核）；
6. 对账后退订 Supabase；`supabase.storage` 配置块保留（历史回退通道），白名单 legacy 前缀保留。

**本机调试（非 ECS）所需的最小 RAM 配置**：
- RAM 用户（如 `quwuting-sts-caller`）**只**授予：`{ "Effect": "Allow", "Action": ["sts:AssumeRole"], "Resource": "acs:ram::<UID>:role/<role>" }`——无任何 OSS 权限；
- 该角色的信任策略把此 RAM 用户加入受信主体，生产侧则把 ECS 加入受信主体（两者可同时存在，角色与权限策略共用一份）；
- 配置填 `assume-role-arn` + `assume-role-access-key-id/secret` 即可，代码路径与生产完全相同。

**角色权限策略 JSON**（附加到角色，替换桶名——生产实例角色 / 本机 AssumeRole 共用同一角色）：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["oss:PutObject", "oss:PostObject", "oss:GetObject", "oss:ListObjects"],
      "Resource": ["acs:oss:*:*:<bucket>", "acs:oss:*:*:<bucket>/*"]
    }
  ]
}
```

### 安全模型（2026-08-12 修订，恶意文件防线）

- `anonKey` 是 Supabase 的公开密钥（嵌于小程序包，人人可提取），**不构成安全边界**——
  前端直传必须放行 anon 写，token 接口对扩展名/大小的校验可被伪造参数整体绕过
- **真正的防线 = `ImageContentValidator`**：业务提交（图片 URL 落库）时下载文件做内容级校验——
  URL 必须为本应用公开桶前缀（防外部图床/SSRF）、内容大小 ≤ maxFileSize、
  magic bytes 命中 JPEG/PNG/WebP（拒 exe/HTML/脚本改名伪造）、JPEG/PNG 解析宽高限尺寸（防解压炸弹）
- 挂载点：venue 创建/更新（imageUrl/photos/wechatQr）、dancer 创建/更新（avatarUrl）与相册（photos）、
  user 头像（avatarUrl）、claim 营业执照（licenseUrls）——**新增图片 URL 落库字段必须挂载校验**
- **编辑未变更跳过校验（2026-08-24）**：venue `updateVenue` 对 imageUrl/wechatQr 仅在与库中现值
  **不同**时才校验（未变更相等跳过）——存量 URL（历史 picsum 占位图 / 高德图床直写主图）不在
  白名单内，编辑表单回显原样提交必 1005 拒绝（「只改位置也报图片地址不合法」即此根因）。
  创建/新增 URL 仍全量校验，安全语义不变；清空图片（null）由 validator 空值放行兜底
- 校验结果按 URL 缓存（Caffeine 10min），编辑全量覆盖旧图不重复下载
- `serviceRoleKey` 绝不下发前端（本模块不使用）
- 后端在签发凭证前完成文件类型/大小校验（第一道，可绕过），内容校验在业务提交时兜底（第二道，不可绕过）

### 约束

- 文件分类：场所图片（封面/相册/二维码）、用户头像、舞伴照片/头像、认领营业执照（见 FileCategory）
- 新增文件分类 = **三处同步**：① 扩展 `FileCategory` 枚举 + 前端 `FileCategory` 类型；② **Supabase 控制台把新前缀加入 `qwt-public insert anon` RLS 白名单**（`storage.objects` 策略 `(storage.foldername(name))[1] IN (...)`，见下「RLS 上传白名单」）——**漏配 = 该分类上传生产 403**（2026-08-24 事故：GROUP_QR 08-17 新增但白名单漏配，群二维码上传一直失败）；③ 图片类 URL 落库字段挂载 `ImageContentValidator`
- 禁止后端接收 MultipartFile 中转上传（前端直传，后端零文件流）
- 禁止在凭证响应中暴露 serviceRoleKey
- 图片 URL 落库前必须经 `ImageContentValidator` 校验（见「安全模型」挂载点）

### RLS 上传白名单（2026-08-24 事故记录）

`qwt-public` bucket 的匿名上传受 RLS 限制——`storage.objects` 上 `qwt-public insert anon` 策略只放行**白名单一级目录**（当前：`venue-covers / venue-photos / venue-qr / user-avatars / dancer-photos / dancer-avatars / dancer-contact-qr / claim-licenses / group-qr / dancer-videos`）。**前端直传走 anon key，凡路径前缀不在白名单一律被拒**——⚠️ **Supabase Storage 对 RLS 拒绝返回 HTTP 400**（响应体 `{"statusCode":"403","error":"Unauthorized","message":"new row violates row-level security policy","code":"AccessDenied"}`），前端 uploadToSupabase 非 200/201 报「上传失败（400）」——**排障时不要把 400 当参数错，先看响应体 code 是否 AccessDenied**；读（SELECT）对所有 anon 开放（bucket 公开读）。

- **新增 FileCategory 后必须同步在 Supabase 控制台更新该策略**（或 DDL 重建同名策略）——`FileCategory` 枚举与 RLS 白名单无自动联动，两侧是手工契约
- **⚠️ DANCER_VIDEO 白名单补配（2026-08-24 事故：舞伴短视频上传 400）**：08-22 新增 `DANCER_VIDEO("dancer-videos")` 分类（前端 video-upload + 后端枚举/校验均已就绪），但 RLS 白名单漏配 `dancer-videos` → 视频直传全部 HTTP 400 `AccessDenied`（同 GROUP_QR 08-17 漏配同款三处同步遗漏，本次漏的是第 ② 处）。修复 = 控制台执行下文「RLS 白名单补配 SQL」加入 `dancer-videos`。**教训：新增 FileCategory 三处同步校验点不止枚举/前端，必须以「直传生产实测 200」为验收标准**（`curl -F file=@test.jpg` 直传新前缀，见下文验证）。
- **历史遗留前缀 `dancer-videos/`**（旧项目 tkyreautvukkwpwmisbg 时代存量，与现行分类同名不同源）：存量数据切新项目后未迁移，遇此类 URL 需人工转存到白名单目录并同步改写数据库 URL
- **切 Supabase 项目（08-08）后存量 OLD 前缀 URL 全面清零**：全库扫描所有 `%url%` 列 `LIKE 'https://tkyreautvukkwpwmisbg%'`，先转存对象到新 bucket 同路径（保持路径则仅换域名；不在白名单的目录改存白名单目录），再 `replace` 改写前缀——**对象不存在时改写前缀会变成新 URL 裂图且 ImageContentValidator 下载 404 继续报 1005**

---

