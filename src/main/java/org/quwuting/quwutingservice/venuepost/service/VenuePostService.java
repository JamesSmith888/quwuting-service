package org.quwuting.quwutingservice.venuepost.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.config.CacheConfig;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourcePermission;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourceType;
import org.quwuting.quwutingservice.resourceaccess.service.ResourceAccessService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venuepost.dto.request.CreatePostRequest;
import org.quwuting.quwutingservice.venuepost.dto.response.VenuePostResponse;
import org.quwuting.quwutingservice.venuepost.entity.VenuePost;
import org.quwuting.quwutingservice.venuepost.enums.PostPublisherType;
import org.quwuting.quwutingservice.venuepost.repository.VenuePostRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VenuePostService {

    private static final String PLATFORM_PUBLISHER_NAME = "去舞厅平台";
    private static final int MAX_PAGE_SIZE = 50;

    private final VenuePostRepository venuePostRepository;
    private final VenueRepository venueRepository;
    private final VenueHeatService venueHeatService;
    private final ResourceAccessService resourceAccessService;

    /**
     * 分页查询场所动态（公开接口，按发布时间倒序）。
     */
    @Transactional(readOnly = true)
    public Page<VenuePostResponse> listPosts(Long venueId, int page, int size) {
        assertVenueExists(venueId);
        page = Math.max(0, page);
        size = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return venuePostRepository.findByVenueIdAndDeletedFalse(venueId, pageable)
                .map(this::toResponse);
    }

    /**
     * 发布动态（管理员或门店认领人）。
     * <p>
     * publisherType 由角色自动判定：ADMIN 角色 → 平台公告（publisherName="去舞厅平台"），
     * 认领人 → 商家动态（publisherName=门店名）。客户端不指定发布方身份。
     * <p>
     * 写操作即时逐出两类缓存：近30天新增动态是热度公式输入（newPostCount30d × 5）——热度为
     * VenueHeatService 内嵌 LoadingCache，方法体内显式 invalidate；动态数也参与热门场所
     * 排序（2026-09-01 起同为近30天新增动态 ×5）——hotVenueIds 仍由 Spring CacheManager 托管，
     * 用 @CacheEvict(allEntries) 逐出。
     */
    @CacheEvict(value = CacheConfig.CACHE_HOT_VENUE_IDS, allEntries = true)
    @Transactional
    public VenuePostResponse createPost(Long venueId, CreatePostRequest req) {
        Venue venue = venueRepository.findByIdAndDeletedFalse(venueId)
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));
        resourceAccessService.requireCurrentUser(ResourceType.VENUE, venueId,
            ResourcePermission.VENUE_POST_MANAGE);

        boolean isAdmin = UserContext.getCurrentRole() == UserRole.ADMIN;
        PostPublisherType publisherType = isAdmin ? PostPublisherType.ADMIN : PostPublisherType.OWNER;
        String publisherName = isAdmin ? PLATFORM_PUBLISHER_NAME : venue.getName();

        VenuePost post = new VenuePost();
        post.setVenueId(venueId);
        post.setTitle(req.title().trim());
        post.setContent(req.content().trim());
        post.setPublisherType(publisherType);
        post.setPublisherName(publisherName);
        VenuePostResponse response = toResponse(venuePostRepository.save(post));
        venueHeatService.invalidate(venueId);
        return response;
    }

    private VenuePostResponse toResponse(VenuePost post) {
        return new VenuePostResponse(
                post.getId(),
                post.getVenueId(),
                post.getTitle(),
                post.getContent(),
                post.getPublisherType(),
                post.getPublisherType().getDisplayName(),
                post.getPublisherName(),
                post.getCreatedAt()
        );
    }

    private void assertVenueExists(Long venueId) {
        if (venueRepository.findByIdAndDeletedFalse(venueId).isEmpty()) {
            throw new BusinessException(1001, "场所不存在");
        }
    }
}
