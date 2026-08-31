package org.quwuting.quwutingservice.dancer.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.dancer.dto.request.AddDancerPhotosRequest;
import org.quwuting.quwutingservice.dancer.dto.request.AddDancerVideosRequest;
import org.quwuting.quwutingservice.dancer.dto.request.RecognizeDancerRequest;
import org.quwuting.quwutingservice.dancer.dto.request.RecordDancerViewRequest;
import org.quwuting.quwutingservice.dancer.dto.request.UpsertDancerRequest;
import org.quwuting.quwutingservice.dancer.dto.request.UpsertDancerServiceRequest;
import org.quwuting.quwutingservice.dancer.dto.response.AdViewResponse;
import org.quwuting.quwutingservice.dancer.dto.response.DancerDetailResponse;
import org.quwuting.quwutingservice.dancer.dto.response.DancerDemandRecord;
import org.quwuting.quwutingservice.dancer.dto.response.DancerPhotoResponse;
import org.quwuting.quwutingservice.dancer.dto.response.DancerServiceResponse;
import org.quwuting.quwutingservice.dancer.dto.response.DancerStatsResponse;
import org.quwuting.quwutingservice.dancer.dto.response.DancerSummaryResponse;
import org.quwuting.quwutingservice.dancer.dto.response.DancerTagsResponse;
import org.quwuting.quwutingservice.dancer.dto.response.DancerUnlockRecord;
import org.quwuting.quwutingservice.dancer.dto.response.RecognizeResponse;
import org.quwuting.quwutingservice.dancer.service.DancerService;
import org.quwuting.quwutingservice.dancer.service.DancerStatsService;
import org.quwuting.quwutingservice.dancer.service.DancerViewService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.enums.ViewSource;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 舞伴生态体系公开/用户接口（路由挂载在 /dancers 下）。
 * 接口集合与规范对应需求（见 AGENTS.md「舞伴生态体系」）：
 * <ul>
 *   <li>GET /dancers — 舞伴列表（公开，登录时含个人认可态；city 可选筛选）</li>
 *   <li>GET /dancers/cities — 常驻城市词表（列表页城市筛选；聚合真实数据）</li>
 *   <li>GET /dancers/favorites — 我的收藏列表（登录，2026-08-14 舞伴收藏）</li>
 *   <li>POST /dancers/{id}/favorite — 收藏舞伴（登录，幂等）</li>
 *   <li>POST /dancers/{id}/favorite/remove — 取消收藏（登录，幂等）</li>
 *   <li>GET /dancers/{id} — 舞伴详情（公开，可见性规则见 DancerService）</li>
 *   <li>POST /dancers/{id}/update — 编辑本人/管理舞伴资料（全量覆盖；REJECTED → 自动重审，
 *       2026-08-19 由 PUT /dancers/{id} 迁移对齐「只允许 GET 和 POST」约定）</li>
 *   <li>GET /dancers/{id}/tags — 舞伴标签聚合（公开）</li>
 *   <li>POST /dancers/{id}/recognitions — 认可 toggle（登录）</li>
 *   <li>POST /dancers/{id}/photos — 本人/管理员上传相册（插入即 PENDING 待审）</li>
 *   <li>POST /dancers/{id}/photos/{photoId}/remove — 本人/管理员删除照片
 *       （2026-08-19 由 DELETE 迁移对齐「只允许 GET 和 POST」约定）</li>
 * </ul>
 * 认可体系语义（产品定位）：用户认可/支持/点赞，不含打赏、礼物、虚拟币等金钱/排行概念。
 */
@RestController
@RequestMapping("/dancers")
@RequiredArgsConstructor
public class DancerController {

    private final DancerService dancerService;
    private final DancerStatsService dancerStatsService;
    private final DancerViewService dancerViewService;

    /**
     * 舞伴列表（公开，软鉴权：登录时返回个人"今日已认可"状态）。
     * 支持按常驻城市筛选（city 可选）与<b>服务类别筛选</b>（serviceCategory 可选，
     * 2026-08-24 需求优先匹配：命中"存在 ≥1 个在用且类别匹配的服务"的舞伴，
     * 枚举 code = PACKAGE/DANCE/BAR/ONLINE_CHAT/OTHER）；
     * <b>排序模式</b>（sort 可选，2026-08-26 晚新增）：
     * HOT（默认，组合分 = 近7天认可 + 新鲜度加成 + 近30天收藏 tie-break）/
     * LATEST（id 倒序，新资料在前）；null/空串 → HOT 兜底（旧客户端零回归），
     * 非法值 → 1001。排序细节见 DancerService#listPublic。
     */
    @GetMapping
    public ApiResponse<Page<DancerSummaryResponse>> list(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String serviceCategory,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(dancerService.listPublic(city, serviceCategory, sort, page, size,
                UserContext.getCurrentUserId()));
    }

