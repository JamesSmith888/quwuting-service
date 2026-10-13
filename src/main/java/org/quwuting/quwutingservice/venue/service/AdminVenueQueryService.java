package org.quwuting.quwutingservice.venue.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.spend.enums.WireEnums;
import org.quwuting.quwutingservice.venue.dto.response.AdminVenueListItem;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.AdminVenueSort;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuepresence.service.NearbyFootprint;
import org.quwuting.quwutingservice.venuepresence.service.VenueVisitBadgeService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理端门店列表查询服务（2026-09-29，V33 到访域配套；2026-10-03 足迹排序 / 筛选；文档 =
 * docs/agents/52-venue-presence.md §6.1）。
 * <p>
 * 职责 = 「公开列表查询做不了的事」：① 无业务裁剪的全量分页（含停业/暂停，
 * 见 {@code VenueRepository#findAdminPage} 注释）；② 注入足迹摘要列
 * （{@code VenueVisitBadgeService#footprintsForAdmin} 批量口径，一次 IN 防 N+1）；③ 按足迹派生量排序 / 筛选。
 * 依赖方向 venue → venuepresence 单向，无构造器循环。
 * <p>
 * <b>足迹数字 = 小程序列表卡片上的那组数字</b>（2026-10-13 用户要求「admin 列表与小程序列表保持一致」）：
 * 经 {@link VenueVisitBadgeService}（展示口径唯一出口）取数，⛔ 本类不得直连 {@code VenuePresenceService}
 * 另算一套——列表行、详情卡、名单、小程序文案的一致性靠「只有一个取数点」保证，而不是靠各处各自算对。
 * <p>
 * <b>两条分页路径，同一组筛选谓词</b>（{@code VenueRepository#ADMIN_LIST_FILTERS}）：
 * <ul>
 *   <li>默认序且不筛足迹 → SQL 分页（{@code findAdminPage}），与门店规模无关；</li>
 *   <li>按足迹排序 / 只看有足迹 → 足迹人数是派生量（附近带 × 邻域并集），进不了
 *       ORDER BY ⇒ 取筛选后的全部 id（千级 Long）+ 有足迹门店的稀疏结果，内存稳定排序后切页
 *       （{@link #orderByFootprint}）。量级边界：门店到万级 / ping 到百万级时改为物化汇总表（52 号 §6.1）。</li>
 * </ul>
 * 两条路径的行数据都经 {@code footprintsForAdmin} 注入，数字与详情页同源。
 */
@Service
@RequiredArgsConstructor
public class AdminVenueQueryService {

    /** 管理列表单页上限（防深翻页拖库；admin-web 端 PAGE_SIZE=20，上限 100 兜住误传） */
    private static final int PAGE_SIZE_LIMIT = 100;

    private final VenueRepository venueRepository;
    private final VenueVisitBadgeService venueVisitBadgeService;

    /**
     * 管理端门店列表（city / status / keyword 筛选 + 足迹排序 / 筛选 + 分页）。
     * status / sort 经 {@link WireEnums#parse} 宽容解析（非法 = 视为未筛选 / 默认序而非 500，
     * 与「禁猜默认值」的适用面不同：这里是查询参数，非法值降级为「不筛」；写入路径仍禁止宽容）。
     *
     * @param visitedOnly true = 只看近 30 天附近有足迹的门店
     */
    @Transactional(readOnly = true)
    public Page<AdminVenueListItem> list(String city, String statusRaw, String keyword,
                                         String sortRaw, boolean visitedOnly, int page, int size) {
        VenueStatus status = statusRaw == null || statusRaw.isBlank()
                ? null
                : WireEnums.parse(VenueStatus.class, statusRaw.trim());
        AdminVenueSort parsedSort = WireEnums.parse(AdminVenueSort.class, sortRaw);
        AdminVenueSort sort = parsedSort == null ? AdminVenueSort.LATEST : parsedSort;
        String cityFilter = city == null || city.isBlank() ? null : city.trim();
        String keywordFilter = normalizeKeyword(keyword);

        int pageSize = Math.min(Math.max(size, 1), PAGE_SIZE_LIMIT);
        Pageable pageable = PageRequest.of(Math.max(page, 0), pageSize);
        Page<Venue> venuePage = sort == AdminVenueSort.LATEST && !visitedOnly
                ? venueRepository.findAdminPage(cityFilter, status, keywordFilter, pageable)
                : rankByFootprint(cityFilter, status, keywordFilter, sort, visitedOnly, pageable);

        Map<Long, NearbyFootprint> footprints = venueVisitBadgeService
                .footprintsForAdmin(venuePage.map(Venue::getId).getContent());

        return venuePage.map(venue -> toItem(venue,
                footprints.getOrDefault(venue.getId(), NearbyFootprint.EMPTY)));
    }

    /** 足迹排序路径：全量 id（默认序）→ 派生量稳定排序 / 筛选 → 切页 → 按页 id 取实体并保序 */
    private Page<Venue> rankByFootprint(String city, VenueStatus status, String keyword,
                                        AdminVenueSort sort, boolean visitedOnly, Pageable pageable) {
        List<Long> ordered = orderByFootprint(
                venueRepository.findAdminIds(city, status, keyword),
                venueVisitBadgeService.visitedFootprintsForAdmin(), sort, visitedOnly);
        int from = (int) Math.min(pageable.getOffset(), ordered.size());
        int to = Math.min(from + pageable.getPageSize(), ordered.size());
        List<Long> pageIds = ordered.subList(from, to);
        Map<Long, Venue> byId = venueRepository.findAllById(pageIds).stream()
                .collect(Collectors.toMap(Venue::getId, Function.identity()));
        List<Venue> content = pageIds.stream().map(byId::get).filter(Objects::nonNull).toList();
        return new PageImpl<>(content, pageable, ordered.size());
    }

    /**
     * 足迹排序 / 筛选（纯函数）：入参 id 已是默认序（id 倒序），{@link List#sort} 是稳定排序 ⇒
     * 排序键全并列的门店（含从无到访）天然保持默认序，同一筛选下翻页结果确定。
     *
     * @param visited 有足迹门店的稀疏结果（缺席 = 附近从无足迹）
     */
    static List<Long> orderByFootprint(List<Long> idsInDefaultOrder, Map<Long, NearbyFootprint> visited,
                                       AdminVenueSort sort, boolean visitedOnly) {
        Function<Long, NearbyFootprint> summaryOf = id -> visited.getOrDefault(id, NearbyFootprint.EMPTY);
        List<Long> ids = new ArrayList<>(idsInDefaultOrder);
        if (visitedOnly) {
            ids.removeIf(id -> summaryOf.apply(id).visitUsers30d() <= 0);
        }
        Comparator<Long> byLastVisitDesc = Comparator.comparing(
                id -> summaryOf.apply(id).lastVisitAt(),
                Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder()));
        switch (sort) {
            case VISITS_30D -> ids.sort(Comparator
                    .comparingLong((Long id) -> summaryOf.apply(id).visitUsers30d()).reversed()
                    .thenComparing(byLastVisitDesc));
            case LAST_VISIT -> ids.sort(byLastVisitDesc);
            case LATEST -> { /* 已是默认序 */ }
        }
        return ids;
    }

    private AdminVenueListItem toItem(Venue venue, NearbyFootprint visit) {
        boolean hasCoordinate = venue.getLatitude() != null && venue.getLongitude() != null;
        return new AdminVenueListItem(
                venue.getId(),
                venue.getName(),
                venue.getCity(),
                venue.getDistrict(),
                venue.getAddress(),
                venue.getStatus(),
                venue.getStatus().getDisplayName(),
                venue.getVenueType(),
                venue.getVenueType().getDisplayName(),
                hasCoordinate,
                venue.getExpectedOpenDate(),
                visit.visitUsers7d(),
                visit.visitUsers30d(),
                visit.visitEvents30d(),
                visit.lastVisitAt(),
                venueVisitBadgeService.badgeTextOf(visit),
                visit.nearbyVenueCount());
    }

    /**
     * 关键词规整：trim → 空串归 null（= 不筛）→ 小写化（JPQL 侧 LOWER(name)
     * 匹配）→ LIKE 通配符转义（% _ \，06 号 §LIKE 字面转义 ESCAPE 同款约定；
     * 转义只在 Service 单点做，Repository 查询不重复处理）。
     */
    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim().toLowerCase();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
