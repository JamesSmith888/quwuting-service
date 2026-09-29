package org.quwuting.quwutingservice.venue.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.spend.enums.WireEnums;
import org.quwuting.quwutingservice.venue.dto.response.AdminVenueListItem;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 管理端门店列表查询服务（2026-09-29，V33 到访域配套；文档 =
 * docs/agents/52-venue-presence.md §「admin 门店模块」）。
 * <p>
 * 职责 = 「公开列表查询做不了的两件事」：① 无业务裁剪的全量分页（含停业/暂停，
 * 见 {@code VenueRepository#findAdminPage} 注释）；② 注入到访统计列
 * （{@code VenuePresenceService} 批量口径，一次 IN 防 N+1）。
 * 依赖方向 venue → venuepresence 单向，无构造器循环。
 */
@Service
@RequiredArgsConstructor
public class AdminVenueQueryService {

    /** 管理列表单页上限（防深翻页拖库；admin-web 端 PAGE_SIZE=20，上限 100 兜住误传） */
    private static final int PAGE_SIZE_LIMIT = 100;

    private final VenueRepository venueRepository;
    private final VenuePresenceService venuePresenceService;

    /**
     * 管理端门店列表（city / status / keyword 三筛选 + 分页，id 倒序）。
     * status 经 {@link WireEnums#parse} 宽容解析（非法 = 视为未筛选而非 500，
     * 与「禁猜默认值」的适用面不同：这里是查询过滤参数，非法值降级为「不筛」
     * 并由调用方日志留痕；写入路径仍禁止宽容）。
     */
    @Transactional(readOnly = true)
    public Page<AdminVenueListItem> list(String city, String statusRaw,
                                         String keyword, int page, int size) {
        VenueStatus status = statusRaw == null || statusRaw.isBlank()
                ? null
                : WireEnums.parse(VenueStatus.class, statusRaw.trim());
        String cityFilter = city == null || city.isBlank() ? null : city.trim();
        String keywordFilter = normalizeKeyword(keyword);

        int pageSize = Math.min(Math.max(size, 1), PAGE_SIZE_LIMIT);
        Pageable pageable = PageRequest.of(Math.max(page, 0), pageSize);
        Page<Venue> venuePage = venueRepository.findAdminPage(
                cityFilter, status, keywordFilter, pageable);

        Map<Long, Long> visitUsers30d = venuePresenceService
                .visitUsers30dByVenueIds(venuePage.map(Venue::getId).getContent());

        return venuePage.map(venue -> toItem(venue,
                visitUsers30d.getOrDefault(venue.getId(), 0L)));
    }

    private AdminVenueListItem toItem(Venue venue, long visitUsers30d) {
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
                visitUsers30d);
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
