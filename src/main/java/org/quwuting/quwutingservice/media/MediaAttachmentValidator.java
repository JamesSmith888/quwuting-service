package org.quwuting.quwutingservice.media;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.storage.ImageContentValidator;
import org.quwuting.quwutingservice.storage.MediaKind;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 媒体附件<b>内容级</b>校验（2026-09-28）。落实跨仓既有约定："新增图片/视频 URL
 * 落库字段必校验"（GroupChatService / Dancer 注释先例）——结构化 media_json 是
 * 一个新的 URL 落库字段，同样必须挂 {@link ImageContentValidator}，不得例外。
 * <p>
 * 校验分两层，职责刻意分离：
 * <ul>
 *   <li>结构层（{@link MediaAttachments#validateStructure}，纯静态零依赖）：
 *       条数 / 必填 / https / 视频上限；</li>
 *   <li>内容层（本类，Spring Bean）：URL 落在本应用存储白名单内（双 provider
 *       形态并存）→ 图片下载验魔数与宽高、视频白名单 + 扩展名校验（视频可达
 *       50MB，不下载）、封面按图片校验。</li>
 * </ul>
 * <b>外链策略（有意收紧）</b>：仅接受本应用存储白名单内的地址。运营上传入口
 * 已就绪（admin 媒体区块走 {@code FileCategory.OPERATION_MEDIA} 直传），没有理由
 * 再贴外链；外链失效会让附件卡变成死块（比正文外链更糟——正文图挂了还有文字，
 * 附件卡挂了是纯死块）。Agent 通道如需带第三方图，后续独立做"URL 转存"能力
 * （下载 → 传本桶 → 写附件），不属本次范围。
 */
@Service
@RequiredArgsConstructor
public class MediaAttachmentValidator {

    private final ImageContentValidator imageContentValidator;

    /**
     * 全量校验（结构 + 内容），任一失败抛 {@code BusinessException}。
     * create / update / agentPublish 三条写入路径都必须调用（在序列化落库之前）。
     */
    public void validate(List<MediaAttachment> media) {
        MediaAttachments.validateStructure(media);
        if (media == null || media.isEmpty()) {
            return;
        }
        for (MediaAttachment m : media) {
            if (m.type() == MediaKind.VIDEO) {
                imageContentValidator.validateVideoUrl(m.url());
            } else {
                imageContentValidator.validate(m.url());
            }
            if (m.poster() != null) {
                imageContentValidator.validate(m.poster());
            }
        }
    }
}
