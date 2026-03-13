package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.dto.response.auth.LoginResponse;
import com.annotation.platform.dto.response.user.UserProfileResponse;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.User;
import com.annotation.platform.repository.OrganizationRepository;
import com.annotation.platform.repository.ProjectRepository;
import com.annotation.platform.service.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final ProjectRepository projectRepository;
    private final OrganizationRepository organizationRepository;

    @GetMapping("/me")
    @Transactional
    public Result<LoginResponse.UserInfo> getCurrentUser(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        User user = userService.findById(userId);

        LoginResponse.UserInfo userInfo = LoginResponse.UserInfo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .organization(user.getOrganization() != null ?
                        LoginResponse.OrganizationInfo.builder()
                                .id(user.getOrganization().getId())
                                .name(user.getOrganization().getName())
                                .displayName(user.getOrganization().getDisplayName())
                                .build() : null)
                .build();

        return Result.success(userInfo);
    }

    @GetMapping("/{id}")
    public Result<LoginResponse.UserInfo> getUser(@PathVariable Long id) {
        User user = userService.findById(id);

        LoginResponse.UserInfo userInfo = LoginResponse.UserInfo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .organization(user.getOrganization() != null ?
                        LoginResponse.OrganizationInfo.builder()
                                .id(user.getOrganization().getId())
                                .name(user.getOrganization().getName())
                                .displayName(user.getOrganization().getDisplayName())
                                .build() : null)
                .build();

        return Result.success(userInfo);
    }

    @GetMapping
    public Result<List<LoginResponse.UserInfo>> getUsers(
            @RequestParam(required = false) Long organizationId) {

        List<User> users;
        if (organizationId != null) {
            users = userService.findByOrganizationId(organizationId);
        } else {
            users = userService.findByOrganizationId(organizationId);
        }

        List<LoginResponse.UserInfo> userInfoList = users.stream()
                .map(user -> LoginResponse.UserInfo.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .email(user.getEmail())
                        .displayName(user.getDisplayName())
                        .organization(user.getOrganization() != null ?
                                LoginResponse.OrganizationInfo.builder()
                                        .id(user.getOrganization().getId())
                                        .name(user.getOrganization().getName())
                                        .displayName(user.getOrganization().getDisplayName())
                                        .build() : null)
                        .build())
                .collect(Collectors.toList());

        return Result.success(userInfoList);
    }

    @GetMapping("/me/stats")
    @Transactional
    public Result<Map<String, Object>> getOrganizationStats(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        User user = userService.findById(userId);
        
        if (user.getOrganization() == null) {
            Map<String, Object> emptyStats = new HashMap<>();
            emptyStats.put("totalProjects", 0);
            emptyStats.put("totalImages", 0);
            emptyStats.put("runningTasks", 0);
            emptyStats.put("completedTasks", 0);
            return Result.success(emptyStats);
        }
        
        Long orgId = user.getOrganization().getId();
        
        long totalProjects = projectRepository.countByOrganizationId(orgId);
        
        long totalImages = projectRepository.countImagesByOrganizationId(orgId);
        
        long runningTasks = projectRepository.findByOrganizationIdAndStatus(
                orgId, Project.ProjectStatus.DETECTING, 
                org.springframework.data.domain.Pageable.ofSize(100)
        ).getTotalElements();
        
        runningTasks += projectRepository.findByOrganizationIdAndStatus(
                orgId, Project.ProjectStatus.CLEANING, 
                org.springframework.data.domain.Pageable.ofSize(100)
        ).getTotalElements();
        
        runningTasks += projectRepository.findByOrganizationIdAndStatus(
                orgId, Project.ProjectStatus.SYNCING, 
                org.springframework.data.domain.Pageable.ofSize(100)
        ).getTotalElements();
        
        long completedTasks = projectRepository.findByOrganizationIdAndStatus(
                orgId, Project.ProjectStatus.COMPLETED,
                org.springframework.data.domain.Pageable.ofSize(100)
        ).getTotalElements();
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalProjects", totalProjects);
        stats.put("totalImages", totalImages);
        stats.put("runningTasks", runningTasks);
        stats.put("completedTasks", completedTasks);
        
        return Result.success(stats);
    }

    @GetMapping("/me/profile")
    @Transactional
    public Result<UserProfileResponse> getUserProfile(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        UserProfileResponse profile = userService.getUserProfile(userId);
        return Result.success(profile);
    }
}
