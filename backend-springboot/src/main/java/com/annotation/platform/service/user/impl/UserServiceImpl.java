package com.annotation.platform.service.user.impl;

import com.annotation.platform.common.ErrorCode;
import com.annotation.platform.dto.response.common.PageableResponse;
import com.annotation.platform.dto.response.user.UserProfileResponse;
import com.annotation.platform.entity.Organization;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.User;
import com.annotation.platform.exception.BusinessException;
import com.annotation.platform.exception.ResourceNotFoundException;
import com.annotation.platform.repository.OrganizationRepository;
import com.annotation.platform.repository.ProjectRepository;
import com.annotation.platform.repository.UserRepository;
import com.annotation.platform.service.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final ProjectRepository projectRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    @Override
    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", "username", username));
    }

    @Override
    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
    }

    @Override
    @Transactional
    public User createUser(User user) {
        if (userRepository.existsByUsername(user.getUsername())) {
            throw new BusinessException(ErrorCode.USER_003);
        }
        if (userRepository.existsByEmail(user.getEmail())) {
            throw new BusinessException(ErrorCode.USER_003);
        }

        String encodedPassword = passwordEncoder.encode(user.getPasswordHash());
        user.setPasswordHash(encodedPassword);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        user.setIsActive(true);

        return userRepository.save(user);
    }

    @Override
    @Transactional
    public User updateUser(Long userId, User user) {
        User existingUser = findById(userId);

        if (!existingUser.getUsername().equals(user.getUsername()) &&
                userRepository.existsByUsername(user.getUsername())) {
            throw new BusinessException(ErrorCode.USER_003);
        }
        if (!existingUser.getEmail().equals(user.getEmail()) &&
                userRepository.existsByEmail(user.getEmail())) {
            throw new BusinessException(ErrorCode.USER_003);
        }

        existingUser.setEmail(user.getEmail());
        existingUser.setDisplayName(user.getDisplayName());
        existingUser.setUpdatedAt(LocalDateTime.now());

        if (user.getOrganization() != null && user.getOrganization().getId() != null) {
            Organization organization = organizationRepository.findById(user.getOrganization().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Organization", "id", user.getOrganization().getId()));
            existingUser.setOrganization(organization);
        }

        return userRepository.save(existingUser);
    }

    @Override
    @Transactional
    public void deleteUser(Long userId) {
        User user = findById(userId);
        userRepository.delete(user);
    }

    @Override
    @Transactional
    public void updateLastLogin(Long userId) {
        User user = findById(userId);
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);
    }

    @Override
    public List<User> findByOrganizationId(Long organizationId) {
        return userRepository.findAll().stream()
                .filter(user -> user.getOrganization() != null && user.getOrganization().getId().equals(organizationId))
                .toList();
    }

    @Override
    public Page<User> findByOrganizationId(Long organizationId, Pageable pageable) {
        return userRepository.findAll(pageable).stream()
                .filter(user -> user.getOrganization() != null && user.getOrganization().getId().equals(organizationId))
                .toList()
                .stream()
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toList(),
                        list -> new org.springframework.data.domain.PageImpl<>(
                                list,
                                pageable,
                                list.size()
                        )
                ));
    }

    @Override
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    @Override
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(Long userId) {
        User user = findById(userId);

        Long orgId = null;
        String orgName = null;
        String orgDisplayName = null;
        Boolean isOrgCreator = false;

        if (user.getOrganization() != null) {
            orgId = user.getOrganization().getId();
            orgName = user.getOrganization().getName();
            orgDisplayName = user.getOrganization().getDisplayName();
            isOrgCreator = user.getOrganization().getCreatedBy() != null && 
                           user.getOrganization().getCreatedBy().getId().equals(userId);
        }

        long totalProjects = 0;
        long totalImages = 0;
        long processingTasks = 0;
        long completedTasks = 0;
        List<Project> recentProjects = List.of();

        if (orgId != null) {
            totalProjects = projectRepository.countByOrganizationId(orgId);
            totalImages = projectRepository.countImagesByOrganizationId(orgId);

            List<Project.ProjectStatus> processingStatuses = List.of(
                Project.ProjectStatus.UPLOADING,
                Project.ProjectStatus.DETECTING,
                Project.ProjectStatus.CLEANING,
                Project.ProjectStatus.SYNCING
            );
            processingTasks = projectRepository.countByOrganizationIdAndStatusIn(orgId, processingStatuses);

            completedTasks = projectRepository.countByOrganizationIdAndStatusIn(
                orgId, 
                List.of(Project.ProjectStatus.COMPLETED)
            );

            recentProjects = projectRepository.findTop5ByOrganizationIdOrderByCreatedAtDesc(orgId);
        }
        List<UserProfileResponse.RecentProjectInfo> recentProjectInfos = recentProjects.stream()
            .limit(5)
            .map(project -> UserProfileResponse.RecentProjectInfo.builder()
                .id(project.getId())
                .name(project.getName())
                .status(project.getStatus().name())
                .totalImages(project.getTotalImages() != null ? project.getTotalImages() : 0)
                .processedImages(project.getProcessedImages() != null ? project.getProcessedImages() : 0)
                .createdAt(project.getCreatedAt())
                .build())
            .toList();

        String lsPassword = null;
        try {
            if (user.getLsSynced() != null && user.getLsSynced() && user.getLsToken() != null) {
                String[] parts = user.getLsToken().split(":");
                if (parts.length >= 2) {
                    lsPassword = parts[1];
                }
            }
        } catch (Exception e) {
            log.warn("解析 LS 密码失败: userId={}, error={}", userId, e.getMessage());
        }

        return UserProfileResponse.builder()
            .id(user.getId())
            .username(user.getUsername())
            .email(user.getEmail())
            .displayName(user.getDisplayName())
            .avatarUrl(user.getAvatarUrl())
            .createdAt(user.getCreatedAt())
            .lastLoginAt(user.getLastLogin())
            .orgId(orgId)
            .orgName(orgName)
            .orgDisplayName(orgDisplayName)
            .isOrgCreator(isOrgCreator)
            .totalProjects(totalProjects)
            .totalImages(totalImages)
            .processingTasks(processingTasks)
            .completedTasks(completedTasks)
            .recentProjects(recentProjectInfos)
            .isActive(user.getIsActive())
            .lsSyncStatus(user.getLsSynced())
            .lsUserId(user.getLsUserId())
            .lsEmail(user.getEmail())
            .lsPassword(lsPassword)
            .build();
    }

    @Override
    public PageableResponse buildPageableResponse(Page<?> page) {
        return PageableResponse.builder()
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build();
    }
}
