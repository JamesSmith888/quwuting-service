-- ============================================================================
-- qwt_tag_interactions.liked NOT NULL 约束迁移（2026-08-05）
-- ============================================================================
-- 背景（根因，详见 AGENTS.md「schema 变更纪律」章节）：
--   实体 TagInteraction 在"标签点赞 → Reaction 快速反馈"重构中移除了 liked 字段
--   （javadoc 声称"liked 列已废弃并移除"），但该列从未从实际数据库表删除：
--   - dev（ddl-auto: update）只新增缺失列/约束，从不删除实体已移除的列，
--     也从不把现有列改为可空 —— 列仍为 NOT NULL 且无 DEFAULT
--   - prod（ddl-auto: validate）不校验列级 NOT NULL（见 repair-schema-identity.sql 注释）
--   → 代码侧 insert 不再提供 liked，运行期任何"首次评分"插入都违反 NOT NULL 约束，
--     报错 "null value in column \"liked\" ... violates not-null constraint"，
--     且被 TagInteractionService.score 的 DataIntegrityViolationException 兜底
--     catch 静默吞掉（误当并发唯一键冲突），事务 rollback-only 后 commit 抛
--     UnexpectedRollbackException（接口对外仍 200 + code=5000，表面成功实为失败）。
--
-- 修复策略：列已完全废弃（Java 代码零引用，仅 seed 脚本显式写 false），
--   保留列但取消 NOT NULL —— 与"列不再读写"的实体语义对齐，非破坏性、可回滚，
--   不影响历史数据；彻底删列（DROP COLUMN）为破坏性操作，如需执行请人工评估
--   并取消下方注释（seed-dev.sql 仍显式插入 liked，删列前需同步移除其列引用）。
--
-- 执行时机：dev 环境任意时刻可执行（幂等可重复）；本脚本不可由 ddl-auto 替代。
-- ============================================================================

-- 1) 取消 NOT NULL（幂等：重复执行无副作用）
ALTER TABLE qwt_tag_interactions ALTER COLUMN liked DROP NOT NULL;

-- 2)（可选，破坏性）彻底移除废弃列 —— 人工确认后取消注释执行
-- ALTER TABLE qwt_tag_interactions DROP COLUMN liked;

-- 3) 验证（期望输出 is_nullable = YES）
-- SELECT column_name, is_nullable, data_type
-- FROM information_schema.columns
-- WHERE table_name = 'qwt_tag_interactions' AND column_name = 'liked';
