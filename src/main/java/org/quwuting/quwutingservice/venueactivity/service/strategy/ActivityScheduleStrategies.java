package org.quwuting.quwutingservice.venueactivity.service.strategy;

import org.quwuting.quwutingservice.venueactivity.enums.ActivityOuterSchedule;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 外层调度策略注册表 —— 按枚举值索引，**启动期做完整性自检**。
 * <p>
 * 自检的必要性：漏实现一个策略值时，代码能编译、能启动，只在"用户恰好遇到那种
 * 活动"时才 NPE 或静默返回错误状态——这与项目 V13/V14「tinijnt × Integer 在启动期
 * 炸掉好过运行期」同一取向：配置类错误必须在启动期暴露。
 * <p>
 * 新增形态时只需新增一个 {@code @Component} 实现类，本类零改动。
 */
@Component
public class ActivityScheduleStrategies {

    private final Map<ActivityOuterSchedule, ActivityScheduleStrategy> byType;

    public ActivityScheduleStrategies(List<ActivityScheduleStrategy> strategies) {
        EnumMap<ActivityOuterSchedule, ActivityScheduleStrategy> map =
                new EnumMap<>(ActivityOuterSchedule.class);
        for (ActivityScheduleStrategy strategy : strategies) {
            ActivityScheduleStrategy previous = map.put(strategy.type(), strategy);
            if (previous != null) {
                throw new IllegalStateException(
                        "调度策略重复注册：" + strategy.type() + " → "
                                + previous.getClass().getSimpleName() + " / "
                                + strategy.getClass().getSimpleName());
            }
        }
        for (ActivityOuterSchedule type : ActivityOuterSchedule.values()) {
            if (!map.containsKey(type)) {
                throw new IllegalStateException("调度策略缺失实现：ActivityOuterSchedule." + type);
            }
        }
        this.byType = Collections.unmodifiableMap(map);
    }

    public ActivityScheduleStrategy of(ActivityOuterSchedule type) {
        ActivityScheduleStrategy strategy = byType.get(type);
        if (strategy == null) {
            // 理论上不可达（构造期已校验）；保留以防反序列化出未知枚举
            throw new IllegalStateException("未知调度类型：" + type);
        }
        return strategy;
    }
}
