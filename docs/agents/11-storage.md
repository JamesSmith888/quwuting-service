# 文件存储（storage 模块）

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 文件存储（storage 模块）

### 架构：前端直传 Supabase Storage

后端**不接收文件流**，仅签发上传凭证。前端凭凭证直传 Supabase Storage REST API，上传成功后将公开 URL 写入业务字段随表单提交。

```
前端 wx.chooseMedia 选图
  → GET /storage/upload-token（后端校验登录态 + 文件类型/大小 → 生成唯一路径 → 返回凭证）
  → wx.uploadFile 直传 Supabase Storage（Authorization: Bearer anonKey）
  → 上传成功 → publicUrl 写入业务字段（imageUrl / photos / wechatQr）
```

### 接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/storage/upload-token` | 需登录 | 参数：category, fileName, fileSize → 返回 UploadTokenResponse |

### 文件分类（FileCategory）

| 枚举值 | 路径前缀 | 用途 |
|--------|----------|------|
| VENUE_COVER | `venue-covers/` | 场所封面图 |
| VENUE_PHOTO | `venue-photos/` | 场所相册 |
| VENUE_QR | `venue-qr/` | 微信二维码 |

上传路径格式：`{prefix}/{userId}/{uuid}.{ext}`（按用户隔离，UUID 保证唯一）。

### 配置

```yaml
supabase:
  storage:
    project-url: ${SUPABASE_PROJECT_URL:}   # 如 https://xxxx.supabase.co
    anon-key: ${SUPABASE_ANON_KEY:}         # 公开密钥，RLS 策略控制访问
    bucket: ${SUPABASE_STORAGE_BUCKET:qwt-public}  # 公开读 bucket
    max-file-size: 5242880                  # 5MB
    allowed-extensions: .jpg,.jpeg,.png,.webp
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
- 新增文件分类只需扩展 `FileCategory` 枚举 + 前端 `FileCategory` 类型
- 禁止后端接收 MultipartFile 中转上传（前端直传，后端零文件流）
- 禁止在凭证响应中暴露 serviceRoleKey
- 图片 URL 落库前必须经 `ImageContentValidator` 校验（见「安全模型」挂载点）

---

