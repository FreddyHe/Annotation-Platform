package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.request.algorithm.RunDinoDetectionRequest;
import com.annotation.platform.dto.request.algorithm.RunVlmCleaningRequest;
import com.annotation.platform.dto.response.algorithm.TaskStatusResponse;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.User;
import com.annotation.platform.repository.ProjectRepository;
import com.annotation.platform.repository.UserRepository;
import com.annotation.platform.service.labelstudio.LabelStudioProxyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/label-studio")
@RequiredArgsConstructor
public class LabelStudioController {

    private final LabelStudioProxyService labelStudioProxyService;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;

    @GetMapping("/login-url")
    public Result<String> getLoginUrl(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String returnUrl,
            HttpServletRequest request) {

        Long userId = (Long) request.getAttribute("userId");
        if ((returnUrl == null || returnUrl.isBlank()) && projectId != null) {
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project != null && project.getLsProjectId() != null) {
                returnUrl = "/projects/" + project.getLsProjectId() + "/data";
            }
        }
        String loginUrl = labelStudioProxyService.getLoginUrl(userId, returnUrl);
        return Result.success(loginUrl);
    }

    @PostMapping("/sync-user")
    public Result<Void> syncUser(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        User user = userRepository.findById(userId).orElseThrow();
        labelStudioProxyService.syncUserToLS(user, null);
        return Result.success();
    }

    @PostMapping("/sync-project/{projectId}")
    public Result<Void> syncProject(@PathVariable Long projectId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        Project project = projectRepository.findById(projectId).orElseThrow();
        labelStudioProxyService.syncProjectToLS(project, userId);
        return Result.success();
    }

    @GetMapping("/user-info")
    public Result<com.annotation.platform.dto.response.auth.LoginResponse.UserInfo> getUserInfo(
            @RequestParam String lsToken) {

        var userInfo = labelStudioProxyService.getUserInfo(lsToken);
        return Result.success(userInfo);
    }
}