    /**
     * ⚠️ 2026-08-21 已下线：原「舞伴主动注册 POST /dancers（登录 → status=PENDING）」
     * 因个人主体小程序「收集、存储用户身份信息」审核驳回被移除——舞伴资料改为
     * 纯平台发布内容，创建通道唯一 = 管理员 POST /admin/dancers（status=NORMAL 直通
     * 公开）。前端无任何普通用户创建入口，见 AGENTS.md「小程序类目合规 UGC 红线」。
     */

    /**
     * 编辑舞伴资料（本人 createdBy 匹配 或 管理员）：全量覆盖可编辑字段；
     * REJECTED 资料编辑后自动回到 PENDING（重新送审）。返回更新后详情。
     * <p>
     * ⚠️ 合规约束（2026-08-21 个人主体审核驳回沉淀，见 AGENTS.md「小程序类目合规
     * UGC 红线」）：舞伴资料 = 平台发布的黄页内容，创建通道唯一 = 管理员
     * （POST /admin/dancers，status=NORMAL 直通公开）；本接口的「本人」分支仅
     * 服务历史数据（早期用户主动注册创建的舞伴）的存量编辑，前端无任何非管理员
     * 入口（dancer-edit 页面仅管理员可进入），不构成"收集、存储用户身份信息"。
     * <p>
     * 2026-08-19：HTTP 方法对齐项目「只允许 GET 和 POST」约定——由 PUT /dancers/{id}
     * 迁移为 POST /dancers/{id}/update（原 PUT 路径已废弃；与门店 favorite 的
     * POST 幂等写先例一致，见 12-api-conventions.md）。
     */
    @PostMapping("/{id}/update")
    public ApiResponse<DancerDetailResponse> update(@PathVariable Long id,
                                                    @Valid @RequestBody UpsertDancerRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(dancerService.updateDancer(userId, id, request, UserContext.getCurrentRole()));
    }

