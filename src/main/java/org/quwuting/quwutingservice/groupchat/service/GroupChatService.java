package org.quwuting.quwutingservice.groupchat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.groupchat.dto.request.GroupChatUpsertRequest;
import org.quwuting.quwutingservice.groupchat.dto.response.GroupChatListResponse;
import org.quwuting.quwutingservice.groupchat.dto.response.GroupChatResponse;
import org.quwuting.quwutingservice.groupchat.entity.GroupChat;
import org.quwuting.quwutingservice.groupchat.enums.GroupChatScope;
import org.quwuting.quwutingservice.groupchat.repository.GroupChatRepository;
import org.quwuting.quwutingservice.storage.ImageContentValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 舞友群服务（V33 新增）。
 * <p>
 * 定位：运营配置的微信群引流内容。公开读（GET /group-chats 分组返回启用群）+ 管理端
 * 增删改查（ADMIN，controller 层 requireAdmin）。二维码 URL 落库统一挂
 * {@link ImageContentValidator}（08-12 安全加固约定——新增图片 URL 落库字段必校验）。
 * <p>
 * 维度一致性校验（scope ↔ city / region 互斥必填）集中在本服务，跨字段业务规则
 * 不散落 DTO——保证管理端任何入口（创建/更新）共享同一套规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupChatService {

    private final GroupChatRepository groupChatRepository;
    private final ImageContentValidator imageValidator;

    /**
     * 公开列表：启用 + 未软删的群，按维度分组（全国 / 城市 / 地域）。
     * 读多写少，由前端会话内缓存承担（后端不加缓存层，保持简单）。
     */
    @Transactional(readOnly = true)
    public GroupChatListResponse listPublic() {
        List<GroupChat> all = groupChatRepository
                .findByDeletedFalseAndEnabledTrueOrderByDisplayOrderAscIdAsc();
        List<GroupChatResponse> nationwide = all.stream()
                .filter(g -> g.getScope() == GroupChatScope.NATIONWIDE)
                .map(this::toResponse).toList();
        List<GroupChatResponse> city = all.stream()
                .filter(g -> g.getScope() == GroupChatScope.CITY)
                .map(this::toResponse).toList();
        List<GroupChatResponse> region = all.stream()
                .filter(g -> g.getScope() == GroupChatScope.REGION)
                .map(this::toResponse).toList();
        return new GroupChatListResponse(nationwide, city, region);
    }

    /** 管理端列表：未软删的全部群（含已下线），运营可查看并上下线 */
    @Transactional(readOnly = true)
    public List<GroupChatResponse> listAdmin() {
        return groupChatRepository.findByDeletedFalseOrderByDisplayOrderAscIdAsc().stream()
                .map(this::toResponse).toList();
    }

    /** 创建群（ADMIN）。二维码 URL 先过内容校验再落库。 */
    @Transactional
    public GroupChatResponse create(GroupChatUpsertRequest req, Long adminId) {
        validateDimension(req);
        imageValidator.validate(req.qrCodeUrl());
        GroupChat chat = new GroupChat();
        apply(chat, req);
        chat.setUpdatedBy(adminId);
        GroupChat saved = groupChatRepository.save(chat);
        log.info("group chat created: id={} name={} scope={} (by user {})",
                saved.getId(), saved.getName(), saved.getScope(), adminId);
        return toResponse(saved);
    }

    /** 更新群（ADMIN，全量覆盖；enabled 由上下线接口独立管理） */
    @Transactional
    public GroupChatResponse update(Long id, GroupChatUpsertRequest req, Long adminId) {
        GroupChat chat = findExisting(id);
        validateDimension(req);
        imageValidator.validate(req.qrCodeUrl());
        apply(chat, req);
        chat.setUpdatedBy(adminId);
        chat.setUpdatedAt(LocalDateTime.now());
        GroupChat saved = groupChatRepository.save(chat);
        log.info("group chat updated: id={} name={} (by user {})", id, saved.getName(), adminId);
        return toResponse(saved);
    }

    /** 上下线切换（ADMIN，乐观翻转：公开读立即不可见/可见） */
    @Transactional
    public GroupChatResponse toggle(Long id, Long adminId) {
        GroupChat chat = findExisting(id);
        chat.setEnabled(!chat.isEnabled());
        chat.setUpdatedBy(adminId);
        chat.setUpdatedAt(LocalDateTime.now());
        GroupChat saved = groupChatRepository.save(chat);
        log.info("group chat toggled: id={} enabled={} (by user {})", id, saved.isEnabled(), adminId);
        return toResponse(saved);
    }

    /** 软删（ADMIN；deleted=true，管理端列表不可见） */
    @Transactional
    public void delete(Long id, Long adminId) {
        GroupChat chat = findExisting(id);
        chat.setDeleted(true);
        chat.setUpdatedBy(adminId);
        chat.setUpdatedAt(LocalDateTime.now());
        groupChatRepository.save(chat);
        log.info("group chat deleted: id={} (by user {})", id, adminId);
    }

    /**
     * 维度一致性校验：scope ↔ city / region 互斥必填——
     * CITY → city 必填、region 禁填；REGION → region 必填、city 禁填；
     * NATIONWIDE → 两者禁填。防管理端配出"前后矛盾/歧义"的群。
     */
    private void validateDimension(GroupChatUpsertRequest req) {
        GroupChatScope scope = req.scope();
        boolean hasCity = isNotBlank(req.city());
        boolean hasRegion = isNotBlank(req.region());
        if (scope == GroupChatScope.CITY) {
            if (!hasCity) throw new BusinessException(1001, "城市群必须填写城市");
            if (hasRegion) throw new BusinessException(1001, "城市群不能填写地域");
        } else if (scope == GroupChatScope.REGION) {
            if (!hasRegion) throw new BusinessException(1001, "地域群必须填写地域");
            if (hasCity) throw new BusinessException(1001, "地域群不能填写城市");
        } else {
            if (hasCity || hasRegion) throw new BusinessException(1001, "全国群不能填写城市或地域");
        }
    }

    /** 请求字段应用到实体（enabled 不在此列——上下线接口独立管理） */
    private void apply(GroupChat chat, GroupChatUpsertRequest req) {
        chat.setName(req.name().trim());
        chat.setScope(req.scope());
        chat.setCity(blankToNull(req.city()));
        chat.setRegion(blankToNull(req.region()));
        chat.setQrCodeUrl(req.qrCodeUrl().trim());
        chat.setDescription(blankToNull(req.description()));
        chat.setDisplayOrder(req.displayOrder() == null ? 0 : req.displayOrder());
    }

    private GroupChat findExisting(Long id) {
        return groupChatRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(1001, "群聊不存在"));
    }

    private GroupChatResponse toResponse(GroupChat chat) {
        return new GroupChatResponse(
                chat.getId(),
                chat.getName(),
                chat.getScope(),
                chat.getScope().getDisplayName(),
                chat.getCity(),
                chat.getRegion(),
                chat.getQrCodeUrl(),
                chat.getDescription(),
                chat.getDisplayOrder(),
                chat.isEnabled(),
                chat.getCreatedAt()
        );
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String blankToNull(String s) {
        return isNotBlank(s) ? s.trim() : null;
    }
}
