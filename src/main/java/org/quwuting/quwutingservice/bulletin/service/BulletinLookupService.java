package org.quwuting.quwutingservice.bulletin.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.announcement.entity.Announcement;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementStatus;
import org.quwuting.quwutingservice.announcement.repository.AnnouncementRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 快讯可见性/存在性查询（2026-09-10 抽离，docs/agents/47-bulletins.md）。
 * <p>
 * <b>根因（为什么需要这个类）</b>：表态域（{@link BulletinReactionService}）在写入前必须
 * 校验"这条快讯对用户可见"，而可见性规则（FLASH + PUBLISHED + 已生效 + 未软删）原本写在
 * {@link BulletinService} 的私有方法里。若让表态服务反过来依赖 BulletinService、而
 * BulletinService 又依赖表态服务（列表要下发表情），就形成<b>循环依赖</b>——
 * 生产级解法不是 {@code @Lazy} 打补丁，而是把"领域可见性规则"抽成下层共享组件，
 * 两侧都依赖它（同门店域 {@code VenueLookupService} 先例）。
 * <p>
 * 因此本类是快讯可见性规则的<b>唯一事实源</b>：读接口（列表/详情）、写接口（表态）、
 * 管理端（任意状态）都从这里取条目，任何一条可见性规则变更只改一处。
 */
@Service
@RequiredArgsConstructor
public class BulletinLookupService {

    /**
     * 快讯固定分类（领域常量唯一事实源）：接口不接受调用方指定 category——避免从快讯域
     * 写入公告内容或反向操作（公告域同样拒绝 FLASH，见 AnnouncementService#rejectFlashCategory）。
     */
    public static final AnnouncementCategory CATEGORY = AnnouncementCategory.FLASH;

    private final AnnouncementRepository announcementRepository;

    /**
     * 用户端可见性校验：FLASH + PUBLISHED + 已生效 + 未软删，否则 404。
     * 表态、详情、列表过滤共用同一判据——未发布/已下线的快讯不可被表态。
     */
    @Transactional(readOnly = true)
    public Announcement requirePublished(Long id) {
        Announcement a = announcementRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(404, "快讯不存在或已下线"));
        if (a.getCategory() != CATEGORY
                || a.getStatus() != AnnouncementStatus.PUBLISHED
                || (a.getPublishAt() != null && a.getPublishAt().isAfter(LocalDateTime.now()))) {
            throw new BusinessException(404, "快讯不存在或已下线");
        }
        return a;
    }

    /** 管理端存在性校验：FLASH + 未软删（任意状态），否则 404（防跨域读写公告条目） */
    @Transactional(readOnly = true)
    public Announcement requireAny(Long id) {
        Announcement a = announcementRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(404, "快讯不存在"));
        if (a.getCategory() != CATEGORY) {
            throw new BusinessException(404, "快讯不存在");
        }
        return a;
    }
}