    /**
     * 舞伴详情（公开，软鉴权：登录时返回我的认可态与 isMine）。
     * PENDING/HIDDEN 资料仅创建人本人与管理员可见（服务层可见性校验）。
     */
    @GetMapping("/{id}")
    public ApiResponse<DancerDetailResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(dancerService.getDetail(id, UserContext.getCurrentUserId(), UserContext.getCurrentRole()));
    }

    /**
     * 舞伴服务范围列表（公开软鉴权，2026-08-24——与详情同可见性校验）。
     * 详情页服务卡数据已随详情响应下发（services 字段，公共缓存），本端点供
     * 独立场景/前端降级直查（口径一致）。需求弹层「服务场景」chip 数据源。
     */
    @GetMapping("/{id}/services")
    public ApiResponse<List<DancerServiceResponse>> services(@PathVariable Long id) {
        return ApiResponse.ok(dancerService.listServices(
                id, UserContext.getCurrentUserId(), UserContext.getCurrentRole()));
    }

    @PostMapping("/{id}/services")
    public ApiResponse<DancerServiceResponse> addService(
            @PathVariable Long id,
            @Valid @RequestBody UpsertDancerServiceRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(dancerService.addService(userId, id, request));
    }

    @PostMapping("/{id}/services/{serviceId}")
    public ApiResponse<DancerServiceResponse> updateService(
            @PathVariable Long id,
            @PathVariable Long serviceId,
            @Valid @RequestBody UpsertDancerServiceRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(dancerService.updateService(userId, id, serviceId, request));
    }

    @PostMapping("/{id}/services/{serviceId}/remove")
    public ApiResponse<Void> removeService(@PathVariable Long id, @PathVariable Long serviceId) {
        Long userId = UserContext.requireAuth();
        dancerService.removeService(userId, id, serviceId);
        return ApiResponse.ok(null);
    }

    /** 舞伴标签聚合（公开软鉴权；2026-08-19 扩展：响应含 myTags——当前用户今日
     *  投票，舞伴认可明细页行活跃态数据源，见 DancerTagsResponse） */
    @GetMapping("/{id}/tags")
    public ApiResponse<DancerTagsResponse> getTags(@PathVariable Long id) {
        return ApiResponse.ok(dancerService.getTags(id, UserContext.getCurrentUserId(), UserContext.getCurrentRole()));
    }

    /**
     * 认可 toggle（需登录，2026-08-15 单票换票模型）：body.tag = 单枚表情（今日唯一票）——
     * 今日未认可 → 参与；今日同标签 → 取消；今日异标签 → 原子换票。
     * 旧客户端 body.tags（0-3 列表）走兼容路径（未认可 → 参与，已认可 → 取消）。
     * 返回最终参与态 + 被替换旧标签 + 今日标签 + 最新四窗口统计 + 标签聚合绝对快照
     * （前端据此本地收敛，无需整页刷新）。
     */
    @PostMapping("/{id}/recognitions")
    public ApiResponse<RecognizeResponse> recognize(@PathVariable Long id,
                                                    @Valid @RequestBody(required = false) RecognizeDancerRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(dancerService.toggleRecognize(userId, id, request, UserContext.getCurrentRole()));
    }

    /**
     * 舞伴统计（<b>公开访问</b>，2026-08-14 舞伴统计图第一期；2026-08-24 放开：
     * 对齐门店 venue-heat 公开先例——响应为纯计数聚合（趋势/累计/解锁人次），
     * 无个人身份信息，详情页「统计图」动作行按钮全员可见可入）：
     * 六组近30天每日时间序列（认可/收藏/礼物价值/分享/浏览 + 浏览来源），供舞伴统计页
     * 渲染。缓存 60s refresh-ahead（写路径显式失效）。
     */
    @GetMapping("/{id}/stats")
    public ApiResponse<DancerStatsResponse> stats(@PathVariable Long id) {
        return ApiResponse.ok(dancerStatsService.getStats(id));
    }

    /**
     * 舞伴解锁记录明细（<b>公开访问</b>，2026-08-26 新增——对齐 stats / gifters
     * 公开先例：响应为用户公开资料（昵称/头像）+ 解锁时间/内容描述/花费积分，
     * 无身份敏感字段；「解锁信息」条形点击 → 详情页数据源）。
     * targetType = PointsGateTargetType.name()（DANCER_PHOTO / DANCER_VIDEO /
     * DANCER_CONTACT），实时查询按解锁时间倒序。
     * 舞伴存在性 + 公开可见性校验（对齐 gifters validateTargetVisible）。
     */
    @GetMapping("/{id}/unlocks")
    public ApiResponse<List<DancerUnlockRecord>> unlocks(@PathVariable Long id,
                                                         @RequestParam String targetType) {
        return ApiResponse.ok(dancerStatsService.unlocks(id, targetType));
    }

    /**
     * 需求热度明细（资料创建者或管理员；聚合统计公开，逐条邀约保持受保护）。
     * 仅返回去标识化邀约内容与状态，提出者身份不出端。
     */
    @GetMapping("/{id}/demand-records")
    public ApiResponse<Page<DancerDemandRecord>> demandRecords(@PathVariable Long id,
                                                               @RequestParam String category,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(dancerStatsService.demandRecords(UserContext.requireAuth(),
                UserContext.getCurrentRole(), id, category, page, size));
    }

    /**
     * 记录舞伴详情页浏览（软鉴权：未登录时 userId 为 null，匿名访问不去重）。
     * body.source 为浏览来源（LIST/SHARE/SEARCH/OTHER），可空——旧客户端不传时兜底
     * OTHER（fire-and-forget，由详情页 GET /dancers/{id} 发起）。
     * POST /dancers/{id}/view
     */
    @PostMapping("/{id}/view")
    public ApiResponse<Void> recordView(@PathVariable Long id,
                                        @RequestBody(required = false) RecordDancerViewRequest request) {
        ViewSource source = null;
        if (request != null && request.source() != null) {
            try {
                source = ViewSource.valueOf(request.source());
            } catch (IllegalArgumentException e) {
                // 非法来源值兜底 OTHER（应用层防御，枚举列无 CHECK 约束）
                source = ViewSource.OTHER;
            }
        }
        dancerViewService.recordView(id, UserContext.getCurrentUserId(), source);
        return ApiResponse.ok(null);
    }

    /**
     * 常驻城市词表（公开；列表页城市筛选数据源——聚合真实数据，新增城市自动出现，
     * 与 venue 域 /venues/cities 同模式）。
     */
    @GetMapping("/cities")
    public ApiResponse<List<String>> cities() {
        return ApiResponse.ok(dancerService.listPublicCities());
    }

    /**
     * 我的收藏列表（需登录；按收藏时间倒序，仅当前公开 NORMAL 舞伴——HIDDEN 自动
     * 淡出，2026-08-14 舞伴收藏，见 AGENTS.md「舞伴收藏」）。
     * 静态路径优先于 /dancers/{id}（与 /cities 同先例），Spring MVC 精确匹配优先。
     */
    @GetMapping("/favorites")
    public ApiResponse<List<DancerSummaryResponse>> listFavorites() {
        return ApiResponse.ok(dancerService.listFavorites(UserContext.requireAuth()));
    }

    /**
     * 收藏舞伴（需登录；幂等——已收藏则忽略，软删行 restore 复用）。
     * 与门店收藏同款接口形态：POST 幂等写（POST /favorites/{venueId} /
     * POST /favorites/{venueId}/remove 先例），不用 PUT/DELETE（仅 GET/POST 语义约定）。
     */
    @PostMapping("/{id}/favorite")
    public ApiResponse<Void> addFavorite(@PathVariable Long id) {
        dancerService.addFavorite(UserContext.requireAuth(), id);
        return ApiResponse.ok(null);
    }

    /**
     * 取消收藏（需登录；幂等——未收藏则忽略）。软删行保留——被收藏舞伴 HIDDEN
     * 下架后行留存，恢复 NORMAL 后自动重现。
     */
    @PostMapping("/{id}/favorite/remove")
    public ApiResponse<Void> removeFavorite(@PathVariable Long id) {
        dancerService.removeFavorite(UserContext.requireAuth(), id);
        return ApiResponse.ok(null);
    }

    /**
     * 本人/管理员上传相册照片（需登录 + canManage）：插入即 PENDING（先审后发）。
     * blurUrls 为与原图一一对应的模糊图（收费照片详情页"模糊可见轮廓"占位，可缺省）。
     * 返回本人视角全量照片（含刚上传的待审项，编辑页据此刷新）。
     */
    @PostMapping("/{id}/photos")
    public ApiResponse<List<DancerPhotoResponse>> addPhotos(@PathVariable Long id,
                                                            @Valid @RequestBody AddDancerPhotosRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(dancerService.addPhotos(userId, id, request.urls(), request.blurUrls(),
                UserContext.getCurrentRole()));
    }

    /**
     * 本人/管理员上传短视频（需登录 + canManage，2026-08-22 新增）：插入即 PENDING，
     * 逐条审核后公开（与照片同审核链）。coverUrls（封面帧图）与 durations（秒）与
     * urls 按 index 一一对应（可缺省）。删除复用照片删除端点（同表软删）。
     * 返回本人视角全量相册（含刚上传的待审项）。
     */
    @PostMapping("/{id}/videos")
    public ApiResponse<List<DancerPhotoResponse>> addVideos(@PathVariable Long id,
                                                            @Valid @RequestBody AddDancerVideosRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(dancerService.addVideos(userId, id, request, UserContext.getCurrentRole()));
    }

    /**
     * 广告观看完成上报（需登录 + 目标开启创作者收益计划；本人不可观看自己的广告）。
     * 激励视频完整观看后由前端调用，计入舞伴收益（线下结算依据）；同一用户同舞伴
     * 每天至多一次（幂等，不重复计收益）。
     */
    @PostMapping("/{id}/ad-views")
    public ApiResponse<AdViewResponse> recordAdView(@PathVariable Long id) {
        return ApiResponse.ok(dancerService.recordAdView(
                UserContext.requireAuth(), id, UserContext.getCurrentRole()));
    }

    /**
     * 本人/管理员删除照片（软删；普通用户不可调用）。
     * 2026-08-19：HTTP 方法对齐「只允许 GET 和 POST」约定——由 DELETE /dancers/{id}/photos/{photoId}
     * 迁移为 POST /dancers/{id}/photos/{photoId}/remove（逻辑删除 = POST 动作路径先例，
     * 同 POST /venues/{id}/disable）。
     */
    @PostMapping("/{id}/photos/{photoId}/remove")
    public ApiResponse<Void> removePhoto(@PathVariable Long id, @PathVariable Long photoId) {
        Long userId = UserContext.requireAuth();
        dancerService.removePhoto(userId, id, photoId, UserContext.getCurrentRole());
        return ApiResponse.ok(null);
    }
}
