package org.quwuting.quwutingservice.dancerule.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.dancerule.dto.RuleSnapshotSaveRequest;
import org.quwuting.quwutingservice.dancerule.dto.RuleSnapshotResponse;
import org.quwuting.quwutingservice.dancerule.entity.RuleSnapshotEntity;
import org.quwuting.quwutingservice.dancerule.repository.RuleSnapshotRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;

/**
 * 计价规则快照服务（2026-09-13，quwuting 仓 docs/agents/43-dance-timer.md §48）。
 * <p>
 * 职责边界：本服务是 qwt_dance_rule_snapshots 的唯一读写口（GET 拉回 / PUT
 * 幂等整体覆盖）。快照是不透明配置 blob——结构校验归客户端读写两侧，服务端
 * 只守大小上限；updated_at 由 BaseEntity 维护（POST 覆盖即推进），对外一律转
 * epoch 毫秒（客户端把双方水位都建在服务端时钟上，规避设备时钟偏移）。
 * 隐私边界与账本（SpendService）同款：接口全部 user-scoped，userId 恒取
 * 登录态，产品层永不对外分发单用户配置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleSnapshotService {

    /** 快照大小上限（UTF-8 字节）：规则 < 10 条 × 每条约 300 字节，32KB 已是
     *  异常量级的 10 倍以上——上限只防滥用与手改存储的脏载荷，不约束正常演化 */
    private static final int SNAPSHOT_MAX_BYTES = 32 * 1024;

    private static final int CODE_SNAPSHOT_INVALID = 1040;

    private final RuleSnapshotRepository ruleSnapshotRepository;

    /**
     * 读取当前用户的规则快照。云端无快照返回 snapshot=null / updatedAt=0
     * （客户端据此走本地出厂 seed——null 是合法且常见的状态：从未上过云的老
     * 用户 / 全新账号，不是错误）。
     */
    @Transactional(readOnly = true)
    public RuleSnapshotResponse get(Long userId) {
        return ruleSnapshotRepository.findByUserId(userId)
                .map(e -> new RuleSnapshotResponse(e.getSnapshotJson(), toEpochMillis(e.getUpdatedAt())))
                .orElse(new RuleSnapshotResponse(null, 0L));
    }

    /**
     * 幂等写入（整体覆盖）：已有行即覆盖 snapshotJson（updated_at 由
     * {@code @UpdateTimestamp} 推进），否则插入。重复 PUT 同一内容重放安全
     * ——快照语义天然幂等，无需条目级去重键。
     */
    @Transactional
    public RuleSnapshotResponse put(Long userId, RuleSnapshotSaveRequest request) {
        String snapshot = request == null ? null : request.snapshot();
        if (snapshot == null || snapshot.isBlank()) {
            throw new BusinessException(CODE_SNAPSHOT_INVALID, "快照内容不能为空");
        }
        if (snapshot.getBytes(StandardCharsets.UTF_8).length > SNAPSHOT_MAX_BYTES) {
            throw new BusinessException(CODE_SNAPSHOT_INVALID, "快照超出大小上限");
        }
        RuleSnapshotEntity entity = ruleSnapshotRepository.findByUserId(userId)
                .orElseGet(() -> {
                    RuleSnapshotEntity created = new RuleSnapshotEntity();
                    created.setUserId(userId);
                    return created;
                });
        entity.setSnapshotJson(snapshot);
        RuleSnapshotEntity saved = ruleSnapshotRepository.save(entity);
        return new RuleSnapshotResponse(saved.getSnapshotJson(), toEpochMillis(saved.getUpdatedAt()));
    }

    private long toEpochMillis(java.time.LocalDateTime ts) {
        return ts == null ? 0L : ts.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
