-- =====================================================================
-- 去舞厅：RLS 白名单补配 dancer-videos（2026-08-24 事故：舞伴短视频上传 400）
--
-- 事故根因：08-22 新增 FileCategory.DANCER_VIDEO("dancer-videos")（前端 video-upload
-- 组件 + 后端枚举/校验均已就绪），但 Supabase 控制台 `qwt-public insert anon`
-- RLS 策略白名单漏配 `dancer-videos` 前缀 → 前端直传全部失败。
--
-- ⚠️ 关键排障认知：Supabase Storage 对 RLS 拒绝返回 **HTTP 400**（响应体
--    {"statusCode":"403","error":"Unauthorized","message":"new row violates
--    row-level security policy","code":"AccessDenied"}），前端 uploadToSupabase
--    非 200/201 报「上传失败（400）」——不要把 400 当参数错误排查，先看响应体
--    code 是否 AccessDenied。
--
-- 使用方法（在 Supabase 控制台 → SQL Editor 执行，项目 ijhuwkpumjnqxmfwobog）：
--   1. 先跑「1. 核对」段，确认 insert anon 策略的实际名称与白名单；
--   2. 跑「2. 修复」段（幂等，重复执行无害——用 DROP + CREATE 重建保证白名单精确）；
--   3. 跑「3. 验证」段 + 外部 curl 直传实测（见文件尾注释）。
-- =====================================================================

-- ---------- 1. 核对：现有 storage.objects 策略 ----------
-- 重点看 INSERT 且角色含 anon 的策略（with_check 里的 foldername IN (...) 即白名单）。
-- ⚠️ 不用 pg_get_expr——部分环境（Supabase）下 pg_policies 的 qual/with_check 列
--    以 text 形式暴露，pg_get_expr 不匹配报 42883；直接 SELECT 列即可输出表达式文本。
SELECT policyname, cmd, roles,
       qual        AS using_expr,
       with_check  AS check_expr
FROM pg_policies
WHERE schemaname = 'storage' AND tablename = 'objects'
ORDER BY policyname;

-- ---------- 2. 修复：重建 insert anon 策略，白名单加入 dancer-videos ----------
-- ⚠️ 策略名以「1. 核对」实际输出为准（默认假设为 "qwt-public insert anon"；
--    若不同，把下方两处策略名替换为真实名称，并保留其原 with check 中的其他条件）
DROP POLICY IF EXISTS "qwt-public insert anon" ON storage.objects;

CREATE POLICY "qwt-public insert anon" ON storage.objects
  FOR INSERT TO anon
  WITH CHECK (
    bucket_id = 'qwt-public'
    AND (storage.foldername(name))[1] IN (
      'venue-covers',
      'venue-photos',
      'venue-qr',
      'user-avatars',
      'dancer-photos',
      'dancer-avatars',
      'dancer-contact-qr',
      'claim-licenses',
      'group-qr',
      'dancer-videos'           -- 2026-08-24 补配
    )
  );

-- ---------- 3. 验证：策略已含 dancer-videos ----------
SELECT policyname, cmd, roles,
       with_check AS check_expr
FROM pg_policies
WHERE schemaname = 'storage' AND tablename = 'objects'
  AND cmd = 'INSERT' AND 'anon' = ANY (roles);

-- =====================================================================
-- 外部直传实测（修复后必跑，验收标准 = HTTP 200；这是「三处同步」的最终校验点）：
--
--   printf 'probe' > /tmp/qwt-probe.mp4
--   curl -s -o /dev/null -w "HTTP %{http_code}\n" \
--     -X POST "https://ijhuwkpumjnqxmfwobog.supabase.co/storage/v1/object/qwt-public/dancer-videos/probe/qwt-probe.mp4" \
--     -H "Authorization: Bearer <anon-key(application-dev.yaml supabase.storage.anon-key)>" \
--     -F "file=@/tmp/qwt-probe.mp4"
--
-- 期望输出：HTTP 200（修复前为 HTTP 400 + body code=AccessDenied）。
-- 实测后记得删除 probe 对象（Storage 控制台或 service key DELETE）。
-- =====================================================================
