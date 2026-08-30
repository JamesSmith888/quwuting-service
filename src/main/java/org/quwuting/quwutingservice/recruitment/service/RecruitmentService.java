package org.quwuting.quwutingservice.recruitment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.recruitment.dto.request.RecruitmentSaveRequest;
import org.quwuting.quwutingservice.recruitment.dto.response.AdminRecruitmentItem;
import org.quwuting.quwutingservice.recruitment.dto.response.RecruitmentContactResponse;
import org.quwuting.quwutingservice.recruitment.dto.response.RecruitmentDetail;
import org.quwuting.quwutingservice.recruitment.dto.response.RecruitmentListItem;
import org.quwuting.quwutingservice.recruitment.entity.Recruitment;
import org.quwuting.quwutingservice.recruitment.enums.RecruitGenderLimit;
import org.quwuting.quwutingservice.recruitment.enums.RecruitPosition;
import org.quwuting.quwutingservice.recruitment.enums.RecruitSalaryType;
import org.quwuting.quwutingservice.recruitment.enums.RecruitStatus;
import org.quwuting.quwutingservice.recruitment.enums.RecruitTerm;
import org.quwuting.quwutingservice.recruitment.repository.RecruitmentContactFetchRepository;
import org.quwuting.quwutingservice.recruitment.repository.RecruitmentRepository;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 门店招工服务（2026-08-29，docs/agents/28-recruitments.md）。
 * <p>
 * 写入唯一通道 = 管理员（P0 无用户 UGC）；发布前置校验 = 联系方式至少其一 +
 * 风险词扫描（押金/培训费类诈骗特征与「+威私聊进群」类导流话术，命中需
 * confirmed 二次确认，code 1010）。过期 = 查询谓词硬过滤，不做状态落库，
 * 管理端可一键续期。展示文案（性别/年龄/薪资/人数/待遇）服务端权威派生，
 * 前端零拼接。联系方式真实值仅 fetchContact 按需下发并幂等留痕。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecruitmentService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_EXPIRES_DAYS = 30;
    private static final int RENEW_DAYS = 30;

    /** 发布风险词：押金/培训费类诈骗特征 + 站外导流话术（对标真实样例「+威 私聊进群」） */
    private static final List<String> RISK_WORDS = List.of(
            "押金", "保证金", "培训费", "服装费", "办卡", "充值", "返费", "介绍费", "进群", "私聊");

    private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9+\\-]{5,20}$");
    private static final Pattern WECHAT_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]{4,64}$");

    private final RecruitmentRepository recruitmentRepository;
    private final RecruitmentContactFetchRepository contactFetchRepository;
    private final VenueRepository venueRepository;
    private final ObjectMapper objectMapper;

    // ===== 用户侧 =====

    /**
     * 用户侧列表：PUBLISHED 且未过期且门店未软删，急聘置顶 + 发布时间倒序。
     * city / venueId 均可选（venueId 由门店详情入口行带入）。
     */
    public Page<RecruitmentListItem> list(String city, Long venueId, int page, int size) {
        page = Math.max(0, page);
        size = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        String filterCity = (city == null || city.isBlank()) ? null : city.trim();
        Page<Recruitment> result = recruitmentRepository.findPublished(
                RecruitStatus.PUBLISHED, LocalDateTime.now(), filterCity, venueId,
                PageRequest.of(page, size, Sort.by(
                        Sort.Order.desc("urgent"),
                        Sort.Order.desc("publishedAt"),
                        Sort.Order.desc("id"))));
        Map<Long, Venue> venues = loadVenueMap(result.getContent());
        return result.map(r -> toListItem(r, venues.get(r.getVenueId())));
    }

    /**
     * 用户侧详情：联系方式恒不下发，仅 hasContact 驱动入口。
     */
    public RecruitmentDetail detail(Long id) {
        Recruitment r = getVisible(id);
        Venue venue = loadVenue(r.getVenueId());
        return toDetail(r, venue);
    }

    /**
     * 获取联系方式（需登录）：幂等一记留痕后实时返回真实值。
     */
    @Transactional
    public RecruitmentContactResponse fetchContact(Long id) {
        Long userId = UserContext.requireAuth();
        Recruitment r = getVisible(id);
        contactFetchRepository.insertFetchIfAbsent(r.getId(), userId);
        return new RecruitmentContactResponse(r.getContactName(), r.getContactPhone(), r.getContactWechat());
    }

    // ===== 管理端 =====

    /**
     * 管理端列表：expired=true 时为「已过期」视图（PUBLISHED 且已过有效期，待续期/下架决策）。
     */
    public Page<AdminRecruitmentItem> adminList(RecruitStatus status, Long venueId, String keyword,
                                                Boolean expired, int page, int size) {
        page = Math.max(0, page);
        size = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Recruitment> result;
        if (Boolean.TRUE.equals(expired)) {
            result = recruitmentRepository.adminExpired(RecruitStatus.PUBLISHED, LocalDateTime.now(), venueId, kw, pageRequest);
        } else {
            result = recruitmentRepository.adminSearch(status, venueId, kw, pageRequest);
        }
        Map<Long, Venue> venues = loadVenueMap(result.getContent());
        Map<Long, Long> fetchCounts = loadFetchCounts(
                result.getContent().stream().map(Recruitment::getId).toList());
        return result.map(r -> toAdminItem(r, venues.get(r.getVenueId()),
                fetchCounts.getOrDefault(r.getId(), 0L)));
    }

    /**
     * 管理端详情（编辑页回显）。
     */
    public AdminRecruitmentItem adminDetail(Long id) {
        Recruitment r = recruitmentRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(1001, "招工信息不存在"));
        return toAdminItem(r, loadVenue(r.getVenueId()), loadFetchCounts(List.of(r.getId()))
                .getOrDefault(r.getId(), 0L));
    }

    /**
     * 管理员创建（落草稿，有效期默认 30 天；发布走 /publish）。
     */
    @Transactional
    public AdminRecruitmentItem create(Long adminId, RecruitmentSaveRequest request) {
        Recruitment r = new Recruitment();
        applyFields(r, request);
        r.setCreatedBy(adminId);
        r.setStatus(RecruitStatus.DRAFT);
        int days = request.expiresInDays() != null ? request.expiresInDays() : DEFAULT_EXPIRES_DAYS;
        r.setExpiresAt(LocalDateTime.now().plusDays(days));
        return toAdminItem(recruitmentRepository.save(r), loadVenue(r.getVenueId()), 0L);
    }

    /**
     * 管理员编辑（全量覆盖；有效期不经此接口修改，走 /renew）。
     */
    @Transactional
    public AdminRecruitmentItem update(Long id, RecruitmentSaveRequest request) {
        Recruitment r = recruitmentRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(1001, "招工信息不存在"));
        applyFields(r, request);
        return toAdminItem(recruitmentRepository.save(r), loadVenue(r.getVenueId()), 0L);
    }

    /**
     * 发布（DRAFT/OFFLINE → PUBLISHED；已发布态重复调用 = 刷新发布时间）。
     * 前置校验：联系方式至少其一；风险词命中且未确认 → 1010（message 携带命中词）。
     */
    @Transactional
    public AdminRecruitmentItem publish(Long id, boolean confirmed) {
        Recruitment r = recruitmentRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(1001, "招工信息不存在"));
        if (!hasAnyContact(r)) {
            throw new BusinessException(1001, "发布前请至少填写电话或微信号");
        }
        if (r.getStatus() != RecruitStatus.PUBLISHED && !confirmed) {
            List<String> hits = scanRiskWords(r);
            if (!hits.isEmpty()) {
                throw new BusinessException(1010, "内容含风险词：" + String.join("、", hits)
                        + "。请核实无违规后确认发布");
            }
        }
        r.setStatus(RecruitStatus.PUBLISHED);
        r.setPublishedAt(LocalDateTime.now());
        Recruitment saved = recruitmentRepository.save(r);
        return toAdminItem(saved, loadVenue(saved.getVenueId()),
                loadFetchCounts(List.of(saved.getId())).getOrDefault(saved.getId(), 0L));
    }

    /**
     * 手动下架（PUBLISHED → OFFLINE，可重新发布）。
     */
    @Transactional
    public AdminRecruitmentItem offline(Long id) {
        Recruitment r = recruitmentRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(1001, "招工信息不存在"));
        r.setStatus(RecruitStatus.OFFLINE);
        Recruitment saved = recruitmentRepository.save(r);
        return toAdminItem(saved, loadVenue(saved.getVenueId()),
                loadFetchCounts(List.of(saved.getId())).getOrDefault(saved.getId(), 0L));
    }

    /**
     * 一键续期：有效期 = max(now, 当前有效期) + 30 天（已过期从现在起算）。
     */
    @Transactional
    public AdminRecruitmentItem renew(Long id) {
        Recruitment r = recruitmentRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(1001, "招工信息不存在"));
        LocalDateTime base = r.getExpiresAt().isAfter(LocalDateTime.now()) ? r.getExpiresAt() : LocalDateTime.now();
        r.setExpiresAt(base.plusDays(RENEW_DAYS));
        Recruitment saved = recruitmentRepository.save(r);
        return toAdminItem(saved, loadVenue(saved.getVenueId()),
                loadFetchCounts(List.of(saved.getId())).getOrDefault(saved.getId(), 0L));
    }

    // ===== 私有：校验与字段应用 =====

    private void applyFields(Recruitment r, RecruitmentSaveRequest request) {
        Venue venue = venueRepository.findById(request.venueId())
                .filter(v -> !v.isDeleted())
                .orElseThrow(() -> new BusinessException(1001, "关联门店不存在"));

        List<String> positions = request.positions().stream()
                .map(code -> {
                    try {
                        return RecruitPosition.valueOf(code);
                    } catch (IllegalArgumentException e) {
                        throw new BusinessException(1001, "无效的职位类型: " + code);
                    }
                })
                .map(RecruitPosition::name)
                .distinct()
                .toList();

        String salaryText = request.salaryText() == null ? null : request.salaryText().trim();
        String phone = request.contactPhone() == null ? null : request.contactPhone().trim();
        String wechat = request.contactWechat() == null ? null : request.contactWechat().trim();
        if (phone != null && !phone.isEmpty() && !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(1001, "电话格式不正确");
        }
        if (wechat != null && !wechat.isEmpty() && !WECHAT_PATTERN.matcher(wechat).matches()) {
            throw new BusinessException(1001, "微信号格式不正确");
        }
        if (request.ageMin() != null && request.ageMax() != null && request.ageMin() > request.ageMax()) {
            throw new BusinessException(1001, "年龄下限不能大于上限");
        }

        r.setVenueId(venue.getId());
        r.setPositionTypes(serializePositions(positions));
        r.setHeadcount(request.headcount());
        r.setTerm(request.term() == null ? RecruitTerm.LONG_TERM : request.term());
        r.setGenderLimit(request.genderLimit() == null ? RecruitGenderLimit.ANY : request.genderLimit());
        r.setAgeMin(request.ageMin());
        r.setAgeMax(request.ageMax());
        r.setSalaryType(request.salaryType() == null ? RecruitSalaryType.NEGOTIABLE : request.salaryType());
        r.setSalaryText(salaryText);
        r.setAccommodation(request.accommodation());
        r.setTravelPaid(request.travelPaid());
        r.setDescription(request.description().trim());
        r.setContactName(request.contactName() == null ? null : request.contactName().trim());
        r.setContactPhone(phone);
        r.setContactWechat(wechat);
        r.setUrgent(Boolean.TRUE.equals(request.urgent()));
    }

    private boolean hasAnyContact(Recruitment r) {
        return notBlank(r.getContactPhone()) || notBlank(r.getContactWechat());
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private List<String> scanRiskWords(Recruitment r) {
        String text = (r.getDescription() == null ? "" : r.getDescription())
                + (r.getSalaryText() == null ? "" : r.getSalaryText());
        return RISK_WORDS.stream().filter(text::contains).toList();
    }

    // ===== 私有：查询与映射 =====

    private Recruitment getVisible(Long id) {
        Recruitment r = recruitmentRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(1001, "招工信息不存在或已下架"));
        if (r.getStatus() != RecruitStatus.PUBLISHED || !r.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(1001, "招工信息不存在或已下架");
        }
        return r;
    }

    private Venue loadVenue(Long venueId) {
        return venueRepository.findById(venueId).filter(v -> !v.isDeleted()).orElse(null);
    }

    private Map<Long, Venue> loadVenueMap(List<Recruitment> items) {
        List<Long> venueIds = items.stream().map(Recruitment::getVenueId).distinct().toList();
        if (venueIds.isEmpty()) {
            return Map.of();
        }
        return venueRepository.findAllById(venueIds).stream()
                .collect(Collectors.toMap(Venue::getId, Function.identity()));
    }

    private Map<Long, Long> loadFetchCounts(Collection<Long> ids) {
        List<Long> distinctIds = ids.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        return recruitmentRepository.countFetchesByRecruitmentIds(distinctIds).stream()
                .collect(Collectors.toMap(
                        RecruitmentRepository.FetchCount::getRecruitmentid,
                        RecruitmentRepository.FetchCount::getCnt));
    }

    private RecruitmentListItem toListItem(Recruitment r, Venue venue) {
        return new RecruitmentListItem(
                r.getId(),
                r.getVenueId(),
                venue == null ? null : venue.getName(),
                venue == null ? null : venue.getCity(),
                venue == null ? null : venue.getImageUrl(),
                r.isUrgent(),
                positionLabels(r),
                genderText(r),
                ageText(r),
                salaryText(r),
                r.getTerm().getLabel(),
                headcountText(r),
                r.getPublishedAt(),
                r.getExpiresAt());
    }

    private RecruitmentDetail toDetail(Recruitment r, Venue venue) {
        return new RecruitmentDetail(
                r.getId(),
                r.getVenueId(),
                venue == null ? null : venue.getName(),
                venue == null ? null : venue.getCity(),
                venue == null ? null : venue.getDistrict(),
                venue == null ? null : venue.getAddress(),
                venue == null ? null : venue.getImageUrl(),
                r.isUrgent(),
                positionLabels(r),
                genderText(r),
                ageText(r),
                salaryText(r),
                r.getTerm().getLabel(),
                headcountText(r),
                benefitText(r.getAccommodation(), "提供住宿", "不提供住宿"),
                benefitText(r.getTravelPaid(), "报销路费", "不报销路费"),
                r.getDescription(),
                hasAnyContact(r),
                r.getPublishedAt(),
                r.getExpiresAt());
    }

    private AdminRecruitmentItem toAdminItem(Recruitment r, Venue venue, long fetchCount) {
        boolean expired = r.getStatus() == RecruitStatus.PUBLISHED
                && !r.getExpiresAt().isAfter(LocalDateTime.now());
        return new AdminRecruitmentItem(
                r.getId(),
                r.getVenueId(),
                venue == null ? null : venue.getName(),
                deserializePositions(r.getPositionTypes()),
                positionLabels(r),
                r.getHeadcount(),
                r.getTerm().name(),
                r.getGenderLimit().name(),
                r.getAgeMin(),
                r.getAgeMax(),
                r.getSalaryType().name(),
                r.getSalaryText(),
                r.getAccommodation(),
                r.getTravelPaid(),
                r.getDescription(),
                r.getContactName(),
                r.getContactPhone(),
                r.getContactWechat(),
                r.isUrgent(),
                r.getStatus().name(),
                r.getStatus().getLabel(),
                expired,
                fetchCount,
                r.getPublishedAt(),
                r.getExpiresAt(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    private String genderText(Recruitment r) {
        if (r.getGenderLimit() == null || r.getGenderLimit() == RecruitGenderLimit.ANY) {
            return null;
        }
        return "限" + r.getGenderLimit().getLabel();
    }

    private String ageText(Recruitment r) {
        Integer min = r.getAgeMin();
        Integer max = r.getAgeMax();
        if (min != null && max != null) return min + "-" + max + "岁";
        if (min != null) return min + "岁以上";
        if (max != null) return max + "岁以下";
        return null;
    }

    private String salaryText(Recruitment r) {
        return notBlank(r.getSalaryText()) ? r.getSalaryText() : r.getSalaryType().getLabel();
    }

    private String headcountText(Recruitment r) {
        return r.getHeadcount() == null ? null : "招" + r.getHeadcount() + "人";
    }

    private String benefitText(Boolean value, String yesText, String noText) {
        if (value == null) return null;
        return value ? yesText : noText;
    }

    private List<String> positionLabels(Recruitment r) {
        List<String> labels = new ArrayList<>();
        for (String name : deserializePositions(r.getPositionTypes())) {
            try {
                labels.add(RecruitPosition.valueOf(name).getLabel());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown recruit position code: {}", name);
            }
        }
        return labels;
    }

    private String serializePositions(List<String> names) {
        try {
            return objectMapper.writeValueAsString(names);
        } catch (Exception e) {
            log.warn("Failed to serialize positions: {}", names, e);
            throw new BusinessException(1001, "职位序列化失败");
        }
    }

    private List<String> deserializePositions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to deserialize positions: {}", json, e);
            return List.of();
        }
    }
}
