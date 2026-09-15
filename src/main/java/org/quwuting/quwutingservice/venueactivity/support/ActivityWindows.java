package org.quwuting.quwutingservice.venueactivity.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.venueactivity.dto.ActivityWindow;
import org.quwuting.quwutingservice.venueactivity.entity.VenueActivity;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 活动内层生效窗口的**读写与筛选单点**。
 * <p>
 * 为什么内层不做成策略类：{@code windows}（可空=全天）+ {@code weekdayMask}
 * （可空=每天）两个字段的组合语义已经覆盖「全时段 / 每日时段 / 每周固定日 /
 * 每周固定日的指定时段」四种形态——它们是同一件事的不同取值，不是不同算法。
 * 策略模式只给**外层有效期**（见 {@code ActivityOuterSchedule}）。
 * <p>
 * 注入 Spring 容器里那一个 {@link ObjectMapper}（与 {@code VenueResponseMapper}
 * 同一做法），不自建实例：项目用的是 Jackson 3（{@code tools.jackson.*}），
 * 自建 mapper 会绕过应用级配置（命名策略、时间序列化开关），
 * 而"同一个 JSON 在入库与出库时行为不同"是最难排查的一类问题。
 * <p>
 * <b>宽容读</b>：解析失败一律回落空列表（= 全天生效），不抛异常。判据是
 * 「窗口是运营填写的数据，脏数据最坏的结果应该是"这条活动不好用"，
 * 而不是"整个门店详情页打不开"」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityWindows {

    private static final TypeReference<List<ActivityWindow>> WINDOW_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    // ── 时段列表读写 ─────────────────────────────────────────

    /** 解析时段 JSON；空 / 非法 / 元素缺时间 → 丢弃该元素（返回可能更短的列表） */
    public List<ActivityWindow> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ActivityWindow> parsed = objectMapper.readValue(json, WINDOW_LIST);
            List<ActivityWindow> valid = new ArrayList<>();
            for (ActivityWindow window : parsed) {
                if (window != null && window.open() != null && window.close() != null) {
                    valid.add(window);
                }
            }
            return valid;
        } catch (Exception e) {
            // 宽容读：脏窗口数据只让"这条活动不好用"，不能崩掉整个详情页
            log.warn("[venue-activity] 生效时段 JSON 解析失败，按全天处理：{}", e.getMessage());
            return List.of();
        }
    }

    /** 序列化时段列表（空列表 → null，让"不设窗口 = 全天"在库里是 NULL 而不是 "[]"） */
    public String write(List<ActivityWindow> windows) {
        if (windows == null || windows.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(windows);
    }

    // ── 星期掩码（纯函数，无 mapper 依赖，保持静态） ────────────

    /** 解析星期掩码 CSV（ISO 1=周一 … 7=周日）；空 / 非法 → 空集合（= 每天） */
    public static Set<Integer> parseWeekdayMask(String mask) {
        if (mask == null || mask.isBlank()) {
            return Set.of();
        }
        Set<Integer> days = new LinkedHashSet<>();
        for (String part : mask.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                int value = Integer.parseInt(token);
                if (value >= 1 && value <= 7) {
                    days.add(value);
                }
            } catch (NumberFormatException ignored) {
                // 单值非法即跳过，不影响其余位
            }
        }
        return days;
    }

    public static String writeWeekdayMask(Set<Integer> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        List<Integer> sorted = new ArrayList<>(days);
        sorted.sort(Comparator.naturalOrder());
        StringBuilder sb = new StringBuilder();
        for (Integer day : sorted) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(day);
        }
        return sb.toString();
    }

    /** 该日期是否落在星期掩码内（空掩码 = 每天成立） */
    public static boolean matchesWeekday(String mask, LocalDate date) {
        Set<Integer> days = parseWeekdayMask(mask);
        if (days.isEmpty()) {
            return true;
        }
        return days.contains(toIsoDayOfWeek(date));
    }

    /** ISO 序号：1=周一 … 7=周日（与 {@link DayOfWeek#getValue()} 同口径） */
    public static int toIsoDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue();
    }

    /** 便捷构造（单时段场景） */
    public static ActivityWindow of(String name, String open, String close) {
        return new ActivityWindow(name, LocalTime.parse(open), LocalTime.parse(close));
    }

    // ── 组合语义 ────────────────────────────────────────────

    /**
     * 指定日期上**实际生效**的时段列表（已按星期掩码过滤、并按开始时间排序）。
     * <p>
     * ⚠️ 返回空列表有两种含义，调用方必须先判 {@link #matchesWeekday}：
     * <b>该日不生效</b>（星期不匹配）与 <b>该日全天生效</b>（本就没设窗口）。
     * 本方法对后者的返回同样是空列表，所以调用方拿到空列表时应理解为
     * "全天生效"，不要自行再判一次。
     */
    public List<ActivityWindow> windowsOn(VenueActivity activity, LocalDate date) {
        if (!matchesWeekday(activity.getWeekdayMask(), date)) {
            return List.of();
        }
        List<ActivityWindow> list = new ArrayList<>(parse(activity.getWindows()));
        list.sort(Comparator.comparing(ActivityWindow::open));
        return list;
    }

    /** 展示文案：多时段用 " · " 连接（如 "13:00-13:45 · 18:00-19:00"）；无窗口 → null */
    public String displayText(String json) {
        List<ActivityWindow> list = parse(json);
        if (list.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ActivityWindow window : list) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(window.displayText());
        }
        return sb.toString();
    }
}
