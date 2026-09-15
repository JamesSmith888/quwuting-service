package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.announcement.entity.Announcement;
import org.quwuting.quwutingservice.announcement.repository.AnnouncementRepository;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.recruitment.entity.Recruitment;
import org.quwuting.quwutingservice.recruitment.repository.RecruitmentRepository;
import org.quwuting.quwutingservice.user.repository.UserBehaviorEvent;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行为事件的<b>关联对象名</b>与<b>明细文案</b>解析器（2026-09-15，仅 ADMIN 消费；
 * docs/agents/35-dashboard-stats.md）。
 *
 * <h2>为什么单独成组件（根因）</h2>
 * 「把 id 批量换成人能读的名字」在管理端被反复实现过：{@code AdminUserStatsDetailService}
 * 各写一份 venue/dancer 批量查询与「渠道 / 上报类型」中文映射，行为轨迹又要一份。
 * 同一个字典（如分享渠道 BUTTON→按钮分享）在两处各写一份 ⇒ 改动只落到一处时，
 * 两个页面会对同一份数据给出不同文案——这类漂移无法被编译器或测试发现，
 * 只能靠「只有一个实现」来根除。故本类 = 行为/明细展示文案的<b>唯一实现处</b>，
 * 由 {@code AdminUserStatsDetailService}（旧消费方）与
 * {@code AdminUserBehaviorService}（轨迹）共用。
 *
 * <h2>解析口径</h2>
 * <ul>
 *   <li>{@link #names}：按 {@link UserBehaviorEvent.RefKind} 分组批量取回（一次 IN 查询/类，
 *       <b>禁 N+1</b>）；取不到（已软删/已删）时返回 {@code 兜底文案} 由调用方决定，
 *       本方法只给「有名字的那些」；</li>
 *   <li>{@link #dictionary}：明细列的渲染字典，<b>字典键由行为事件目录声明</b>
 *       （{@link UserBehaviorEvent.DetailDict}），语义映射镜像各领域既有枚举
 *       （{@code FeedbackType#getDisplayName} / {@code ReportType#getDisplayName}），
 *       本类不重复定义业务语义，只负责「哪一列按哪本字典读」；</li>
 *   <li>非法/未知原始值一律<b>回退到中性文案</b>而非抛错或显示英文码——展示层不能因为
 *       一条历史脏数据整页报错（同 {@code WireEnums} 「禁猜默认值」的对偶：展示可以兜底，
 *       写入不可以）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class BehaviorRefNameResolver {

    /** 门店名兜底（名称缺失时） */
    public static final String VENUE_FALLBACK = "门店";
    /** 舞伴名兜底 */
    public static final String DANCER_FALLBACK = "舞伴";
    /** 招工所属门店名兜底 */
    public static final String RECRUITMENT_FALLBACK = "招工信息";
    /** 公告标题兜底 */
    public static final String ANNOUNCEMENT_FALLBACK = "公告";

    private final VenueRepository venueRepository;
    private final DancerRepository dancerRepository;
    private final RecruitmentRepository recruitmentRepository;
    private final AnnouncementRepository announcementRepository;

    /**
     * 批量解析关联对象名：{@code id → 展示名}。
     * <p>
     * 传空集合或 {@link UserBehaviorEvent.RefKind#NONE} 直接返回空 Map（不产生查询）；
     * 查不到的对象不出现在结果里（调用方用兜底文案），<b>不抛错</b>——对象被软删是正常业务事实。
     *
     * @param kind 关联对象类型（来自行为事件目录）
     * @param ids  关联对象 id 集合（可含重复/null 之外的值；null 元素被过滤）
     */
    @Transactional(readOnly = true)
    public Map<Long, String> names(UserBehaviorEvent.RefKind kind, Collection<Long> ids) {
        List<Long> clean = ids == null ? List.of()
                : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (clean.isEmpty() || kind == null || kind == UserBehaviorEvent.RefKind.NONE) {
            return Map.of();
        }
        return switch (kind) {
            case VENUE -> venueNames(clean);
            case DANCER -> dancerNames(clean);
            case ANNOUNCEMENT -> announcementTitles(clean);
            case RECRUITMENT -> recruitmentVenueNames(clean);
            case NONE -> Map.of();
        };
    }

    /**
     * 明细列渲染（字典键由行为事件目录声明）。{@code raw} 为空 → 空串（不渲染明细行）。
     */
    public String dictionary(UserBehaviorEvent.DetailDict dict, String raw) {
        if (dict == null || raw == null || raw.isBlank()) {
            return "";
        }
        return switch (dict) {
            case RAW_TEXT -> raw;
            case SHARE_CHANNEL -> shareChannel(raw);
            case VENUE_FEEDBACK_TYPE -> enumDisplay(FeedbackType.class, raw, "其他问题");
            case STATUS_REPORT_REASON -> enumDisplay(ReportType.class, raw, "暂停营业");
        };
    }

    // ── 各对象类型的批量取回 ────────────────────────────────────────────────────

    private Map<Long, String> venueNames(List<Long> ids) {
        return venueRepository.findByIdInAndDeletedFalse(ids).stream()
                .collect(java.util.stream.Collectors.toMap(
                        Venue::getId, Venue::getName, (a, b) -> a, LinkedHashMap::new));
    }

    private Map<Long, String> dancerNames(List<Long> ids) {
        return dancerRepository.findByIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(
                        Dancer::getId, Dancer::getNickname, (a, b) -> a, LinkedHashMap::new));
    }

    private Map<Long, String> announcementTitles(List<Long> ids) {
        Map<Long, String> out = new LinkedHashMap<>();
        announcementRepository.findAllById(ids).stream()
                .filter(a -> !a.isDeleted() && a.getTitle() != null)
                .forEach(a -> out.put(a.getId(), a.getTitle()));
        return out;
    }

    /**
     * 招工联系：{@code qwt_recruitment_contacts} 只落 {@code recruitment_id}，
     * 而「招工」本身没有标题字段 ⇒ 展示其<b>所属门店名</b>（两级：招工 → 门店），
     * 这是该对象唯一对运营可读的标识。两跳都在本方法内批量完成（无 N+1）。
     */
    private Map<Long, String> recruitmentVenueNames(List<Long> recruitmentIds) {
        List<Recruitment> recruitments = recruitmentRepository.findAllById(recruitmentIds).stream()
                .filter(r -> !r.isDeleted())
                .toList();
        if (recruitments.isEmpty()) {
            return Map.of();
        }
        List<Long> venueIds = new ArrayList<>();
        recruitments.forEach(r -> {
            if (r.getVenueId() != null) {
                venueIds.add(r.getVenueId());
            }
        });
        Map<Long, String> venues = venueNames(venueIds);
        Map<Long, String> out = new LinkedHashMap<>();
        recruitments.forEach(r -> out.put(r.getId(),
                r.getVenueId() == null
                        ? RECRUITMENT_FALLBACK
                        : venues.getOrDefault(r.getVenueId(), RECRUITMENT_FALLBACK)));
        return out;
    }

    // ── 字典 ──────────────────────────────────────────────────────────────────

    /** 分享渠道（channel：BUTTON/MENU/TIMELINE；空 = 未知渠道不渲染，与既有实现逐字一致） */
    private static String shareChannel(String channel) {
        return switch (channel) {
            case "BUTTON" -> "按钮分享";
            case "MENU" -> "菜单分享";
            case "TIMELINE" -> "朋友圈分享";
            default -> "分享";
        };
    }

    /** 枚举原始码 → 展示名（非法/未知码回退中性文案，不抛错、不显示英文码） */
    private static <E extends Enum<E>> String enumDisplay(Class<E> type, String raw, String fallback) {
        try {
            E value = Enum.valueOf(type, raw);
            if (value instanceof FeedbackType f) {
                return f.getDisplayName();
            }
            if (value instanceof ReportType r) {
                return r.getDisplayName();
            }
            return fallback;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
