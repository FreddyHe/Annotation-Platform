package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.entity.Project;
import com.annotation.platform.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/test")
@RequiredArgsConstructor
public class TestController {

    private final ProjectRepository projectRepository;

    /**
     * 测试自动标注流程（使用 Mock 数据）
     */
    @GetMapping("/auto-annotation/{projectId}")
    public Result<String> testAutoAnnotation(@PathVariable Long projectId) {
        log.info("Testing auto annotation flow: projectId={}", projectId);
        
        try {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found: " + projectId));
            
            log.info("Project found: {}, labels={}", project.getName(), project.getLabels());
            
            // 这里可以添加 Mock 数据生成逻辑
            // 实际部署时应该调用 autoAnnotationService.startAutoAnnotation(projectId)
            
            log.info("Auto annotation test completed for project: {}", projectId);
            
            return Result.success("Auto annotation test completed");
            
        } catch (Exception e) {
            log.error("Auto annotation test failed: {}", e.getMessage(), e);
            return Result.error("Test failed: " + e.getMessage());
        }
    }
}
