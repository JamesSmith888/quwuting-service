package org.quwuting.quwutingservice.venuepresence.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminUserTrackResponse;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminUserVisitsResponse;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端「用户到访足迹」接口（2026-10-06；文档 = docs/agents/52-venue-presence.md；仅 ADMIN）。
 * <p>
 * <b>为什么独立于 {@link AdminVenuePresenceController} 而不是并进去</b>：那是个
 * {@code /admin/venues} 前缀的控制器，本端点以<b>用户</b>为主键、页面归属是<b>用户详情页</b>
 * （admin 名单下钻的第二级：门店 → 到访用户名单 → 该用户的资料页）。
 * 塞进 venue 前缀会得到 {@code /admin/venues/admin/users/{id}/visits} 这种双重前缀路径——
 * 路径是给运维与 nginx 看的，语义拧着会让日后排查的人先怀疑一遍路由。
 * <p>
 * 与 {@code AdminUserController}（{@code /admin/users/**}）同前缀不同类：后者管用户资料与协作授权，
 * 本类只读到访足迹一个域；到访的口径权威（命中谓词 / 同址归因 / 桶合并）在
 * {@code VenuePresenceService}，⛔ 禁在本类另写一份查询。
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserPresenceController {

    private final VenuePresenceService venuePresenceService;

    /**
     * 某用户的到访足迹（仅 ADMIN，2026-10-06）。
     * GET /admin/users/{userId}/visits?windowDays=90
     * <p>
     * 按门店分组、组内展开<b>一次次到店</b>（到店时刻 / 已观测停留时长 / 采样次数 / 最近距离）——
     * 把到访从「近 30 天 N 人」这样的计数还原成具体事实。
     * <p>
     * <b>同一次到店的多个采样桶在服务端合并为一条</b>（采集主力 = 每次打开小程序 + 店内每
     * 15 分钟补采，不合并会把一次跳舞记成十几次到店）；合并阈值是服务端常量，
     * admin-web 端不参与判定，只负责展示。
     * <p>
     * ⚠️ 语义边界：{@code arrivedAt} = <b>首次被记录到</b>的时刻，<b>不是物理上跨进店门的时刻</b>——
     * 到店后第一次打开小程序才留痕。没打开小程序的到店不在此列（这也是全站到访数字
     * 「显著低于真实到店量」的根因）。{@code stayMinutes} 是<b>已观测</b>停留时长（下界）。
     * <p>
     * 用户不存在 / 已软删 → 1004。记录超上限时 {@code truncated=true}，admin 端必须显示
     * 「仅显示最近 N 次」——⛔ 静默截断会让运营把上限读成「他就这么多次来过」。
     * <p>
     * <b>同时下发开关段 {@code consent}</b>（2026-10-07）：当前是否启用到访足迹（四态，
     * 含「待补问」= 默认开启期未经询问）+ 开关变更流水 + 全站采集总开关。
     * 与足迹同响应的理由 = 运营在同一屏要同时回答「他是否允许我们记」与「他来过哪家店」，
     * 看到一堆到访却不知道对方早已关闭采集，会直接误判成隐私事故。
     */
    @GetMapping("/{userId}/visits")
    public ApiResponse<AdminUserVisitsResponse> userVisits(
            @PathVariable Long userId,
            @RequestParam(required = false) Integer windowDays) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venuePresenceService.visitsFor(
                userId, windowDays == null ? 90 : windowDays));
    }

    /**
     * 某用户的坐标轨迹（仅 ADMIN，2026-10-08 V46）。
     * GET /admin/users/{userId}/track?windowDays=7
     * <p>
     * 与 {@link #userVisits} 的分工：visits = 命中口径的「一次次到店」（分组 / 停留 /
     * 采样数）；本端点 = <b>全部</b>坐标采样点的原始轨迹（升序，含 150m 外的
     * 「附近 / 留痕」带）——2026-10-08 判例（user 210 在丽莎 295m 处上报但超命中线、
     * 到访不可见）正是本端点要回答的「他当时在哪」。
     * <p>
     * 窗口缺省 7 天、服务端钳制 1~90；无坐标的历史记录不在 points 里，以
     * {@code pointsWithoutCoordinates} 显式计数（⛔ 禁静默丢）。
     * 用户不存在 / 已软删 → 1004。
     */
    @GetMapping("/{userId}/track")
    public ApiResponse<AdminUserTrackResponse> userTrack(
            @PathVariable Long userId,
            @RequestParam(required = false) Integer windowDays) {
        UserContext.requireAdmin();
        return ApiResponse.ok(venuePresenceService.trackFor(
                userId, windowDays == null ? 7 : windowDays));
    }
}
