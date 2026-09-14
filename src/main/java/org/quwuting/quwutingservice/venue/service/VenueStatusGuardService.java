package org.quwuting.quwutingservice.venue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.venue.dailyopening.enums.GuardSkipReason;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.enums.VenueStatusSource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 门店状态「权威层级」门禁（2026-09-14，V25；方案见 docs/agents/48）。
 *
 * <h2>要解决的问题（根因，不是症状）</h2>
 * {@code qwt_venues.status} 原先是一个<b>没有所有权</b>的字段：管理端人工编辑、报告采纳、
 * 每日舞讯（Agent 通道）都能无条件覆盖它（last-write-wins），库里也没有任何字段记录
 * 「这个值是谁的判断」。于是管理员手工修正的状态隔天就被舞讯批量写库冲掉；而舞讯本身
 * 是第三方整理，并不 100% 可靠（会漏报 / 误报）。
 *
 * <h2>本类承载的领域不变量（唯一实现，禁止在调用方各写一份）</h2>
 * <pre>
 *   信息来源权威序（高 → 低）：人工直改  >  外部舞讯推断
 *   · 人工通道（管理端编辑 / 认领人编辑 / 采纳上报）改状态 ⇒ 打「人工锁」（有时限）；
 *   · 外部舞讯通道（batch / batch-suspend）写状态前必须过门禁：
 *       - 已永久豁免（dailySyncExempt）        ⇒ 跳过（EXEMPT）
 *       - 人工锁未过期（statusLockedUntil）    ⇒ 跳过（LOCKED）
 *       - 其余                                ⇒ 允许，写后接管所有权（SYNC）并清锁
 * </pre>
 *
 * <h2>为什么判定必须长在服务端</h2>
 * 若只在采集 Skill 侧维护一份「手工改过的店黑名单」，会同时踩三个坑：
 * ① Web 后台「同步报告勾选应用」走的是另一条入口（{@code VenueSyncReportService} →
 * {@code DailyOpeningService.applyBatch}），Skill 侧管不到；② 「锁会过期」的时间语义在
 * Skill 字典里无处安放；③ 两处实现必然漂移。故规则下沉为服务端不变量，任何调用方都受约束，
 * Skill 侧只负责展示与如实汇报。
 *
 * <h2>锁为什么有时限、且时长不对称</h2>
 * 人工判断也会过期（店可能真的改了），所以「人工优先」是有时限的优先权，不是永久黑名单；
 * 到期后自动回归外部同步，无需清理任务。时长按人工设定到的目标状态不对称——<b>错判代价
 * 不对称决定观察期长短</b>：人工置 OPEN（舞讯漏报 / 当日临时恢复）意图时效极短，锁长了会
 * 让用户看到「营业」白跑，取 3 天；人工置停业类（这家确实关了）漏判代价是用户白跑 + 平台
 * 失信，取 7 天。时长与总开关都是运营配置，可热更新。
 *
 * <h2>反复冲突的正确处置</h2>
 * 同一家店每隔几天就被人工改一次，说明问题<b>不在时间维度</b>（该店不在舞讯覆盖范围 /
 * 被系统性漏报）。此时不该继续加长锁（那是打补丁），而应升级为永久豁免
 * （{@link #setExempt}）——把「周期性返工」变成「一次性事实声明」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueStatusGuardService {

    /** 总开关（应急）：'false' ⇒ 全局关闭本机制（人工锁不再生效，回到无条件覆盖）。 */
    public static final String KEY_STATUS_LOCK_ENABLED = "venue.status_lock.enabled";

    /** 人工置「营业中」后的锁时长（天）；'0' ⇒ 该方向不打锁。 */
    public static final String KEY_STATUS_LOCK_HUMAN_OPEN_DAYS = "venue.status_lock.human_open_days";

    /** 人工置「非营业中」（暂停/停业/休息/装修）后的锁时长（天）；'0' ⇒ 该方向不打锁。 */
    public static final String KEY_STATUS_LOCK_HUMAN_CLOSED_DAYS = "venue.status_lock.human_closed_days";

    /**
     * 外部通道的「程序化写库」来源声明值。管理端在同步报告里<b>勾选应用</b>属人工背书，
     * 可越过人工锁（人工的明确决定永远能推翻前一个人工决定），但会清锁并把所有权交回自动同步。
     */
    public static final String CHANGE_SOURCE_AGENT_BATCH = "AGENT_BATCH";

    private static final boolean DEFAULT_ENABLED = true;
    private static final int DEFAULT_OPEN_DAYS = 3;
    private static final int DEFAULT_CLOSED_DAYS = 7;

    private final OpsConfigService opsConfigService;

    /**
     * 门禁判定结果。
     *
     * @param allowed     true = 允许覆盖；false = 跳过（不写库、不通知、不发公告）
     * @param reason      跳过原因（allowed=true 时为 null）
     * @param lockedUntil 人工锁到期时刻（reason=LOCKED 时非空，供汇报展示）
     */
    public record Decision(boolean allowed, GuardSkipReason reason, LocalDateTime lockedUntil) {

        static Decision allow() {
            return new Decision(true, null, null);
        }

        static Decision deny(GuardSkipReason reason, LocalDateTime lockedUntil) {
            return new Decision(false, reason, lockedUntil);
        }
    }

    /**
     * 外部舞讯通道写入门禁（逐店判定；两个批量通道共用）。
     * <p>
     * 判定顺序：永久豁免优先于时限锁——豁免是「事实维度的例外」，锁是「时间维度的优先」，
     * 前者不可能被时间解除，先判可省一次比较，也让汇报原因更准确。
     */
    public Decision decideExternalWrite(Venue venue, LocalDateTime now) {
        if (venue.isDailySyncExempt()) {
            return Decision.deny(GuardSkipReason.EXEMPT, null);
        }
        if (isLockActive(venue, now)) {
            return Decision.deny(GuardSkipReason.LOCKED, venue.getStatusLockedUntil());
        }
        return Decision.allow();
    }

    /**
     * 人工锁当前是否生效（与是否豁免无关——豁免是另一条正交的例外）。
     * <p>
     * 单独暴露给管理端状态查询用：让「锁有没有生效」这件事只有一处判定实现，
     * 前端 / 后台不再各算一遍时间比较。
     */
    public boolean isLockActive(Venue venue, LocalDateTime now) {
        LocalDateTime lockedUntil = venue.getStatusLockedUntil();
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /**
     * 调用方是否声明为「程序化外部写库」（Agent/Skill）。
     * <p>
     * 未声明 / 其他值一律按人工通道处理——默认落在更保守的一侧（人工优先），
     * 前端编辑表单不传该字段，天然走人工通道。
     */
    public boolean isExternalChangeSource(String changeSource) {
        return CHANGE_SOURCE_AGENT_BATCH.equalsIgnoreCase(changeSource);
    }

    /**
     * 外部通道写入成功后的所有权接管：标记为自动来源并清除人工锁。
     * <p>
     * 必须清锁——否则「一次人工锁 + 一次合法的外部覆盖」会留下悬空的锁，
     * 让后续轮次继续被无谓跳过。
     */
    public void takeOverByExternalWrite(Venue venue) {
        venue.setStatusSource(VenueStatusSource.SYNC);
        venue.setStatusLockedUntil(null);
    }

    /**
     * 人工直改状态时的打锁（**仅在实际状态发生变化时调用**；由调用方判定并传入新状态）。
     * <p>
     * ⚠️ 必须由调用方先判定「状态确实变了」再调用：若对「无变化的保存」也打锁，管理员
     * 每打开一次编辑页点保存就会无限续锁，门店会被永久冻结在人工值上——这是本机制最需要
     * 堵住的洞。调用方（{@code VenueService}）的模式是「newStatus != currentStatus」才进锁分支。
     *
     * @param newStatus 人工设定到的目标状态（决定锁时长走 OPEN 档还是停业档）
     */
    public void lockOnManualChange(Venue venue, VenueStatus newStatus, LocalDateTime now) {
        venue.setStatusSource(VenueStatusSource.MANUAL);
        if (!isLockEnabled()) {
            venue.setStatusLockedUntil(null);
            return;
        }
        int days = newStatus == VenueStatus.OPEN ? humanOpenDays() : humanClosedDays();
        venue.setStatusLockedUntil(days <= 0 ? null : now.plusDays(days));
    }

    /**
     * 人工显式释放（管理端「恢复自动同步」按钮）：清锁，下一轮舞讯即可覆盖。
     * <p>
     * 不清 {@code statusSource}——「上一次是谁定的」是审计事实，不该被这个动作抹掉。
     */
    public void unlockByHuman(Venue venue) {
        venue.setStatusLockedUntil(null);
        log.info("venue {} 人工锁已释放（恢复自动同步）", venue.getId());
    }

    /**
     * 设置 / 撤销永久豁免（管理端）。
     *
     * @param note 人工说明；null / 空串 = 保留原备注（撤销豁免时通常无需重填理由）
     */
    public void setExempt(Venue venue, boolean exempt, String note) {
        venue.setDailySyncExempt(exempt);
        if (note != null && !note.isBlank()) {
            venue.setSyncNote(note.trim());
        }
        log.info("venue {} 舞讯推断豁免 = {}{}", venue.getId(), exempt,
                (note == null || note.isBlank()) ? "" : ("（" + note.trim() + "）"));
    }

    /** 记录人工备注（改状态原因等；由管理端入口按需调用）。 */
    public void noteSync(Venue venue, String note) {
        if (note != null && !note.isBlank()) {
            venue.setSyncNote(note.trim());
        }
    }

    public boolean isLockEnabled() {
        return opsConfigService.isEnabled(KEY_STATUS_LOCK_ENABLED, DEFAULT_ENABLED);
    }

    public int humanOpenDays() {
        return opsConfigService.getInt(KEY_STATUS_LOCK_HUMAN_OPEN_DAYS, DEFAULT_OPEN_DAYS);
    }

    public int humanClosedDays() {
        return opsConfigService.getInt(KEY_STATUS_LOCK_HUMAN_CLOSED_DAYS, DEFAULT_CLOSED_DAYS);
    }
}
