package com.annotation.platform.service.auth.impl;

import com.annotation.platform.common.ErrorCode;
import com.annotation.platform.config.JwtUtils;
import com.annotation.platform.dto.request.auth.LoginRequest;
import com.annotation.platform.dto.request.auth.RegisterRequest;
import com.annotation.platform.dto.response.auth.LoginResponse;
import com.annotation.platform.entity.Organization;
import com.annotation.platform.entity.Session;
import com.annotation.platform.entity.User;
import com.annotation.platform.exception.BusinessException;
import com.annotation.platform.repository.OrganizationRepository;
import com.annotation.platform.repository.SessionRepository;
import com.annotation.platform.repository.UserRepository;
import com.annotation.platform.service.auth.AuthService;
import com.annotation.platform.service.labelstudio.LabelStudioProxyService;
import com.annotation.platform.service.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final SessionRepository sessionRepository;
    private final LabelStudioProxyService labelStudioProxyService;

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getUsername(),
                            request.getPassword()
                    )
            );

            User user = userService.findByUsername(request.getUsername());
            userService.updateLastLogin(user.getId());

            if (!user.getLsSynced() || user.getLsToken() == null) {
                try {
                    labelStudioProxyService.syncUserToLS(user, request.getPassword());
                    log.info("登录时同步用户到 Label Studio: userId={}, lsUserId={}", 
                             user.getId(), user.getLsUserId());
                    user = userService.findById(user.getId());
                } catch (Exception e) {
                    log.warn("登录时同步用户到 Label Studio 失败: userId={}, error={}", 
                             user.getId(), e.getMessage());
                }
            }

            String token = jwtUtils.generateToken(user.getUsername(), user.getId(), user.getOrganization() != null ? user.getOrganization().getId() : null);

            Session session = Session.builder()
                    .token(token)
                    .user(user)
                    .createdAt(LocalDateTime.now())
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            sessionRepository.save(session);

            LoginResponse.UserInfo userInfo = LoginResponse.UserInfo.builder()
                    .id(user.getId())
                    .username(user.getUsername())
                    .email(user.getEmail())
                    .displayName(user.getDisplayName())
                    .lsUserId(user.getLsUserId())
                    .lsToken(user.getLsToken())
                    .lsSynced(user.getLsSynced())
                    .organization(user.getOrganization() != null ?
                            LoginResponse.OrganizationInfo.builder()
                                    .id(user.getOrganization().getId())
                                    .name(user.getOrganization().getName())
                                    .displayName(user.getOrganization().getDisplayName())
                                    .build() : null)
                    .build();

            return LoginResponse.builder()
                    .token(token)
                    .user(userInfo)
                    .build();

        } catch (BadCredentialsException e) {
            log.error("登录失败: 用户名或密码错误 - {}", request.getUsername());
            throw new BusinessException(ErrorCode.AUTH_003);
        }
    }

    @Override
    @Transactional
    public LoginResponse register(RegisterRequest request) {
        if (userService.existsByUsername(request.getUsername())) {
            throw new BusinessException(ErrorCode.AUTH_004);
        }
        if (userService.existsByEmail(request.getEmail())) {
            throw new BusinessException(ErrorCode.AUTH_004);
        }

        Organization organization = null;
        boolean isNewOrganization = false;
        if (request.getOrganizationName() != null && !request.getOrganizationName().isBlank()) {
            organization = organizationRepository.findByName(request.getOrganizationName())
                    .orElseGet(() -> {
                        Organization newOrg = Organization.builder()
                                .name(request.getOrganizationName())
                                .displayName(request.getOrganizationName())
                                .createdAt(LocalDateTime.now())
                                .updatedAt(LocalDateTime.now())
                                .build();
                        return organizationRepository.save(newOrg);
                    });
            isNewOrganization = organization.getCreatedBy() == null;
        } else {
            organization = organizationRepository.findByName("Default")
                    .orElseGet(() -> {
                        Organization newOrg = Organization.builder()
                                .name("Default")
                                .displayName("Default Organization")
                                .createdAt(LocalDateTime.now())
                                .updatedAt(LocalDateTime.now())
                                .build();
                        return organizationRepository.save(newOrg);
                    });
            isNewOrganization = organization.getCreatedBy() == null;
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .passwordHash(request.getPassword())
                .displayName(request.getDisplayName())
                .organization(organization)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .isActive(true)
                .build();

        User savedUser = userService.createUser(user);

        if (isNewOrganization) {
            organization.setCreatedBy(savedUser);
            organization.setUpdatedAt(LocalDateTime.now());
            organizationRepository.save(organization);
        }

        userService.updateLastLogin(savedUser.getId());

        String jwtToken = jwtUtils.generateToken(savedUser.getUsername(), savedUser.getId(), savedUser.getOrganization() != null ? savedUser.getOrganization().getId() : null);

        Session session = Session.builder()
                .token(jwtToken)
                .user(savedUser)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        sessionRepository.save(session);

        try {
            labelStudioProxyService.syncUserToLS(savedUser, request.getPassword());
            savedUser = userService.findById(savedUser.getId());
            log.info("用户注册并绑定LS成功: userId={}, lsUserId={}", 
                     savedUser.getId(), savedUser.getLsUserId());
        } catch (Exception e) {
            log.warn("用户注册成功但LS绑定失败，后续操作时会重试: userId={}, error={}", 
                     savedUser.getId(), e.getMessage());
        }

        LoginResponse.UserInfo userInfo = LoginResponse.UserInfo.builder()
                .id(savedUser.getId())
                .username(savedUser.getUsername())
                .email(savedUser.getEmail())
                .displayName(savedUser.getDisplayName())
                .lsUserId(savedUser.getLsUserId())
                .lsToken(savedUser.getLsToken())
                .lsSynced(savedUser.getLsSynced())
                .organization(savedUser.getOrganization() != null ?
                        LoginResponse.OrganizationInfo.builder()
                                .id(savedUser.getOrganization().getId())
                                .name(savedUser.getOrganization().getName())
                                .displayName(savedUser.getOrganization().getDisplayName())
                                .build() : null)
                .build();

        return LoginResponse.builder()
                .token(jwtToken)
                .user(userInfo)
                .build();
    }

    @Override
    @Transactional
    public void logout(String token) {
        sessionRepository.deleteByToken(token);
    }

    @Override
    @Transactional
    public String refreshToken(String token) {
        String username = jwtUtils.extractUsername(token);
        Long userId = jwtUtils.extractUserId(token);

        User user = userService.findById(userId);

        String newToken = jwtUtils.generateToken(user.getUsername(), user.getId(), user.getOrganization() != null ? user.getOrganization().getId() : null);

        Session session = Session.builder()
                .token(newToken)
                .user(user)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        sessionRepository.save(session);

        sessionRepository.deleteByToken(token);

        return newToken;
    }
}
