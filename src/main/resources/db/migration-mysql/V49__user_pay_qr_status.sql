-- ============================================================================
-- V49（MySQL 轨道 = db/migration-mysql）: 计时「收款码」配置状态（只存张数，不存图）
--      （2026-10-13 运营需求；前端权威文档 = quwuting 仓 docs/agents/60-pay-qr.md 决策 14）
--
-- ⚠️ 编号说明：取号前已核对 db/migration-mysql 最大号 = V48；并行工作流会抢号，部署前须再核对无第二份 V49
--    （Flyway 对重复版本号直接拒启）。PG 遗留轨道（db/migration）编号各自独立、勿混用。
--
-- ── 需求与红线的关系 ──────────────────────────────────────────────────────────
-- 运营要在 admin 统计并查看「配置过收款码的用户」。此前收款码整条链路「只存本机、不出端」
-- （60 号决策 1：上传 = 平台开始保管支付凭证）。本迁移**不改变这条红线**：
--   · 上报的只有 **张数**（整数 0~6），不含图片、不含文件名 / 路径 / 摘要 / 任何可还原收款码的信息；
--   · 平台仍然不经手、不存储、不传输任何支付凭证——只知道「这个用户在自己手机里放了几张」。
--   · 客户端上报模块 services/payQrStatus 与数据层 services/payQr 物理分离（payQr 仍零网络，门禁 P3 不变）。
--
-- ── 表：qwt_user_pay_qr_status（每用户恒一行）─────────────────────────────────
--   · qr_count：客户端最近一次上报的张数；0 = 已清空（保留行以回答「曾配置过」）。
--   · first_configured_at：服务端时钟下**首次 count>0** 的时刻（COALESCE 保首次，之后不再改）。
--     回答「近 7/30 天新增了多少人配置收款码」；不用 created_at 代替——行也会由「先报 0」创建。
--   · updated_at：**仅在张数变化时**推进（重复上报同值不动它）⇒ 即「最近一次变更」，列表排序依据。
--   · 无外键（全库约定）；时间列由 Java 传入（JVM = 北京时间，禁 DB now()）。
--
-- ── 部署顺序 ────────────────────────────────────────────────────────────────
-- 后端先于小程序（纯增量：新表 + 新接口；旧客户端不知道该接口，行为零变化）。
-- ============================================================================

CREATE TABLE qwt_user_pay_qr_status (
    id                  bigint      NOT NULL AUTO_INCREMENT,
    created_at          datetime(6) NOT NULL COMMENT '首次上报时刻（JVM 北京时间）',
    updated_at          datetime(6) NOT NULL COMMENT '最近一次张数变化时刻（同值重复上报不推进）',
    deleted             tinyint(1)  NOT NULL DEFAULT 0,
    user_id             bigint      NOT NULL COMMENT '用户（qwt_users.id）',
    qr_count            int         NOT NULL COMMENT '本机当前持有的收款码张数（0 = 已清空；只存张数，不存图）',
    first_configured_at datetime(6) NULL COMMENT '首次 qr_count>0 的服务端时刻（之后不再改）',
    PRIMARY KEY (id),
    CONSTRAINT qwt_uk_pqs_user UNIQUE (user_id)
) COMMENT='计时收款码配置状态（每用户一行；只存张数，不存收款码图片或任何可还原信息）';

-- admin 名单按「最近一次变更」倒序
CREATE INDEX qwt_idx_pqs_updated ON qwt_user_pay_qr_status (updated_at);
