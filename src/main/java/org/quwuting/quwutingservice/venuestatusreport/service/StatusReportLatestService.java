package org.quwuting.quwutingservice.venuestatusreport.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.venuestatusreport.entity.VenueStatusReport;
import org.quwuting.quwutingservice.venuestatusreport.enums.AdminAction;
import org.quwuting.quwutingservice.venuestatusreport.repository.StatusReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店报告「最新上报」行文案批量生成服务（2026-09-04，docs/agents/07-feedback-and-reporting.md）。
 * <p>
 * 职责：为列表接口（{@code VenueService.listVenues} / {@code FavoriteService.getFavoriteVenues}）
 * 批量生成每店<b>最新一条公示中报告</b>的行文案——用户在详情页上报的<b>门店报告</b>
 * （突发事件，{@code qwt_venue_status_reports}）公示期（2 天）内有效时，列表卡片
 * 「最新上报」行与今晚热度文案（{@code crowdLatestText}「2 分钟前 · 资深舞友上报」）
 * <b>共用同一行控件轮播展示</b>（2026-09-04 用户拍板：双信号并存时每 4 秒上下滚动切换，
 * 推翻同日「中性角标」方案——角标丢失「谁/何时/什么事」的动态信息，行文案信息量完整）。
 * <p>
 * <b>为什么是独立微服务而非挂在 {@link StatusReportService} 上（依赖方向约束）</b>：
 * StatusReportService 依赖 VenueService（采纳联动 markSuspendedByReport / reopenByReport），
 * 而 VenueService 列表流程需要本能力——若 VenueService 反向注入 StatusReportService 即构成
 * 构造器循环（Spring Boot 拒绝启动）。本服务只依赖 {@link StatusReportRepository}（叶子依赖），
 * 可被 VenueService / FavoriteService 安全注入；打破循环的既有手段 @Lazy 需要显式构造器，
 * 与全库 Lombok {@code @RequiredArgsConstructor} 惯例冲突，故拆微服务（对齐
 * CrowdReportService#latestTextsByVenue 的「列表行文案批量生成」契约形态）。
 * <p>
 * <b>展示决策（2026-09-04 用户拍板共用行后的口径收敛）</b>：
 * <ul>
 *   <li><b>文案 = 「{相对时间} · {类型} · 舞友上报」</b>（如「2 分钟前 · 暂停营业 ·
 *       舞友上报」）：类型词是本信号的信息本体（不带类型词则与热度行无法区分、
 *       信号价值归零），分隔符与热度行「 · 」同族；「已核实」（ADOPTED）标记与
 *       严重级色点不上列表行——留详情页公告条完整呈现（注意力入口 vs 决策信息的
 *       分层不变）；</li>
 *   <li><b>口径与详情页公告条同源</b>：公示中 = {@code deleted=false OR
 *       adminAction='ADOPTED'} 且 expires_at &gt; now（<b>采纳也置 deleted=true</b>，
 *       谓词必须带 ADOPTED 分支，否则漏「已核实」报告）；REMOVED 不展示——
 *       列表行有文案 ⇔ 详情页有公告条，禁止两处口径漂移；</li>
 *   <li><b>上报者不点名不分级</b>：统一「舞友」（信任分档 badgeFor 是热度上报域的
 *       上报者画像，门店报告域不重复建设；列表公共面不公开昵称，同热度行决策）；</li>
 *   <li><b>不缓存</b>：单次 IN + 子查询覆盖整页，开销小于缓存失效链路复杂度（门店报告
 *       频度远低于热度上报，对齐 2026-09-03 赞数「不进 Caffeine」先例）；报告写路径
 *       （提交/撤销/处置）无需任何失效动作，列表恒实时。</li>
 * </ul>
 * 文案服务端权威，前端零拼接（WXML 直绑 statusLatestText，null 不渲染）。
 */
@Service
@RequiredArgsConstructor
public class StatusReportLatestService {

    private final StatusReportRepository statusReportRepository;

    /**
     * 批量生成每店最新上报行文案（整页一次 IN 查询，防 N+1）。
     *
     * @param venueIds 整页门店 id 集合（空集合安全，返回空 map）
     * @return venueId → 行文案「{相对时间} · {类型} · 舞友上报」；公示期内无门店报告的
     *         门店不在 map（前端 null 不渲染，同 CrowdReportService#latestTextsByVenue 契约）
     */
    @Transactional(readOnly = true)
    public Map<Long, String> latestTextsByVenue(Collection<Long> venueIds) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Map.of();
        }
        List<VenueStatusReport> rows = statusReportRepository.findLatestActiveByVenueIds(
                venueIds, LocalDateTime.now(), AdminAction.ADOPTED);
        Map<Long, String> texts = new HashMap<>();
        for (VenueStatusReport r : rows) {
            // 同店同刻多条并列（理论罕见，子查询等值匹配）→ 每店只取首条兜底
            if (texts.containsKey(r.getVenueId())) {
                continue;
            }
            texts.put(r.getVenueId(),
                    ageTextFor(r.getCreatedAt()) + " · " + r.getType().getDisplayName() + " · 舞友上报");
        }
        return texts;
    }

    /**
     * 相对时间（「刚刚 / N 分钟前 / N 小时前 / N 天前」）——服务端权威，前端零拼接。
     * 与 CrowdReportService#ageTextFor 同族；多一档「天」：门店报告公示期 2 天，
     * 列表行会出现最长约 48 小时的信号（热度上报 6h 窗口用不到天档）。
     */
    private String ageTextFor(LocalDateTime at) {
        if (at == null) {
            return "";
        }
        long minutes = Duration.between(at, LocalDateTime.now()).toMinutes();
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        if (minutes < 60 * 24) {
            return (minutes / 60) + " 小时前";
        }
        return (minutes / (60 * 24)) + " 天前";
    }
}
