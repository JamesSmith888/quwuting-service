package org.quwuting.quwutingservice.venuefeedback.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;

import java.time.LocalDateTime;

/**
 * 用户上报（原"场所信息纠错反馈"，2026-08-05 泛化为统一上报模板）。
 * <p>
 * 任意登录用户在详情页发现信息缺失/有误时提交，管理员在管理后台
 * （GET /admin/reports）查看并处理。处理状态由 {@link ReportStatus} 状态机
 * 承载（PENDING → RESOLVED / DISMISSED），handledBy / handledAt 记录处理动作。
 * <p>
 * 历史列说明：旧 `handled` 布尔列为状态机引入前的遗留列（NOT NULL 无默认值，
 * ddl-auto:update 无法删列/取消 NOT NULL），本实体**保留字段映射兜底**——
 * insert 时写入默认 false，避免遗留列违反 NOT NULL。业务逻辑一律使用 status，
 * 禁止读取/写入 handled（标记 @Deprecated，彻底清理需手动 SQL，见 AGENTS.md
 * 「Schema 演进」章节"无法避免"场景清单）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_feedbacks", indexes = {
        @Index(name = "qwt_idx_feedbacks_venue_id", columnList = "venueId"),
        @Index(name = "qwt_idx_feedbacks_user_id", columnList = "userId"),
        @Index(name = "qwt_idx_feedbacks_status_created", columnList = "status, createdAt")
})
public class VenueFeedback extends BaseEntity {

    /** 所属场所 ID（qwt_venues.id） */
    @Column(nullable = false)
    private Long venueId;

    /**
     * 上报提交者用户 ID（可空 = 匿名上报，2026-08-06 放宽）。
     * <p>
     * 匿名决策：上报不强推登录（微信审核与低门槛参与友好），匿名上报 userId = null，
     * 管理员仍可处理（管理端不依赖上报者身份）；但匿名记录**无法在个人中心回看**
     * （「我的上报记录」按 userId 查询），处理结果也无法回传——前端在匿名提交后
     * 提示"登录后上报可查看处理结果"。登录用户上报 → userId 落库 → 个人中心可见
     * 全部记录与管理端处理结果。这是"匿名可参与、追踪需登录"的明确设计决策。
     * <p>
     * ⚠ 列约束迁移：user_id 由 NOT NULL 放宽为可空属**修改已有列约束**，
     * ddl-auto:update 无法完成（只加列/约束、不 MODIFY 列），需一次性手动 SQL
     * （见 {@code src/main/resources/db/migrate-feedback-anonymous.sql}，
     * 执行时机与幂等性说明见 AGENTS.md「Schema 演进 → 无法避免的场景」）。
     */
    @Column
    private Long userId;

    /** 上报类型（通用模板的类型维度，新增场景扩展枚举即可） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private FeedbackType type;

    /** 补充说明（可选，最多 500 字） */
    @Column(length = 500)
    private String note;

    /**
     * 处理状态（PENDING / RESOLVED / DISMISSED）。
     * <p>
     * 列 DDL 默认值的<b>唯一声明通道</b>是 {@link ColumnDefault}；Java 字段初始化器
     * 只负责内存态默认值、不参与 DDL 生成。ddl-auto:update 对已有数据的表自动加列时
     * 生成 `ADD COLUMN ... NOT NULL DEFAULT 'PENDING'`，PostgreSQL 快速默认值不重写
     * 表，存量行自动落为 PENDING——这是"schema 演进默认走 JPA 自动更新、不手动执行
     * SQL"的前提（见 AGENTS.md「Schema 演进（自动更新优先）」章节）。
     * <p>
     * ⚠ 禁止在 columnDefinition 中重复声明 DEFAULT：Hibernate 会把元数据派生的
     * `default ...` 追加到 columnDefinition 原文之后，生成 `... DEFAULT 'PENDING'
     * default 'PENDING' ...` 非法 DDL，Postgres 报 "multiple default values
     * specified"（2026-08-05 事故根因，见 AGENTS.md「Schema 演进」事故小节）。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'PENDING'")
    private ReportStatus status = ReportStatus.PENDING;

    /**
     * 处理人用户 ID（管理员标记，未处理为 null）。
     * 自动加列：可空列 update 直接成功，无需默认值。
     */
    private Long handledBy;

    /** 处理时间（管理员标记，未处理为 null）。自动加列：可空列直接成功。 */
    private LocalDateTime handledAt;

    /**
     * 管理员处理结果说明（2026-08-06 新增，可选，最多 500 字）。
     * <p>
     * 「管理员处理完成每个上报后，反馈处理结果给用户」的载体：管理员在 resolve/dismiss
     * 时填写处理说明（如"已核实并更新门票价格"），随「我的上报记录」回传上报者，
     * 个人中心展示处理状态 + 处理结果。可空列，ddl-auto:update 自动加列，无需迁移。
     * 入库前经 {@link org.quwuting.quwutingservice.common.text.TextSanitizer} 清洗
     * （防注入分层约定见其 javadoc）。
     */
    @Column(length = 500)
    private String handleNote;

    /**
     * @deprecated 历史遗留列兜底映射（状态机引入前的布尔字段）。
     * 库中两种状态均兼容：列已存在（NOT NULL）→ 匹配无操作；列已被历史脚本删除 →
     * update 按 {@link ColumnDefault}（DDL 默认值唯一声明通道）自动重建为
     * `boolean NOT NULL DEFAULT false`，存量行落默认值。仅为保证 insert 提供值，
     * 业务逻辑禁止读写——一律使用 {@link #status}。彻底清理该列需要手动 SQL
     * （ddl-auto:update 不删列），见 AGENTS.md「无法避免的场景」。
     */
    @Deprecated
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean handled = false;
}
