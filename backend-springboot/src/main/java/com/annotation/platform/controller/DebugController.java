package com.annotation.platform.controller;

import com.annotation.platform.common.Result;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.User;
import com.annotation.platform.repository.ProjectRepository;
import com.annotation.platform.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/debug")
@RequiredArgsConstructor
public class DebugController {

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/users")
    @Transactional(readOnly = true)
    public Result<List<Map<String, Object>>> listAllUsers() {
        log.info("调试接口: 列出所有用户及项目信息");
        
        List<User> users = userRepository.findAll();
        
        List<Map<String, Object>> userInfos = users.stream()
            .map(user -> {
                Map<String, Object> info = new HashMap<>();
                info.put("id", user.getId());
                info.put("username", user.getUsername());
                info.put("email", user.getEmail());
                info.put("displayName", user.getDisplayName());
                info.put("isActive", user.getIsActive());
                info.put("createdAt", user.getCreatedAt());
                info.put("lastLogin", user.getLastLogin());
                
                if (user.getOrganization() != null) {
                    Map<String, Object> orgInfo = new HashMap<>();
                    orgInfo.put("id", user.getOrganization().getId());
                    orgInfo.put("name", user.getOrganization().getName());
                    orgInfo.put("displayName", user.getOrganization().getDisplayName());
                    info.put("organization", orgInfo);
                }
                
                List<Project> userProjects = projectRepository.findAll().stream()
                    .filter(p -> p.getCreatedBy() != null && p.getCreatedBy().getId().equals(user.getId()))
                    .collect(Collectors.toList());
                
                info.put("projectCount", userProjects.size());
                
                List<Map<String, Object>> projectInfos = userProjects.stream()
                    .map(project -> {
                        Map<String, Object> pInfo = new HashMap<>();
                        pInfo.put("id", project.getId());
                        pInfo.put("name", project.getName());
                        pInfo.put("status", project.getStatus().name());
                        pInfo.put("totalImages", project.getTotalImages());
                        pInfo.put("processedImages", project.getProcessedImages());
                        pInfo.put("createdAt", project.getCreatedAt());
                        return pInfo;
                    })
                    .collect(Collectors.toList());
                
                info.put("projects", projectInfos);
                
                return info;
            })
            .collect(Collectors.toList());
        
        log.info("找到 {} 个用户", userInfos.size());
        return Result.success(userInfos);
    }

    @PostMapping("/reset-password/{userId}")
    @Transactional
    public Result<String> resetPassword(
            @PathVariable Long userId,
            @RequestParam String newPassword) {
        
        log.info("调试接口: 重置用户密码, userId={}", userId);
        
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
        
        String encodedPassword = passwordEncoder.encode(newPassword);
        user.setPasswordHash(encodedPassword);
        userRepository.save(user);
        
        log.info("密码重置成功: userId={}, username={}", userId, user.getUsername());
        
        return Result.success(String.format(
            "用户 %s (ID: %d) 的密码已重置为: %s", 
            user.getUsername(), userId, newPassword
        ));
    }
}
