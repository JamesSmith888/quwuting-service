-- 修订舞伴资料标签「线上 / 线下」说明文案（2026-08-24）。
-- 背景：V40 初始文案存在两个问题，经产品拍板修订——
--   ① 互斥表述失实：「线上」写「仅提供线上互动…不提供线下见面伴舞」、「线下」写
--      「以线下见面伴舞为主」——线上/线下并非二选一，同一舞伴可同时具备两种服务；
--   ② 含偏暗示/风险文字：「视频连线、线上陪跳」「线下见面伴舞」等在平台审核语境
--      下有擦边暗示风险。
-- 修订原则：简短、中性、对称——各自声明「可提供」+「与另一方不冲突」。
-- 幂等安全：V40 保证本表数据已存在，UPDATE 恒作用于字典权威文案。
UPDATE qwt_tag_dict
SET description = '可提供线上互动，与线下不冲突',
    updated_at  = CURRENT_TIMESTAMP
WHERE scope = 'DANCER' AND text = '线上' AND deleted = false;

UPDATE qwt_tag_dict
SET description = '可提供线下互动，与线上不冲突',
    updated_at  = CURRENT_TIMESTAMP
WHERE scope = 'DANCER' AND text = '线下' AND deleted = false;
