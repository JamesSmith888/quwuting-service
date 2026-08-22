-- 舞伴相册视频扩展（2026-08-22）：
-- 短视频 = 管理员直发 + PENDING 审核后公开，与照片同表同审核链（媒体无关契约，
-- 见 09-dancer-and-points.md「根因与防复发 · 媒体无关契约」）。
-- kind 区分媒体类型（PHOTO 默认 / VIDEO）；cover_url = 视频封面图（chooseMedia
-- thumb 帧上传，列表/轮播/审核预览用）；duration_seconds = 时长（秒，展示 mm:ss）。
ALTER TABLE qwt_dancer_photos
    ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'PHOTO',
    ADD COLUMN cover_url VARCHAR(500),
    ADD COLUMN duration_seconds INT NOT NULL DEFAULT 0;
