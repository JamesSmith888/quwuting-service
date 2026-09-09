package org.quwuting.quwutingservice.spend.dto;

/**
 * 批量同步结果：accepted = 落库（新增或更新）条数；rejected = 校验失败跳过条数
 * （缺 clientEntryId / 金额非正 / 枚举非法等）。rejected 不阻断 accepted 条目落库。
 */
public record SpendSyncResponse(
        int accepted,
        int rejected
) {
}
