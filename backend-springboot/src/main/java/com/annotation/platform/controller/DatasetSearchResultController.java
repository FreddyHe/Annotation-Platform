package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.request.feasibility.CreateDatasetSearchRequest;
import com.annotation.platform.dto.response.feasibility.DatasetSearchResultResponse;
import com.annotation.platform.service.DatasetSearchResultService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/feasibility/assessments/{assessmentId}/datasets")
@RequiredArgsConstructor
public class DatasetSearchResultController {

    private final DatasetSearchResultService datasetSearchResultService;

    @PostMapping
    public Result<DatasetSearchResultResponse> create(
            @PathVariable Long assessmentId,
            @Valid @RequestBody CreateDatasetSearchRequest request) {
        log.info("创建数据集搜索结果: assessmentId={}, datasetName={}", assessmentId, request.getDatasetName());
        DatasetSearchResultResponse response = datasetSearchResultService.create(assessmentId, request);
        return Result.success(response);
    }

    @PostMapping("/batch")
    public Result<List<DatasetSearchResultResponse>> batchCreate(
            @PathVariable Long assessmentId,
            @Valid @RequestBody List<CreateDatasetSearchRequest> requests) {
        log.info("批量创建数据集搜索结果: assessmentId={}, count={}", assessmentId, requests != null ? requests.size() : 0);
        List<DatasetSearchResultResponse> responses = datasetSearchResultService.batchCreate(assessmentId, requests);
        return Result.success(responses);
    }

    @GetMapping
    public Result<List<DatasetSearchResultResponse>> list(
            @PathVariable Long assessmentId,
            @RequestParam(required = false) String categoryName,
            @RequestParam(required = false) String source) {
        log.info("查询数据集搜索结果: assessmentId={}, categoryName={}, source={}", assessmentId, categoryName, source);
        List<DatasetSearchResultResponse> responses = datasetSearchResultService.list(assessmentId, categoryName, source);
        return Result.success(responses);
    }

    @GetMapping("/{id}")
    public Result<DatasetSearchResultResponse> get(@PathVariable Long id) {
        log.info("查询数据集搜索结果详情: id={}", id);
        DatasetSearchResultResponse response = datasetSearchResultService.getById(id);
        return Result.success(response);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        log.info("删除数据集搜索结果: id={}", id);
        datasetSearchResultService.delete(id);
        return Result.success();
    }
}
