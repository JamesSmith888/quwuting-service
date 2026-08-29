package org.quwuting.quwutingservice.favorite.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.favorite.repository.FavoriteRepository;
import org.quwuting.quwutingservice.venue.dto.response.VenueResponse;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.mapper.VenueResponseMapper;
import org.quwuting.quwutingservice.venue.repository.VenueViewRepository;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.quwuting.quwutingservice.venue.service.VenueService;
import org.quwuting.quwutingservice.venuereaction.ReactionWindow;
import org.quwuting.quwutingservice.venuereaction.service.VenueReactionService;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FavoriteService 收藏列表编排语义单元测试（Mockito，不依赖数据库）。
 * <p>
 * 覆盖 2026-08-08「列表页-热门标签优化」根因修复的核心契约
 * （见 FavoriteService#getFavoriteVenues javadoc 与后端 AGENTS.md「热门场所标记」）：
 * <ol>
 *   <li><b>收藏列表必须下发 isHot</b>——场景复现：热门舞厅在"全部城市"列表正常展示
 *       热门标签，但在用户收藏列表却不展示。根因：本服务误用
 *       {@link VenueResponseMapper} 双参重载（默认 isHot=false），从未查询热门 ID
 *       集合。修复后必须经 {@link VenueLookupService#getHotVenueIds()} 取热门集合、
 *       走三参/四参重载传入真实 isHot（本测试断言四参调用 + 布尔值正确，防回退双参）；</li>
 *   <li>热门判定与城市列表同口径（同一 getHotVenueIds 缓存源）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class FavoriteServiceTest {

    @Mock
    private FavoriteRepository favoriteRepository;
    @Mock
    private VenueResponseMapper venueResponseMapper;
    @Mock
    private VenueReactionService venueReactionService;
    @Mock
    private VenueLookupService venueLookupService;
    @Mock
    private VenueHeatService venueHeatService;
    @Mock
    private VenueViewRepository venueViewRepository;
    /** 门店公开照片批量加载（2026-08-20 门店照片域新增依赖） */
    @Mock
    private VenueService venueService;

    private FavoriteService service;

    @BeforeEach
    void setUp() {
        service = new FavoriteService(favoriteRepository, venueResponseMapper,
                venueReactionService, venueLookupService, venueHeatService,
                venueViewRepository, venueService);
    }

    private static Venue venue(Long id) {
        Venue v = new Venue();
        v.setId(id);
        v.setStatus(VenueStatus.OPEN);
        return v;
    }

    /** 按映射器入参 isHot 回显构造响应（isHot 四参重载契约的观测点） */
    private static VenueResponse response(Long id, boolean isHot) {
        return new VenueResponse(
                id, "舞厅" + id, VenueStatus.OPEN, "营业中", null,
                Collections.emptyList(), null, "绍兴市", null, null,
                null, null, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), null, null, Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), 0,
                0L, isHot, null, null, null);
    }

    /**
     * 根因场景：收藏列表含两个场所，其中 venue 1 在热门 ID 集合内、venue 2 不在。
     * 断言映射器以三参重载被调用且 isHot 分别为 true/false——修复前本方法调双参
     * 重载（恒 false），热门舞厅在收藏列表恒不展示热门标签。
     */
    @Test
    void getFavoriteVenues_propagatesHotFlagFromHotVenueIds() {
        Venue hot = venue(1L);
        Venue cold = venue(2L);
        when(favoriteRepository.findFavoriteVenuesByUserId(42L)).thenReturn(List.of(hot, cold));
        when(venueReactionService.batchGetBadges(anyList(), eq(42L), eq(ReactionWindow.DAYS_7)))
                .thenReturn(Collections.emptyMap());
        when(venueLookupService.getHotVenueIds()).thenReturn(Set.of(1L));
        // 门店照片域（2026-08-20）：无公开照片时返回空 Map（调用方 getOrDefault 兜底）
        when(venueService.loadPublicPhotosByVenueIds(anyList())).thenReturn(Collections.emptyMap());
        when(venueResponseMapper.toResponse(any(Venue.class), anyList(), anyBoolean(), anyLong(), anyList()))
                .thenAnswer(inv -> response(
                        ((Venue) inv.getArgument(0)).getId(), inv.getArgument(2)));

        List<VenueResponse> result = service.getFavoriteVenues(42L);

        assertEquals(2, result.size());
        assertTrue(result.get(0).isHot(), "热门集合内的场所收藏列表必须展示热门标记");
        assertFalse(result.get(1).isHot(), "非热门集合内的场所不得误标热门");
        // 防回归：必须走五参重载（双参重载 isHot 恒 false 是本缺陷根因；五参携带照片列表）
        verify(venueResponseMapper).toResponse(hot, Collections.emptyList(), true, 0L, Collections.emptyList());
        verify(venueResponseMapper).toResponse(cold, Collections.emptyList(), false, 0L, Collections.emptyList());
    }

    /** 收藏列表为空时短路返回，不触发热门集合查询（无意义往返） */
    @Test
    void getFavoriteVenues_returnsEmptyWithoutHotQueryWhenNoFavorites() {
        when(favoriteRepository.findFavoriteVenuesByUserId(42L)).thenReturn(Collections.emptyList());

        List<VenueResponse> result = service.getFavoriteVenues(42L);

        assertTrue(result.isEmpty());
        verify(venueLookupService, org.mockito.Mockito.never()).getHotVenueIds();
    }
}
