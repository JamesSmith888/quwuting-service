-- 门店表 district（区/县）放宽为可空（2026-08-08）
-- 背景：行政区非业务必填，城市（city）已足够定位粒度；区县仅用于展示拼接
-- （卡片位置行/详情页完整地址/地图导航）与可选筛选，缺失时应容忍 null，
-- 前端展示统一以 '' 兜底。同步 V1 baseline 中该列定义（新库直接可空）。
ALTER TABLE qwt_venues ALTER COLUMN district DROP NOT NULL;
