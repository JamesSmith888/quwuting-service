package org.quwuting.quwutingservice.payqr.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.payqr.dto.PayQrDtos;
import org.quwuting.quwutingservice.payqr.repository.UserPayQrStatusRepository;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.user.service.UserCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 计时收款码配置状态服务（2026-10-13，V49）。
 * <p>
 * <b>它知道什么、不知道什么</b>：只知道每个用户「本机放了几张收款码」（整数）。收款码本体仍只在用户手机本机
 * （quwuting 仓 60 号决策 1 的红线不变）；本服务的存在理由是运营要统计并识别「配置过收款码的用户」。
 * 请求体里<b>没有</b>任何能携带图片 / 路径 / 摘要的字段（{@code ReportRequest} 只有 count），协议层面不可能多收。
 */
@Service
@RequiredArgsConstructor
public class PayQrStatusService {

    /**
     * 单人张数上限（协议常量；客户端 {@code PAY_QR_MAX_COUNT} 镜像，改值两端同改，60 号文档）。
     * 越界按 1022 拒绝而非钳制——端侧上限与此值漂移是 bug，应暴露而不是被静默吞成"合法的 6"。
     */
    public static final int MAX_COUNT = 6;

    /** 名单上限（运营核查面，不做深翻页）；超限置 truncated 明说 */
    static final int ADMIN_USERS_LIMIT = 200;

    private final UserPayQrStatusRepository repository;
    private final UserRepository userRepository;

    /**
     * 上报当前张数（幂等整体覆盖，重复上报安全）。调用方必须已 {@code UserContext.requireAuth()}。
     * 首次 {@code count>0} 的时刻由服务端钟记录并之后保持（见 Repository#upsert）。
     */
    @Transactional
    public PayQrDtos.ReportResponse report(Long userId, Integer count) {
        if (count == null || count < 0 || count > MAX_COUNT) {
            throw new BusinessException(1022, "收款码张数越界");
        }
        LocalDateTime now = LocalDateTime.now();
        repository.upsert(userId, count, count > 0 ? now : null, now);
        return new PayQrDtos.ReportResponse(true, null);
    }

    /** 批量当前张数（用户信息标记用）：缺席 = 从未上报（等价 0） */
    @Transactional(readOnly = true)
    public Map<Long, Integer> countsFor(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> result = new HashMap<>();
        for (Object[] row : repository.findCountsByUserIds(userIds)) {
            result.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return result;
    }

    /** admin 汇总（真实用户范围） */
    @Transactional(readOnly = true)
    public PayQrDtos.AdminStats adminStats() {
        LocalDateTime now = LocalDateTime.now();
        UserPayQrStatusRepository.StatsRow row = repository.sumStats(now.minusDays(7), now.minusDays(30));
        return new PayQrDtos.AdminStats(
                row.getConfiguredUsers(), row.getEverConfiguredUsers(), row.getTotalQrCount(),
                row.getNewUsers7d(), row.getNewUsers30d(), MAX_COUNT);
    }

    /** admin 名单：配置过收款码的真实用户（含已清空者），最近变更在前 */
    @Transactional(readOnly = true)
    public PayQrDtos.AdminUsers adminUsers() {
        // 多取一条只用于判超限（⛔ 不用「取 N 条再猜有没有更多」）
        List<UserPayQrStatusRepository.UserRow> rows = repository.findConfiguredUsers(ADMIN_USERS_LIMIT + 1);
        boolean truncated = rows.size() > ADMIN_USERS_LIMIT;
        List<UserPayQrStatusRepository.UserRow> kept = truncated ? rows.subList(0, ADMIN_USERS_LIMIT) : rows;
        Map<Long, User> profiles = kept.isEmpty() ? Map.of()
                : userRepository.findAllById(kept.stream().map(UserPayQrStatusRepository.UserRow::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        List<PayQrDtos.AdminUserRow> users = kept.stream().map(r -> {
            User user = profiles.get(r.getUserId());
            return new PayQrDtos.AdminUserRow(
                    r.getUserId(),
                    UserCode.format(r.getUserId()),
                    user == null ? null : user.getNickname(),
                    UserCode.isCustomNickname(user),
                    user == null ? null : user.getAvatarUrl(),
                    r.getQrCount(),
                    r.getQrCount() == 0,
                    r.getFirstConfiguredAt(),
                    r.getChangedAt());
        }).toList();
        return new PayQrDtos.AdminUsers(users, ADMIN_USERS_LIMIT, truncated);
    }
}
