package org.quwuting.quwutingservice.announcement.dto.response;

import java.time.LocalDateTime;

/**
 * 首页公告位当前占用者（GET /admin/announcements/home-slot）。
 *
 * <p><b>为什么单独一个查询接口</b>（2026-10-08）：首页公告位是<b>容量为 1 的稀缺资源</b>
 * （不变量由 {@code HomeSlotService} + V44 唯一索引保证，见 docs/agents/34）。编辑页要能
 * 勾"首页置顶"，就必须先知道<b>位上现在是谁</b>——否则运营只能提交后吃一个 400，
 * 看不到"我这条顶不掉谁"。这与 2026-09-15「触达等级的后果必须对运营可见」同一条纪律：
 * <b>稀缺资源的占用状态必须可读，且可读处只有一个</b>。
 *
 * <p>{@code holder == null} = 首页位空着（无人占位）。运营据此判断：
 * 空 → 可直接置顶；非空且是本条 → 已是占位者；非空且是别条 → 需先取消对方。
 *
 * @param holderId   占位公告 id；null = 当前无人占位
 * @param holderTitle 占位公告标题（编辑页直接展示，避免运营另查列表）
 */
public record HomeSlotResponse(
        Long holderId,
        String holderTitle,
        LocalDateTime holderPublishAt
) {

    /** 位空着（编辑页文案与前端零分支用同一判据） */
    public boolean empty() {
        return holderId == null;
    }
}