package org.quwuting.quwutingservice.dancerule.dto;

/**
 * 规则快照写入请求（POST /dance-rules/snapshot，幂等整体覆盖）。
 *
 * snapshot = 客户端序列化的整份规则配置 JSON（不透明 blob）。服务端只校验
 * 非空与 UTF-8 大小上限（≤ 32KB，防御异常载荷）；结构校验归客户端读写两侧
 * ——服务端强 schema 会把客户端演化绑死在服务端版本上（快照整体写、单用户
 * 单份，损坏面远小于条目级同步，判据见 quwuting 仓 43 号 §48）。
 */
public record RuleSnapshotSaveRequest(
        String snapshot
) {
}
