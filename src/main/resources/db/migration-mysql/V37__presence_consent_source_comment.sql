-- V37：到店足迹状态确立流水的来源列注释更新（2026-10-03，docs/agents/52-venue-presence.md §5）。
--
-- 背景：授权模型由「默认开启」（09-29 四轮）改为「第一次真正到店时询问」（10-03 五轮），新增来源
-- PROMPT（到店首问回答）；DEFAULT 停止写入，只存在于历史行（默认开启期首次 ping 时补记、用户
-- 从未被询问——它是证据链的缺口而不是同意）。服务端自此以「最新一条 = 显式来源且 enabled」作为
-- 采集门禁，库表注释必须如实说明三种来源的语义，否则直接读库的取证会把 DEFAULT 读成「已同意」。
--
-- 只改 COMMENT：列类型 / 可空性不变（varchar(16) 容得下 PROMPT），InnoDB 下为元数据变更。
ALTER TABLE qwt_venue_presence_consents
    MODIFY COLUMN source varchar(16) NOT NULL
        COMMENT '确立来源：PROMPT=到店首问回答 / USER=设置页手动变更 / DEFAULT=历史默认开启期补记（未经询问，已停写，不构成同意）';
