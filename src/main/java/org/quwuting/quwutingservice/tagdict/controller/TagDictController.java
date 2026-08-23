package org.quwuting.quwutingservice.tagdict.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.tagdict.dto.response.TagItemResponse;
import org.quwuting.quwutingservice.tagdict.service.TagDictService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签字典公开读接口（2026-08-24）。
 * GET /tag-dict?scope=DANCER — 有效标签字典（编辑页表单可选标签 + 展示侧说明兜底；
 * 列表/详情接口的 profileTags 已自带 description，常规展示无需再拉本接口）。
 */
@RestController
@RequestMapping("/tag-dict")
@RequiredArgsConstructor
public class TagDictController {

    private final TagDictService tagDictService;

    /** 有效标签字典（scope 缺省 = DANCER；非法值回退 DANCER，防空列表） */
    @GetMapping
    public ApiResponse<List<TagItemResponse>> list(
            @RequestParam(defaultValue = "DANCER") String scope) {
        return ApiResponse.ok(tagDictService.listActive(scope));
    }
}
