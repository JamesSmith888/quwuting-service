package org.quwuting.quwutingservice.dancerule.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.dancerule.dto.RuleSnapshotSaveRequest;
import org.quwuting.quwutingservice.dancerule.dto.RuleSnapshotResponse;
import org.quwuting.quwutingservice.dancerule.entity.RuleSnapshotEntity;
import org.quwuting.quwutingservice.dancerule.repository.RuleSnapshotRepository;
import org.quwuting.quwutingservice.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RuleSnapshotService 单元测试（Mockito，不依赖数据库）。
 *
 * 覆盖目标（quwuting 仓 docs/agents/43-dance-timer.md §48）：快照上云是
 * 「清缓存后规则选择丢失」修复的云端半边——读路径必须把「从未上过云」表达为
 * snapshot=null（客户端据此走本地出厂 seed，不得当空对象处理）；写路径必须
 * 幂等整体覆盖（PUT 重放安全）；非法载荷（空 / 超长）显式拒绝（服务端盲收
 * 会放大客户端 bug——账本域 40 号判据 1 的同款教训）。
 */
@ExtendWith(MockitoExtension.class)
class RuleSnapshotServiceTest {

    private static final long USER_ID = 42L;

    /** 固定时刻（避免用例依赖当前时间）：2026-09-13T00:00:00 → epoch 毫秒按系统时区换算 */
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 9, 13, 0, 0, 0);

    @Mock
    private RuleSnapshotRepository ruleSnapshotRepository;

    private RuleSnapshotService service() {
        return new RuleSnapshotService(ruleSnapshotRepository);
    }

    private RuleSnapshotEntity entity(String snapshot, LocalDateTime updatedAt) {
        RuleSnapshotEntity e = new RuleSnapshotEntity();
        e.setUserId(USER_ID);
        e.setSnapshotJson(snapshot);
        e.setUpdatedAt(updatedAt);
        return e;
    }

    /** 云端无快照 = 从未上过云的合法状态：snapshot=null、updatedAt=0（不是错误） */
    @Test
    void getReturnsNullSnapshotWhenAbsent() {
        when(ruleSnapshotRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        RuleSnapshotResponse res = service().get(USER_ID);
        assertEquals(null, res.snapshot());
        assertEquals(0L, res.updatedAt());
    }

    /** 有快照：原文原样返回，updatedAt 转为 epoch 毫秒（客户端水位用服务端时钟） */
    @Test
    void getReturnsSnapshotWithEpochMillis() {
        String json = "{\"version\":1,\"rules\":[],\"selectedRuleId\":\"\",\"presetTombstones\":[]}";
        when(ruleSnapshotRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(entity(json, UPDATED_AT)));
        RuleSnapshotResponse res = service().get(USER_ID);
        assertEquals(json, res.snapshot());
        assertEquals(UPDATED_AT.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                res.updatedAt());
    }

    /** updatedAt 为 null 的存量行不炸（兜底 0） */
    @Test
    void getToleratesNullUpdatedAt() {
        when(ruleSnapshotRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(entity("{}", null)));
        assertEquals(0L, service().get(USER_ID).updatedAt());
    }

    /** 首次 PUT：创建行并归属登录用户 */
    @Test
    void putCreatesWhenAbsent() {
        when(ruleSnapshotRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(ruleSnapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RuleSnapshotResponse res = service().put(USER_ID, new RuleSnapshotSaveRequest("{\"version\":1}"));
        ArgumentCaptor<RuleSnapshotEntity> captor = ArgumentCaptor.forClass(RuleSnapshotEntity.class);
        verify(ruleSnapshotRepository).save(captor.capture());
        assertEquals(USER_ID, captor.getValue().getUserId());
        assertEquals("{\"version\":1}", captor.getValue().getSnapshotJson());
        assertNotNull(res);
    }

    /** 已有行：覆盖原文，不新建第二行（user_id 唯一约束的语义前提） */
    @Test
    void putOverwritesExistingRow() {
        RuleSnapshotEntity existing = entity("old", UPDATED_AT);
        when(ruleSnapshotRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(ruleSnapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service().put(USER_ID, new RuleSnapshotSaveRequest("new"));
        verify(ruleSnapshotRepository).save(existing);
        assertEquals("new", existing.getSnapshotJson());
    }

    /** 空 / 空白载荷显式拒绝（code=1040）——绝不静默落库 */
    @Test
    void putRejectsBlankSnapshot() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().put(USER_ID, new RuleSnapshotSaveRequest("  ")));
        assertEquals(1040, ex.getCode());
        verify(ruleSnapshotRepository, never()).save(any());
    }

    @Test
    void putRejectsNullPayload() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().put(USER_ID, null));
        assertEquals(1040, ex.getCode());
    }

    /** 超过 32KB 上限拒绝（防滥用与脏载荷，正常规则集远小于此） */
    @Test
    void putRejectsOversizedSnapshot() {
        String big = "x".repeat(33 * 1024);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().put(USER_ID, new RuleSnapshotSaveRequest(big)));
        assertEquals(1040, ex.getCode());
        verify(ruleSnapshotRepository, never()).save(any());
    }
}
