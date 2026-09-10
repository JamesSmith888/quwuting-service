package org.quwuting.quwutingservice.spend.dto;

import java.util.List;

/**
 * 批量同步结果：accepted = 落库（新增或更新）条数；rejected = 校验失败跳过条数
 * （缺 clientEntryId / 金额非正 / 枚举非法等）。rejected 不阻断 accepted 条目落库。
 * <p>
 * {@code rejectedIds} = 被拒条目的 clientEntryId 明细（2026-09-11 新增，44 号 §21）。
 * <p>
 * 为什么"逐条归因"是协议的一部分而非便利字段：只有计数时，客户端无法知道是哪几条
 * 失败，于是**只能整批当作已处理清队**——这正是 2026-09-11 事故里"服务端全量拒绝
 * 而客户端状态行显示已同步"的实现原因（失败在客户端没有可表达的位置）。有了明细，
 * 客户端才能做到「确认成功的移出队列 / 被拒的留队计数 / 反复被拒的转死信并显性
 * 可见」，且本地账目永不删除。
 * <p>
 * 向后兼容：字段为**追加**，旧客户端忽略即可；顺序与 {@code rejected} 计数一致
 * （长度可为 0 仅当无拒绝）。
 */
public record SpendSyncResponse(
        int accepted,
        int rejected,
        List<String> rejectedIds
) {
}
