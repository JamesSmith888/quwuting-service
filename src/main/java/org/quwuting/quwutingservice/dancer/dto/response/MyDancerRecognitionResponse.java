package org.quwuting.quwutingservice.dancer.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.util.List;

/**
 * 我的认可记录条目（GET /users/me/dancer-recognitions，个人中心"我的认可"页数据源）。
 * <p>
 * 语义：我最近一次对该舞伴的认可（按认可时间倒序，同一舞伴多日认可只展示最近一条——
 * 个人中心是"我认可过谁"的回顾视图，非认可明细流水）。tagCodes 为该条认可携带的
 * 字典标签（无标签时为空列表）。不公开任何其他用户关系。
 */
public record MyDancerRecognitionResponse(
        Long dancerId,
        String nickname,
        String avatarUrl,
        String bio,
        String city,
        /** 常驻舞厅名（取最早一条 HOME 关系；无则 null） */
        String homeVenueName,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate recognizedOn,
        List<String> tagCodes
) {}
