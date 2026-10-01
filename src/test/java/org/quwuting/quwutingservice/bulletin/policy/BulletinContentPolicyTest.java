package org.quwuting.quwutingservice.bulletin.policy;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.exception.BusinessException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快讯内容红线（2026-10-01）：加载真实词表资源，双向断言——
 * 越线样例必须被拦（原因 / 事件 / 负面评价），正常可得性文案必须放行（不误伤日常发布）；
 * 开关关闭（默认）时一律放行。
 */
class BulletinContentPolicyTest {

    private final BulletinContentPolicy policy = new BulletinContentPolicy(
            BulletinContentPolicy.loadTerms(BulletinContentPolicy.TERMS_RESOURCE), () -> true);

    @Test
    void rejectsCauseAndIncidentNarratives() {
        for (String text : List.of(
                "寻梦缘舞厅因被查暂停营业",
                "XX 歌舞厅今晚停业整顿，具体恢复时间待定",
                "听说老板跑路了，大家别去了",
                "昨晚有人打架，派出所来了",
                "该店拖欠舞伴工资被投诉")) {
            BusinessException ex = assertThrows(BusinessException.class, () -> policy.check(text), text);
            assertEquals(BulletinContentPolicy.CODE_CONTENT_REDLINE, ex.getCode());
            assertTrue(ex.getMessage().contains("命中「"), "错误消息必须点名命中词，方便作者改写：" + ex.getMessage());
        }
    }

    @Test
    void allowsPlainAvailabilityAnnouncements() {
        for (String text : List.of(
                "南通 · 寻梦缘舞厅 今晚 19:00-22:30 正常营业",
                "**星海舞厅** 本周六暂停营业一天，周日恢复",
                "国庆期间营业时间调整为 13:00-17:00 / 19:00-22:00",
                "金色年华 下午场取消，晚场照常",
                "新店「紫罗兰」10 月 3 日开业，门票 15 元",
                "装修完毕，明日起恢复营业")) {
            assertDoesNotThrow(() -> policy.check(text), text);
        }
    }

    @Test
    void disabledSwitchLetsEverythingThrough() {
        BulletinContentPolicy off = new BulletinContentPolicy(
                BulletinContentPolicy.loadTerms(BulletinContentPolicy.TERMS_RESOURCE), () -> false);
        assertDoesNotThrow(() -> off.check("寻梦缘舞厅因被查暂停营业"));
    }

    @Test
    void matchingIsCaseInsensitiveAndReportsEachTermOnce() {
        List<String> hits = new BulletinContentPolicy(List.of("abc", "被查"), () -> true).findHits("ABC 被查 abc 被查");
        assertEquals(List.of("abc", "被查"), hits);
    }

    @Test
    void emptyTermListFailsFast() {
        assertThrows(IllegalStateException.class, () -> new BulletinContentPolicy(List.of(), () -> true));
    }

    @Test
    void bundledTermListIsNonTrivial() {
        assertTrue(BulletinContentPolicy.loadTerms(BulletinContentPolicy.TERMS_RESOURCE).size() >= 30,
                "词表资源过小，疑似加载了错误文件或被意外清空");
    }
}
