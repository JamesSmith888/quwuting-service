package org.quwuting.quwutingservice.venuepresence.service;

/**
 * 浏览者视角的单店足迹（{@code VenuePresenceService#nearbyFootprintForViewer} 的产出；
 * 2026-10-16 详情页「到店足迹」行 + 贡献者回路）：展示口径事实 + 浏览者<b>自己</b>在这份事实里的位置。
 * <p>
 * <b>为什么一次产出两样、而不是调用方各取一次</b>：详情页同屏显示「卡片原文」与「你留下过 N 次」，
 * 两者必须出自<b>同一次证据加载</b>——分两次取，窗口边界上会出现「卡片说 3 位舞友、你的那句却认为
 * 你不在其中」这类无法自洽的瞬间；同一快照则「我在不在这 N 个人里」是结构保证而不是巧合。
 * <p>
 * <b>事实与表达分离</b>：本记录只含数字，文案由展示层（{@code VenueVisitBadgeService}）渲染——
 * 同 {@link NearbyDetail} 的分工。
 *
 * @param footprint 展示口径事实（与列表卡片同一计算）
 * @param mine      浏览者自己的足迹；{@code null} = 浏览者未登录 / 不在被计入的贡献者之列
 *                  （被排除账号同样 null——卡片不计他，就不对他说「你留下过」）
 */
public record ViewerFootprint(NearbyFootprint footprint, Mine mine) {

    /**
     * 浏览者自己的足迹。<b>只含「他自己的」数字</b>——不含任何其他人的身份或明细
     * （52 号「永不公开谁到过」裁决：本接口面能多说的只有「你自己」）。
     *
     * @param visitEvents30d 近 30 天自己的去重到访次数 = 自己的 {@code (用户, 自然日)} 并集大小，
     *                       与卡片「N 次」同一口径（卡片的 N 恰是全体用户该值之和）；恒 ≥ 1
     */
    public record Mine(long visitEvents30d) {
    }
}
