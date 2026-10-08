package org.quwuting.quwutingservice.announcement.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.announcement.entity.Announcement;
import org.quwuting.quwutingservice.announcement.repository.AnnouncementRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 首页公告位（home slot）记账 —— <b>占位语义的唯一写入点</b>（2026-10-08，docs/agents/34）。
 *
 * <h3>为什么需要它（这是根因，不是新需求）</h3>
 * 现象：一天内多条公告要顶置，首页只显示一条，运营"置顶了却什么都没发生"。
 * <p>
 * 根因是<b>类型错配</b>：{@code pinned} 是布尔列，而首页公告位是<b>容量为 1 的稀缺资源</b>。
 * 布尔表达不了"唯一占用"，于是允许 N 条同时为真；而全系统<b>没有任何一处声明过"容量=1"</b>
 * ——唯一的约束落在消费端 {@code listAnnouncements(0, 1, true)} 的 {@code size=1} 上，
 * 也就是<b>在读端做容量限制</b>。任何绕过首页消费的写路径（管理端 / Agent 接口）都能写入
 * 多条置顶，读端只能静默取第一条⇒ 置顶动作<b>系统性不兑现</b>（2026-10-08 生产实测：
 * 36 条可见公告同时 {@code pinned=1} 且永不下线，首页位被每日舞讯流水长期占据）。
 * <p>
 * 修复落点不是"把 size 改大"，而是<b>把稀缺资源的不变量搬到写入端</b>，三层设防：
 * <ol>
 *   <li><b>领域层（本类）</b>：占位前记账校验，冲突显式拒绝；</li>
 *   <li><b>数据层（V44 生成列 + UNIQUE INDEX）</b>：
 *       {@code IF(deleted = 0 AND pinned = 1, 'HOME_SLOT', NULL)} —— 即使有人绕过应用层
 *       直接改库，也不可能出现两条占位；</li>
 *   <li><b>自愈（{@link #reconcile()}）</b>：定时兜底收敛，任何来源的脏数据 30s 内自愈。</li>
 * </ol>
 *
 * <h3>为什么冲突"显式拒绝"而不是"自动顶掉旧的"</h3>
 * 自动抢占会让运营在<b>不知情</b>的情况下把别人的公告挤下首页位且无任何提示——那正是原痛点的
 * 另一种形态（动作不透明）。显式拒绝并把当前占位者《标题》写进报错，让"位被占"变成运营可见的
 * 事实：要么换一条置顶，要么先取消对方。
 *
 * <h3>不变量（唯一声明处）</h3>
 * <ul>
 *   <li><b>任意时刻至多一条</b> {@code pinned=true}（未软删）；本类 + V44 唯一索引共同保证；</li>
 *   <li>下线（{@code OFFLINE}）/软删（{@code deleted=1}）<b>必须释放位</b> ——否则过期公告
 *       永久霸占首页位（每日舞讯的堆积机制正是"永不下线 + 置顶"）；</li>
 *   <li>占位与可见性<b>解耦</b>：草稿也能占位（"预定"语义）。这是刻意取舍——让"位被占"
 *       在<b>发布之前</b>就暴露，而不是定时任务到点强转 PUBLISHED 时才在唯一索引上炸。
 *       副作用是<b>定时批量转态不触碰 pinned</b>，因此永不与唯一索引冲突；</li>
 *   <li>占位是<b>运营显式决策</b>（沿用 2026-09-05 决策"首页位是强触达位，由运营显式决定"），
 *       本类不按分类自动置顶——自动置顶会让"群消息类"等低质量内容抢占强触达位。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeSlotService {

    private final AnnouncementRepository announcementRepository;

    /**
     * 占位可用性校验（<b>写前记账</b>）：本条要置顶时，位上不能已有别人。
     *
     * @param id 本条 id（编辑场景传自身 id = 幂等，允许自己已占位）
     * @throws BusinessException 位已被他人占用，报错带上占位者标题
     */
    @Transactional(readOnly = true)
    public void assertClaimable(Long id) {
        findHolder().ifPresent(holder -> {
            if (!holder.getId().equals(id)) {
                throw new BusinessException(1001, String.format(
                        "首页公告位当前由《%s》占用，请先取消它的置顶，或改用未读提醒触达",
                        holder.getTitle()));
            }
        });
    }

    /**
     * 占位（<b>唯一写入点</b>）：校验 → 释放全部占位 → 置本条为 pinned。
     * <p>
     * <b>为什么逐条改而不是 bulk UPDATE</b>：{@code @Modifying} 的 JPQL 会绕过持久化上下文，
     * 被它清成 {@code pinned=false} 的行在 PC 里仍是 {@code true}，后续 {@code save} 因"字段
     * 无变化"而不发 UPDATE，数据库与实体状态就此分叉。这类"bulk 绕过 PC"的事故在本仓已发生过
     * （见 {@code venueStatusReport} 相关注释）。占位涉及的条目数正常≤1、历史脏数据≤36，
     * 逐条改的性能成本可忽略，换来的是"DB 与 PC 恒等"这个更值钱的性质。
     *
     * @param target 待占位的公告（须为managed 实体，方法内落库并回写）
     */
    @Transactional
    public void claim(Announcement target) {
        assertClaimable(target.getId());
        releaseAll();
        target.setPinned(true);
        announcementRepository.saveAndFlush(target);
        log.info("[home-slot] claimed by id={} title={}", target.getId(), target.getTitle());
    }

    /**
     * 释放位（下线 / 软删时调用，见 {@code AnnouncementService#offline} / {@code #delete}）。
     * <b>必须调用</b>：否则过期公告永久霸占首页位。
     *
     * @return 是否真的释放了（false = 本条本就未占位）
     */
    @Transactional
    public boolean release(Long id) {
        Announcement a = announcementRepository.findByIdAndDeletedFalse(id).orElse(null);
        if (a == null || !a.isPinned()) {
            return false;
        }
        a.setPinned(false);
        announcementRepository.saveAndFlush(a);
        log.info("[home-slot] released by id={} title={}", a.getId(), a.getTitle());
        return true;
    }

    /**
     * 自愈收敛（<b>兜底</b>）：收敛至至多一条占位，多余的按 id 倒序释放。
     * <p>
     * <b>为什么还需要它</b>：写入侧有校验、数据侧有唯一索引，但历史上已存在多条置顶
     * （本轮生产实测 36 条），且任何手工改库都可能绕过约束。挂在 30s 定时任务里 =
     * "脏数据最长 30s 内自愈"，不必人工介入。
     * <p>
     * 胜出判据 = <b>id 最大</b>（最新置顶的那条），与首页读端排序
     * {@code (pinned DESC, publish_at DESC, id DESC)} 同源⇒ 自愈结果与用户实际看到的那条
     * 恒一致，不会出现"保住了另一条、首页显示的还是它"的对不上。
     *
     * @return 释放的条数（0 = 已是合法单占位）
     */
    @Transactional
    public int reconcile() {
        List<Announcement> holders = announcementRepository.findAllPinned();
        if (holders.size() <= 1) {
            return 0;
        }
        int released = 0;
        for (Announcement holder : holders) {
            if (holder.isPinned()) {
                holder.setPinned(false);
                released++;
            }
        }
        announcementRepository.saveAllAndFlush(holders);
        log.warn("[home-slot] reconciled {} pinned announcements, released={} (kept newest)",
                holders.size(), released);
        return released;
    }

    /** 释放全部占位（占位前的清场 + {@link #reconcile()} 内部复用） */
    private void releaseAll() {
        for (Announcement holder : announcementRepository.findAllPinned()) {
            holder.setPinned(false);
        }
    }

    /** 当前占位者（未软删置顶中 id 最大者 = 首页实际展示的那条） */
    @Transactional(readOnly = true)
    public Optional<Announcement> findHolder() {
        return announcementRepository.findTopByPinnedTrueAndDeletedFalseOrderByIdDesc();
    }
}