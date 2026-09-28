package org.quwuting.quwutingservice.media;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.storage.MediaKind;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 媒体附件的<b>结构单点</b>（2026-09-28）：解析（读侧容错）/ 序列化（写侧）/
 * 结构校验。公告域与快讯域共用本类——两个域复用同一张表与实体，媒体契约
 * 若各写一份，必然漂移（先例：两域 validateSchedule 逐字重复是有意 trade-off，
 * 但媒体解析有"坏数据容错策略"这种必须全网一致的行为，必须单点）。
 * <p>
 * <b>读写两侧容错策略不同，且都是有意为之</b>：
 * <ul>
 *   <li>读侧（{@link #parseOrEmpty}）：历史数据 / 脏数据解析失败 → 空列表 + warn，
 *       <b>不抛</b>——一条坏 JSON 不能让整页信息流挂掉（渲染降级比 500 正确）；</li>
 *   <li>写侧（{@link #validateStructure} + {@link #serialize}）：结构校验抛
 *       {@link BusinessException}、序列化超上限抛——写侧是唯一入口，拒绝脏数据
 *       落库比读侧容错便宜得多。</li>
 * </ul>
 * 内容级 URL 校验（白名单 + 下载验图）归 {@link MediaAttachmentValidator}（Spring
 * Bean，依赖 {@code ImageContentValidator}）；本类保持纯静态零 Spring 依赖，可单测。
 */
@Slf4j
public final class MediaAttachments {

    /** 附件总数上限（快讯一条消息 / 公告一篇正文的合理配图规模上限） */
    public static final int MAX_TOTAL = 9;

    /** 视频附件上限（视频 50MB 级，数量约束比图片严） */
    public static final int MAX_VIDEOS = 3;

    /** 序列化 JSON 字符上限（与 V32 列宽 varchar(4000) 对齐；写入前必须满足） */
    public static final int MAX_JSON_CHARS = 4000;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final TypeReference<List<MediaAttachment>> MEDIA_LIST = new TypeReference<>() {};

    private MediaAttachments() {
    }

    /**
     * 读侧容错解析：null / 空白 → 空列表；坏 JSON / 形状不符 → 空列表 + warn。
     * <b>绝不抛异常</b>——列表/详情渲染路径消费本方法，一条坏数据不能放大成页面 500。
     */
    public static List<MediaAttachment> parseOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<MediaAttachment> parsed = MAPPER.readValue(json, MEDIA_LIST);
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (RuntimeException e) {
            log.warn("[media] failed to parse attachments json, render as empty: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 写侧序列化：null / 空列表 → {@code null}（列保持 NULL = 无附件，幂等清空语义）；
     * 非空则序列化并兜底校验长度（调用方应已过 {@link #validateStructure}，此处防两道
     * 校验之间的口径漂移）。
     */
    public static String serialize(List<MediaAttachment> media) {
        if (media == null || media.isEmpty()) {
            return null;
        }
        String json = MAPPER.writeValueAsString(media);
        if (json.length() > MAX_JSON_CHARS) {
            throw new BusinessException(400, "媒体附件数据超出存储上限（" + MAX_JSON_CHARS + " 字符）");
        }
        return json;
    }

    /**
     * 写侧结构校验（纯结构，零依赖）：条数上限、type/url 必填、https 强制、
     * 视频条数上限。URL 的内容级校验（白名单 / 下载验图 / 视频扩展名）归
     * {@link MediaAttachmentValidator}。
     */
    public static void validateStructure(List<MediaAttachment> media) {
        if (media == null || media.isEmpty()) {
            return;
        }
        if (media.size() > MAX_TOTAL) {
            throw new BusinessException(400, "媒体附件最多 " + MAX_TOTAL + " 个");
        }
        int videos = 0;
        for (MediaAttachment m : media) {
            if (m == null) {
                throw new BusinessException(400, "媒体附件不能包含空条目");
            }
            if (m.type() == null) {
                throw new BusinessException(400, "媒体附件类型不能为空");
            }
            if (m.url() == null) {
                throw new BusinessException(400, "媒体附件地址不能为空");
            }
            requireHttps(m.url(), "媒体附件地址");
            if (m.poster() != null) {
                requireHttps(m.poster(), "视频封面地址");
            }
            if (m.type() == MediaKind.VIDEO) {
                videos++;
            }
        }
        if (videos > MAX_VIDEOS) {
            throw new BusinessException(400, "视频附件最多 " + MAX_VIDEOS + " 个");
        }
    }

    private static void requireHttps(String url, String label) {
        if (!url.startsWith("https://")) {
            throw new BusinessException(400, label + "必须为 https 地址");
        }
    }
}
