package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.request.algorithm.RunDinoDetectionRequest;
import com.annotation.platform.dto.request.algorithm.RunVlmCleaningRequest;
import com.annotation.platform.dto.response.algorithm.TaskStatusResponse;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.User;
import com.annotation.platform.repository.UserRepository;
import com.annotation.platform.service.ProjectAccessService;
import com.annotation.platform.service.labelstudio.LabelStudioProxyService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/label-studio")
@RequiredArgsConstructor
public class LabelStudioController {

    private final LabelStudioProxyService labelStudioProxyService;
    private final UserRepository userRepository;
    private final ProjectAccessService projectAccessService;

    @GetMapping("/login-url")
    public Result<String> getLoginUrl(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String returnUrl,
            HttpServletResponse response,
            HttpServletRequest request) {

        Long userId = (Long) request.getAttribute("userId");
        long startedAt = System.nanoTime();
        Project project = null;
        Map<String, Object> workspace = Map.of();
        Long lsProjectId = null;
        if (projectId != null) {
            project = projectAccessService.requireProjectInCurrentOrg(projectId, request);
            lsProjectId = project.getLsProjectId();

            // 已有 LS 项目时直接建立浏览器会话。完整 storage 同步只在首次创建 LS 项目时执行，
            // 避免用户每次点击入口都等待重复的挂载、同步和 task 清理。
            if (lsProjectId == null) {
                workspace = labelStudioProxyService.prepareProjectReviewWorkspace(project, userId);
                Object workspaceProjectId = workspace.get("lsProjectId");
                if (workspaceProjectId instanceof Number number) {
                    lsProjectId = number.longValue();
                }
            }

        }
        if ((returnUrl == null || returnUrl.isBlank()) && lsProjectId != null) {
            returnUrl = "/projects/" + lsProjectId + "/data";
        }
        Long lsOrganizationId = project != null && project.getOrganization() != null
                ? project.getOrganization().getLsOrgId()
                : null;
        String sessionCookie = labelStudioProxyService.createBrowserSessionCookie(
                userId,
                lsProjectId,
                lsOrganizationId
        );
        if (sessionCookie != null && !sessionCookie.isBlank()) {
            response.addHeader(
                    "Set-Cookie",
                    "sessionid=" + sessionCookie + "; Path=/; Max-Age=1209600; HttpOnly; SameSite=Lax"
            );
        }
        String loginUrl = labelStudioProxyService.getLoginUrl(userId, returnUrl);
        if (!workspace.isEmpty()) {
            log.info("Label Studio 复核工作台准备完成: userId={}, projectId={}, workspace={}",
                    userId, project != null ? project.getId() : projectId, new LinkedHashMap<>(workspace));
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("Label Studio 入口生成完成: userId={}, projectId={}, lsProjectId={}, elapsedMs={}",
                userId, projectId, lsProjectId, elapsedMs);
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
        Project project = projectAccessService.requireProjectInCurrentOrg(projectId, request);
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
