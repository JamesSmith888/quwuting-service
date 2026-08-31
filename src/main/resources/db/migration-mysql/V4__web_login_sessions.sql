-- Web 管理后台登录会话（2026-08-31）
-- 扫码登录链路：网页生成会话 → 用户微信扫小程序码打开「确认登录」页 →
-- 小程序内确认（后端校验 ADMIN）→ 网页轮询本表取 token。
-- 安全设计：
--   * session_id 为 29 位随机 hex（防枚举），加 1 字符前缀组成小程序码 scene（30 字符 ≤32 上限）
--   * token_issued 一次性下发：轮询取走后置空，防重放
--   * expires_at 5 分钟 TTL；轮询时惰性置 EXPIRED
--   * deleted 恒 false（无业务删除语义）
CREATE TABLE qwt_web_login_sessions (
    id            bigint NOT NULL AUTO_INCREMENT,
    created_at    datetime(6),
    updated_at    datetime(6),
    deleted       tinyint(1) NOT NULL DEFAULT 0,
    session_id    varchar(64) NOT NULL,
    status        varchar(20) NOT NULL DEFAULT 'PENDING',  -- PENDING/CONFIRMED/REJECTED/EXPIRED
    user_id       bigint NULL,
    token_issued  varchar(1024) NULL,
    expires_at    datetime NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX qwt_idx_web_login_sessions_sid ON qwt_web_login_sessions (session_id);
CREATE INDEX qwt_idx_web_login_sessions_status ON qwt_web_login_sessions (status);
