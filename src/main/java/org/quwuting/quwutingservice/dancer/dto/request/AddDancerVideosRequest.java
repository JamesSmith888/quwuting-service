package org.quwuting.quwutingservice.dancer.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 舞伴短视频上传请求体（POST /dancers/{id}/videos，2026-08-22 新增）。
 * <p>
 * 与照片同链：插入即 PENDING（先审后发），普通用户不可调用（服务层 canManage 校验）；
 * url 来自前端经 /storage/upload-token（DANCER_VIDEO 分类）直传 Supabase 后返回的公开地址。
 * <p>
 * 与照片请求的差异（独立接口而非混入 AddDancerPhotosRequest——请求体语义不同）：
 * 视频需要 coverUrls（chooseMedia thumb 帧上传的封面图，列表/审核预览用）与 durations（秒）；
 * blurUrls = <b>封面帧降采样模糊版</b>（2026-08-22 视频门槛配套：有门槛时未解锁用户获得
 * 模糊封面，同照片 blurUrl 遮罩语义，不泄露清晰首帧；无门槛视频无需模糊封面）。
 * coverUrls / blurUrls / durations 与 urls 按 index 一一对应（缺省该项 = 封面/模糊封面/
 * 时长未知，展示端回退虚焦占位 / 不展示时长）。
 */
public record AddDancerVideosRequest(
        @NotEmpty(message = "请至少选择一个视频")
        @Size(max = MAX_VIDEOS_PER_BATCH, message = "单次最多上传" + MAX_VIDEOS_PER_BATCH + "个视频")
        List<@Valid @Size(max = 500, message = "视频地址过长") String> urls,

        /** 视频封面帧图 URL（与 urls 按 index 一一对应；可缺省） */
        @Size(max = MAX_VIDEOS_PER_BATCH, message = "封面图数量超出范围")
        List<@Valid @Size(max = 500, message = "封面图地址过长") String> coverUrls,

        /** 视频模糊封面图 URL（2026-08-22 视频门槛配套：封面帧降采样模糊版，与 urls 对齐；可缺省） */
        @Size(max = MAX_VIDEOS_PER_BATCH, message = "模糊封面数量超出范围")
        List<@Valid @Size(max = 500, message = "模糊封面地址过长") String> blurUrls,

        /** 视频时长（秒，与 urls 按 index 一一对应；可缺省 = 时长未知不展示） */
        @Size(max = MAX_VIDEOS_PER_BATCH, message = "时长数量超出范围")
        List<@Positive(message = "视频时长无效") Integer> durations
) {
    /** 单次批量上传上限（短视频少量场景——每个视频含封面/模糊封面共三次直传，控制审核负担） */
    public static final int MAX_VIDEOS_PER_BATCH = 3;
}
