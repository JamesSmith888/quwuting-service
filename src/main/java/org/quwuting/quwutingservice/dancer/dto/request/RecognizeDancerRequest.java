package org.quwuting.quwutingservice.dancer.dto.request;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 认可舞伴请求体（POST /dancers/{id}/recognitions，toggle 语义）。
 * <p>
 * <b>2026-08-15 交互模型变更：单标签换票</b>——认可交互从「标签选择器确认（0-3 个标签）」
 * 改造为 Reaction 风格的表情 chip 单票（对齐 venue Reaction 的每日一票/换票语义，见
 * {@code DancerService#toggleRecognize}）。新客户端只传 <b>tag</b>（单个字典标签 =
 * 今日唯一票）：今日未认可 → 参与；今日同标签 → 取消；今日异标签 → 换票（原子替换）。
 * <p>
 * <b>tags（旧客户端兼容）</b>：保留原 0-3 个列表语义——tag 字段缺省时回退此路径：
 * 今日未认可 → 参与并写入列表标签；今日已认可 → 取消（级联删除当日标签）。
 * 两字段同时下发时以 tag（新模型）为准。
 */
public record RecognizeDancerRequest(
        @Size(max = 10, message = "标签数量过多")
        List<String> tags,
        /** 单个字典标签（新模型：表情 chip 单票；缺省 = 旧 tags 列表语义） */
        String tag
) {}
