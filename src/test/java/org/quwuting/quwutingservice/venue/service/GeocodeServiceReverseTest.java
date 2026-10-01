package org.quwuting.quwutingservice.venue.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.exception.ExternalServiceUnavailableException;
import org.quwuting.quwutingservice.geo.tencent.TencentLbsClient;
import org.quwuting.quwutingservice.venue.change.VenueChangePublisher;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 逆地理编码的网格缓存与失败语义（2026-10-01，Mockito 单测）。
 * <p>
 * 回归目标：同一片区域（≈1km 网格）重复定位只外呼一次——这条接口曾把腾讯日配额打满；
 * 上游不可用时异常原样上抛（由全局处理器映射 503 + Retry-After），且不污染缓存。
 */
@ExtendWith(MockitoExtension.class)
class GeocodeServiceReverseTest {

    @Mock private VenueRepository venueRepository;
    @Mock private TencentLbsClient tencentLbsClient;
    @Mock private VenueChangePublisher venueChangePublisher;

    private GeocodeService service() {
        return new GeocodeService(venueRepository, tencentLbsClient, venueChangePublisher);
    }

    @Test
    void sameGridCellIsServedFromCache() {
        when(tencentLbsClient.reverseCity(anyDouble(), anyDouble())).thenReturn("南通市");
        GeocodeService service = service();

        assertEquals("南通市", service.reverseGeocode(32.0123, 120.8641));
        assertEquals("南通市", service.reverseGeocode(32.0149, 120.8649));

        verify(tencentLbsClient, times(1)).reverseCity(anyDouble(), anyDouble());
    }

    @Test
    void unavailableUpstreamPropagatesAndIsNotCached() {
        when(tencentLbsClient.reverseCity(anyDouble(), anyDouble()))
                .thenThrow(new ExternalServiceUnavailableException("腾讯位置服务", 3600, "x", "quota", null))
                .thenReturn("南通市");
        GeocodeService service = service();

        assertThrows(ExternalServiceUnavailableException.class, () -> service.reverseGeocode(32.01, 120.86));
        assertEquals("南通市", service.reverseGeocode(32.01, 120.86), "失败不得写入缓存");
    }

    @Test
    void outOfChinaCoordinateRejectedWithoutCallingUpstream() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service().reverseGeocode(1.0, 1.0));
        assertEquals(1001, ex.getCode());
        verify(tencentLbsClient, never()).reverseCity(anyDouble(), anyDouble());
    }
}
