package org.quwuting.quwutingservice.media;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.quwuting.quwutingservice.storage.MediaKind;

/**
 * 媒体附件单元（运营内容配图 / 短视频，2026-09-28）。
 * <p>
 * <b>为什么媒体要结构化落库而不是埋在 markdown 正文里（根因）</b>：媒体若只是
 * 正文里的字符串（{@code ![](url)} / {@code <video src>}），它就不是"事实"——
 * 无法单独替换/统计/回收，"这条内容带没带媒体"只能靠正则从正文猜，视频的
 * 封面/播放体验也无法结构化表达。结构化后媒体成为可管理的数据。
 * <p>
 * 结构单点 = {@link MediaAttachments}（解析/序列化/结构校验），本 record 同时
 * 充当请求 DTO 元素（管理端 / Agent 通道提交）与响应 DTO 元素（用户端渲染）——
 * 两个通道共用同一契约，避免"一条通道加了字段另一条忘加"。
 * <p>
 * 序列化形态（{@code qwt_announcements.media_json}，varchar 存 JSON 串，
 * 与 {@code qwt_venues.business_hours} 同一模式）：
 * <pre>[{"type":"VIDEO","url":"…mp4","poster":"…jpg"}, {"type":"IMAGE","url":"…webp"}]</pre>
 *
 * @param type   媒体类型（存储校验通道与渲染方式的唯一判据；图片可预览、视频内联播放）
 * @param url    媒体地址（必须 https；<b>仅接受本应用存储白名单内的地址</b>——
 *               媒体是可管理的持久资产，外链失效会让附件卡变死块，且跨仓约定
 *               "图片 URL 落库字段必校验"（ImageContentValidator）；正文 markdown
 *               中的外链图片不受此限，仅为历史内容保留渲染兼容）
 * @param poster 视频封面（仅 VIDEO 类型有意义，可空；可空时不下发封面，渲染侧
 *               用原生播放按钮兜底）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaAttachment(
        MediaKind type,
        String url,
        String poster
) {
    public MediaAttachment {
        // 紧凑构造器规范化（任何来源统一：JSON 反序列化 / 代码构造），空串一律归 null
        url = normalize(url);
        poster = normalize(poster);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
