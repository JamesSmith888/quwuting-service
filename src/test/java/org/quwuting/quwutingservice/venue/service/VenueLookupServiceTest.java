package org.quwuting.quwutingservice.venue.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.venue.config.VenueHotProperties;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuereaction.ReactionCode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VenueLookupService 热门场所编排语义单元测试（Mockito，不依赖数据库）。
 * <p>
 * 覆盖 2026-08-08「列表页-热门标签优化」根因修复的接线契约：
 * <ul>
 *   <li>「热门」绝对门槛（{@code venue.hot.min-heat-score}，配置唯一事实源 =
 *       {@link VenueHotProperties}）必须经本服务注入 {@link VenueRepository#findHotVenueIds}
 *       SQL 参数——修复"热度指数仅 2（近30天 2 次浏览）也有热门标签"的伪热门缺陷，
 *       门槛不得在 SQL/调用方硬编码（本测试断言参数流转，锁死接线）；</li>
 *   <li>正向 Reaction code 列表（唯一事实源 = ReactionCode）继续传入 SQL。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class VenueLookupServiceTest {

    @Mock
    private VenueRepository venueRepository;

    private VenueLookupService service;

    @BeforeEach
    void setUp() {
        // 显式构造配置实例（门槛 70 = 当前默认，2026-08-08 用户反馈后上调；
        // 积分权重 2 = V2 初始保守值，校准机制对象）：
        // 验证配置路径下接线正确，与 DEFAULT 回退语义无关
        service = new VenueLookupService(venueRepository, new VenueHotProperties(70),
                new org.quwuting.quwutingservice.config.PointsProperties(2, 5, 5, 2,
                        new org.quwuting.quwutingservice.config.PointsProperties.GiftLimits(10, 20, 5),
                        new org.quwuting.quwutingservice.config.PointsProperties.GateLimits(50), 3));
    }

    @Test
    void getHotVenueIds_passesConfiguredMinHeatScoreToRepository() {
        when(venueRepository.findHotVenueIds(anyList(), eq(2), eq(70))).thenReturn(List.of(1L, 2L, 3L));

        var result = service.getHotVenueIds();

        assertEquals(3, result.size());
        assertTrue(result.contains(1L));
        // 门槛必须来自配置（本测试构造的 70），且正向 code 列表仍为唯一事实源；
        // 积分权重（2）也必须来自配置传入 SQL（V2 校准机制：改配置即生效）
        verify(venueRepository).findHotVenueIds(eq(ReactionCode.positiveCodeNames()), eq(2), eq(70));
    }
}
