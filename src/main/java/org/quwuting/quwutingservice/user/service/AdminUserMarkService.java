package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.payqr.service.PayQrStatusService;
import org.quwuting.quwutingservice.user.dto.response.AdminUserMarks;
import org.quwuting.quwutingservice.venuepresence.enums.PresenceConsentState;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户信息「功能状态标记」装配（2026-10-13；载体 = {@link AdminUserMarks}）。
 * <p>
 * 一页用户的两类标记各一次批量查询（到店足迹授权 / 收款码张数），列表与详情共用，防 N+1。
 * 判据全部委托各业务域的单点（{@code consentStateOf} / {@code countsFor}），本类<b>不重写任何判断</b>——
 * 门禁 / 分布统计 / 单用户展示 / 用户标记同数是纪律，自行判断就会出现「列表标着已关闭、门禁却在收」。
 */
@Service
@RequiredArgsConstructor
public class AdminUserMarkService {

    private final VenuePresenceService venuePresenceService;
    private final PayQrStatusService payQrStatusService;

    /** 返回覆盖<b>全部</b>入参的映射（缺席事实按"从未询问 / 0 张"补齐，前端无需判空） */
    @Transactional(readOnly = true)
    public Map<Long, AdminUserMarks> marksFor(Collection<Long> userIds) {
        Map<Long, AdminUserMarks> result = new LinkedHashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }
        Map<Long, PresenceConsentState> footprint = venuePresenceService.consentStatesFor(userIds);
        Map<Long, PayQrStatusService.Snapshot> payQr = payQrStatusService.snapshotsFor(userIds);
        for (Long id : userIds) {
            PresenceConsentState f = footprint.getOrDefault(id, PresenceConsentState.NEVER_ASKED);
            PayQrStatusService.Snapshot q = payQr.getOrDefault(id, PayQrStatusService.Snapshot.NO_REPORT);
            result.put(id, new AdminUserMarks(f.name(), f.getDisplayName(),
                    q.count(), q.state().name(), q.state().display(q.count())));
        }
        return result;
    }

    public AdminUserMarks marksOf(Long userId) {
        return marksFor(java.util.List.of(userId)).get(userId);
    }
}
