package com.annotation.platform.service.labelstudio.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.annotation.platform.dto.response.auth.LoginResponse;
import com.annotation.platform.entity.Organization;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.User;
import com.annotation.platform.exception.BusinessException;
import com.annotation.platform.repository.OrganizationRepository;
import com.annotation.platform.repository.ProjectRepository;
import com.annotation.platform.repository.UserRepository;
import com.annotation.platform.service.labelstudio.LabelStudioProxyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.sql.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class LabelStudioProxyServiceImpl implements LabelStudioProxyService {
    private static final int PREDICTION_IMPORT_BATCH_SIZE = 500;
    private static final String IMAGE_TASK_REGEX = "(?i).*\\.(jpg|jpeg|png|bmp|gif|webp)$";
    private static final String DJANGO_SESSION_SALT = "django.contrib.sessions.backends.signed_cookies";
    private static final String DJANGO_AUTH_HASH_SALT = "django.contrib.auth.models.AbstractBaseUser.get_session_auth_hash";
    private static final String DJANGO_AUTH_BACKEND = "django.contrib.auth.backends.ModelBackend";
    private static final String BASE62_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    @Value("${app.label-studio.url}")
    private String labelStudioUrl;

    @Value("${app.label-studio.public-url}")
    private String labelStudioPublicUrl;

    @Value("${app.label-studio.secret-key:}")
    private String labelStudioSecretKey;

    @Value("${app.label-studio.env-path:/root/.local/share/label-studio/.env}")
    private String labelStudioEnvPath;

    @Value("${app.label-studio.timeout}")
    private Integer timeout;

    @Value("${app.label-studio.admin-token}")
    private String adminToken;

    @Value("${app.label-studio.db-path}")
    private String labelStudioDbPath;

    @Value("${app.file.upload.base-path:/root/autodl-fs/uploads}")
    private String uploadBasePath;

    private volatile String cachedAdminToken;

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final OrganizationRepository organizationRepository;
    private final RestTemplate restTemplate;
    private volatile String cachedLabelStudioSecretKey;

    @Override
    public String getLoginUrl(Long userId, String returnUrl) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(com.annotation.platform.common.ErrorCode.USER_001));

        createLSToken(userId);

        String next = normalizeLabelStudioReturnPath(returnUrl);
        String loginUrl = buildLabelStudioPublicUrl(next);

        log.info("生成 Label Studio 项目入口链接: userId={}, path={}", userId, next);
        return loginUrl;
    }

    @Override
    public String createBrowserSessionCookie(Long userId) {
        return createBrowserSessionCookie(userId, null, null);
    }

    @Override
    public String createBrowserSessionCookie(Long userId, Long lsProjectId, Long lsOrganizationId) {
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new BusinessException(com.annotation.platform.common.ErrorCode.USER_001));
            if (user.getLsUserId() == null) {
                syncUserToLS(user, null);
                user = userRepository.findById(userId)
                        .orElseThrow(() -> new BusinessException(com.annotation.platform.common.ErrorCode.USER_001));
            }
            if (user.getLsUserId() == null) {
                log.warn("无法生成 Label Studio 会话：用户尚未同步到 LS: userId={}", userId);
                return null;
            }

            String secretKey = resolveLabelStudioSecretKey();
            String passwordHash = fetchPasswordFromLSDB(user.getLsUserId());
            if ((passwordHash == null || passwordHash.isBlank())
                    && user.getLsPlainPassword() != null
                    && !user.getLsPlainPassword().isBlank()) {
                setLSUserPassword(user.getLsUserId(), user.getLsPlainPassword());
                passwordHash = fetchPasswordFromLSDB(user.getLsUserId());
            }
            if (secretKey == null || secretKey.isBlank() || passwordHash == null || passwordHash.isBlank()) {
                log.warn("无法生成 Label Studio 会话：缺少 LS secret 或密码哈希: userId={}, lsUserId={}", userId, user.getLsUserId());
                return null;
            }

            Long organizationPk = lsOrganizationId != null
                    ? lsOrganizationId
                    : resolveLabelStudioSessionOrganization(user);
            if (lsProjectId != null && organizationPk != null) {
                updateProjectOrganizationInLSDB(lsProjectId, organizationPk);
                updateUserActiveOrganizationInLSDB(user.getLsUserId(), organizationPk);
                addUserToOrganizationInLSDB(organizationPk, user.getLsUserId());
            }
            String authHash = saltedHmacHex(DJANGO_AUTH_HASH_SALT, passwordHash, secretKey);

            Map<String, Object> session = new LinkedHashMap<>();
            session.put("uid", UUID.randomUUID().toString());
            session.put("organization_pk", organizationPk);
            session.put("last_login", Instant.now().toEpochMilli() / 1000.0d);
            session.put("_auth_user_id", String.valueOf(user.getLsUserId()));
            session.put("_auth_user_backend", DJANGO_AUTH_BACKEND);
            session.put("_auth_user_hash", authHash);
            session.put("_session_expiry", 259200.0d);

            return signDjangoSession(session, secretKey);
        } catch (Exception e) {
            log.warn("生成 Label Studio 浏览器会话失败: userId={}, error={}", userId, e.getMessage());
            return null;
        }
    }

    @Override
    public LoginResponse.UserInfo getUserInfo(String lsToken) {
        try {
            String url = String.format("%s/api/current-user", labelStudioUrl);
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Token " + lsToken);

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                JSONObject lsUser = JSON.parseObject(response.getBody());
                return LoginResponse.UserInfo.builder()
                        .id(lsUser.getLong("id"))
                        .username(lsUser.getString("username"))
                        .email(lsUser.getString("email"))
                        .displayName(lsUser.getString("first_name") + " " + lsUser.getString("last_name"))
                        .build();
            }

            throw new BusinessException(com.annotation.platform.common.ErrorCode.LS_002);

        } catch (Exception e) {
            log.error("获取 Label Studio 用户信息失败: {}", e.getMessage(), e);
            throw new BusinessException(com.annotation.platform.common.ErrorCode.LS_001);
        }
    }

    @Override
    public String createLSToken(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(com.annotation.platform.common.ErrorCode.USER_001));

        if (user.getLsToken() != null && !user.getLsToken().isBlank()) {
            return user.getLsToken();
        }

        syncUserToLS(user, null);

        User updatedUser = userRepository.findById(userId).orElseThrow();
        return updatedUser.getLsToken();
    }

    @Override
    @Transactional
    public void syncUserToLS(User user, String plainPassword) {
        try {
            if (user.getLsUserId() != null && Boolean.TRUE.equals(user.getLsSynced()) && user.getLsToken() != null && !user.getLsToken().isBlank()) {
                if (plainPassword != null && !plainPassword.isBlank()) {
                    String existingPassword = fetchPasswordFromLSDB(user.getLsUserId());
                    if (existingPassword == null || existingPassword.isBlank()) {
                        setLSUserPassword(user.getLsUserId(), plainPassword);
                        log.info("为已同步 LS 用户补充密码: lsUserId={}", user.getLsUserId());
                    }
                    if (user.getLsPlainPassword() == null || user.getLsPlainPassword().isBlank()) {
                        user.setLsPlainPassword(plainPassword);
                        user.setUpdatedAt(LocalDateTime.now());
                        userRepository.save(user);
                    }
                }
                log.info("用户已同步到 Label Studio: userId={}, lsUserId={}", user.getId(), user.getLsUserId());
                return;
            }

            if (user.getLsUserId() != null && (user.getLsToken() == null || user.getLsToken().isBlank())) {
                log.info("用户已在 LS 中存在但 Token 为空，从数据库读取 Token: userId={}, lsUserId={}", user.getId(), user.getLsUserId());
                String token = fetchTokenFromLSDB(user.getLsUserId());
                if (token != null && !token.isBlank()) {
                    user.setLsToken(token);
                    user.setLsSynced(true);
                    user.setUpdatedAt(LocalDateTime.now());
                    userRepository.save(user);
                    log.info("从数据库读取 Token 成功: userId={}, lsUserId={}", user.getId(), user.getLsUserId());
                }
                if (plainPassword != null && !plainPassword.isBlank()) {
                    String existingPassword = fetchPasswordFromLSDB(user.getLsUserId());
                    if (existingPassword == null || existingPassword.isBlank()) {
                        setLSUserPassword(user.getLsUserId(), plainPassword);
                        user.setLsPlainPassword(plainPassword);
                        log.info("为已有 LS 用户补充密码: lsUserId={}", user.getLsUserId());
                    }
                }
                return;
            }

            syncOrganizationToLS(user.getOrganization(), user);

            String url = String.format("%s/api/users", labelStudioUrl);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String effectiveAdminToken = resolveLabelStudioAdminToken();
            if (effectiveAdminToken == null) {
                log.warn("缺少 Label Studio admin token，跳过用户同步: userId={}", user.getId());
                return;
            }
            headers.set("Authorization", "Token " + effectiveAdminToken);

            Map<String, Object> userData = new HashMap<>();
            userData.put("username", user.getUsername());
            userData.put("email", user.getEmail());
            userData.put("first_name", user.getDisplayName());
            userData.put("last_name", "");
            userData.put("is_superuser", false);
            userData.put("is_staff", false);
            userData.put("is_active", true);

            HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(userData), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                JSONObject lsUser = JSON.parseObject(response.getBody());
                user.setLsUserId(lsUser.getLong("id"));

                String token = fetchTokenFromLSDB(lsUser.getLong("id"));
                if (token != null && !token.isBlank()) {
                    user.setLsToken(token);
                    log.info("从数据库读取 Token 成功: userId={}, lsUserId={}", user.getId(), lsUser.getLong("id"));
                }

                user.setLsSynced(true);
                user.setUpdatedAt(LocalDateTime.now());
                userRepository.save(user);

                updateUserActiveOrganizationInLSDB(user.getLsUserId(), user.getOrganization().getLsOrgId());

                addUserToOrganizationInLSDB(user.getOrganization().getLsOrgId(), user.getLsUserId());

                if (user.getOrganization().getLsOrgId() != null && user.getOrganization().getLsOrgId() != 1L) {
                    removeUserFromOrganizationInLSDB(1L, user.getLsUserId());
                    log.info("已将用户从 LS 默认组织中移除: lsUserId={}", user.getLsUserId());
                }

                if (user.getOrganization() != null 
                        && user.getOrganization().getCreatedBy() != null 
                        && user.getOrganization().getCreatedBy().getId().equals(user.getId()) 
                        && user.getOrganization().getLsOrgId() != null) {
                    updateOrganizationCreatedByInLSDB(user.getOrganization().getLsOrgId(), user.getLsUserId());
                }

                if (plainPassword != null && !plainPassword.isBlank()) {
                    setLSUserPassword(lsUser.getLong("id"), plainPassword);
                    user.setLsPlainPassword(plainPassword);
                }

                log.info("用户同步到 Label Studio 成功: userId={}, lsUserId={}", user.getId(), user.getLsUserId());
            } else {
                log.warn("同步用户到 Label Studio 失败: HTTP 状态码异常: {}, 忽略此错误", response.getStatusCode());
            }

        } catch (Exception e) {
            log.warn("同步用户到 Label Studio 失败: {}, 忽略此错误", e.getMessage());
        }
    }

    private String fetchTokenFromLSDB(Long lsUserId) {
        String sql = "SELECT key FROM authtoken_token WHERE user_id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + labelStudioDbPath);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, lsUserId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("key");
                }
            }
        } catch (SQLException e) {
            log.error("从 Label Studio 数据库读取 Token 失败: lsUserId={}, error={}", lsUserId, e.getMessage(), e);
        }
        return null;
    }

    @Override
    @Transactional
    public Long syncProjectToLS(Project project, Long userId) {
        try {
            Organization organization = null;
            if (project.getOrganization() != null) {
                organization = organizationRepository.findById(project.getOrganization().getId()).orElse(null);
            }
            if (organization == null) {
                log.warn("项目没有关联组织，跳过 LS 同步: projectId={}", project.getId());
                return null;
            }

            User createdBy = null;
            if (organization.getCreatedBy() != null) {
                createdBy = userRepository.findById(organization.getCreatedBy().getId()).orElse(null);
            }

            syncOrganizationToLS(organization, createdBy);

            if (project.getLsProjectId() != null) {
                ensureProjectVisibleInOrganization(project, organization, userId);
                log.info("项目已同步到 Label Studio: projectId={}, lsProjectId={}", project.getId(), project.getLsProjectId());
                return project.getLsProjectId();
            }

            String lsToken = getOrganizationAdminToken(organization);
            if (lsToken == null) {
                log.warn("无法获取组织管理员 Token，fallback 到 admin token: orgId={}", organization.getId());
                lsToken = resolveLabelStudioAdminToken();
            }
            if (lsToken == null) {
                log.warn("缺少 Label Studio 可用 Token，跳过项目同步: projectId={}, orgId={}", project.getId(), organization.getId());
                return null;
            }

            List<String> projectLabels = project.getLabels();
            
            String labelConfig = generateLabelConfig(projectLabels);

            if (organization.getLsOrgId() != null && createdBy != null && createdBy.getLsUserId() != null) {
                updateUserActiveOrganizationInLSDB(createdBy.getLsUserId(), organization.getLsOrgId());
                log.info("创建项目前更新 LS 用户 active_organization_id: lsUserId={}, lsOrgId={}",
                         createdBy.getLsUserId(), organization.getLsOrgId());
            }

            String url = String.format("%s/api/projects", labelStudioUrl);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + lsToken);

            Map<String, Object> projectData = new HashMap<>();
            projectData.put("title", project.getName());
            projectData.put("description", "Project from Annotation Platform");
            projectData.put("label_config", labelConfig);
            
            if (organization.getLsOrgId() != null) {
                projectData.put("organization", organization.getLsOrgId());
            }

            HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(projectData), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                JSONObject lsProject = JSON.parseObject(response.getBody());
                project.setLsProjectId(lsProject.getLong("id"));
                project.setUpdatedAt(LocalDateTime.now());
                projectRepository.save(project);
                ensureProjectVisibleInOrganization(project, organization, userId);

                log.info("项目同步到 Label Studio 成功: projectId={}, lsProjectId={}", project.getId(), project.getLsProjectId());
                return lsProject.getLong("id");
            } else {
                log.warn("同步项目到 Label Studio 失败: HTTP 状态码异常: {}, 忽略此错误", response.getStatusCode());
                return null;
            }

        } catch (Exception e) {
            log.warn("同步项目到 Label Studio 失败: {}, 忽略此错误", e.getMessage());
            return null;
        }
    }

    @Override
    public void deleteLocalStorageByProject(Long lsProjectId, Long userId) {
        try {
            String lsToken = getUserLsToken(userId);
            if (lsToken == null) {
                log.warn("无法获取用户 Token，跳过 storage 清理: userId={}", userId);
                return;
            }

            // 1. 获取该项目的所有 local storage
            String listUrl = String.format("%s/api/storages/localfiles?project=%d", labelStudioUrl, lsProjectId);
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Token " + lsToken);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<List> response = restTemplate.exchange(listUrl, HttpMethod.GET, entity, List.class);
            List<Map<String, Object>> storages = response.getBody();

            if (storages == null || storages.isEmpty()) {
                log.info("项目无 local storage 需要清理: lsProjectId={}", lsProjectId);
                return;
            }

            // 2. 逐个删除 storage
            for (Map<String, Object> storage : storages) {
                Object storageIdObj = storage.get("id");
                if (storageIdObj != null) {
                    Long storageId = ((Number) storageIdObj).longValue();
                    String deleteUrl = String.format("%s/api/storages/localfiles/%d", labelStudioUrl, storageId);
                    try {
                        restTemplate.exchange(deleteUrl, HttpMethod.DELETE, entity, String.class);
                        log.info("已删除 local storage: storageId={}, lsProjectId={}", storageId, lsProjectId);
                    } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
                        log.info("Label Studio local storage 已不存在，跳过: storageId={}, lsProjectId={}", storageId, lsProjectId);
                    } catch (Exception e) {
                        log.warn("删除 local storage 失败: storageId={}, error={}", storageId, e.getMessage());
                    }
                }
            }
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.info("Label Studio 项目或 storage 已不存在，跳过 storage 清理: lsProjectId={}", lsProjectId);
        } catch (Exception e) {
            log.warn("清理 local storage 失败: lsProjectId={}, error={}", lsProjectId, e.getMessage());
        }
    }

    @Override
    public void deleteProject(Long lsProjectId, Long userId) {
        try {
            String lsToken = getUserLsToken(userId);
            if (lsToken == null) {
                log.warn("无法获取用户 Token，跳过项目删除: userId={}", userId);
                return;
            }

            String url = String.format("%s/api/projects/%d", labelStudioUrl, lsProjectId);
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Token " + lsToken);

            HttpEntity<String> entity = new HttpEntity<>(headers);
            restTemplate.exchange(
                    url,
                    HttpMethod.DELETE,
                    entity,
                    String.class
            );

            log.info("Label Studio 项目删除成功: lsProjectId={}", lsProjectId);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.info("Label Studio 项目已不存在，跳过: lsProjectId={}", lsProjectId);
        } catch (Exception e) {
            log.warn("Label Studio 删除项目失败: lsProjectId={}, error={}", lsProjectId, e.getMessage());
            throw e;
        }
    }

    @Override
    public void updateProjectLabelConfig(Long lsProjectId, List<String> labels, Long userId) {
        try {
            String lsToken = getUserLsToken(userId);
            if (lsToken == null) {
                log.warn("无法获取用户 Token，跳过 label_config 更新: userId={}", userId);
                return;
            }

            String labelConfig = generateLabelConfig(labels);
            log.info("更新 Label Studio 项目 label_config: lsProjectId={}, labelCount={}", lsProjectId, labels != null ? labels.size() : 0);

            String url = String.format("%s/api/projects/%d", labelStudioUrl, lsProjectId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + lsToken);

            Map<String, Object> updateData = new HashMap<>();
            updateData.put("label_config", labelConfig);

            HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(updateData), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.PATCH,
                    entity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                log.info("Label Studio 项目 label_config 更新成功: lsProjectId={}", lsProjectId);
            } else {
                log.warn("Label Studio 项目 label_config 更新失败: lsProjectId={}, status={}", lsProjectId, response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("更新 Label Studio 项目 label_config 失败: lsProjectId={}, error={}", lsProjectId, e.getMessage(), e);
        }
    }

    @Override
    public Long mountLocalStorage(Long lsProjectId, String localPath, Long userId) {
        try {
            String lsToken = getUserLsTokenWithFallback(userId);
            if (lsToken == null) {
                log.warn("无法获取用户 Token，跳过存储挂载: userId={}", userId);
                return null;
            }

            String normalizedPath = new File(localPath).getAbsolutePath();
            
            Long existingStorageId = getExistingLocalStorage(lsProjectId, normalizedPath, lsToken);
            if (existingStorageId != null) {
                log.info("找到已存在的存储: lsProjectId={}, storageId={}, path={}", lsProjectId, existingStorageId, normalizedPath);
                updateLocalStorageImageFilter(existingStorageId, normalizedPath, lsToken);
                return existingStorageId;
            }

            String url = String.format("%s/api/storages/localfiles", labelStudioUrl);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + lsToken);

            Map<String, Object> payload = new HashMap<>();
            payload.put("path", normalizedPath);
            payload.put("project", lsProjectId);
            payload.put("title", "Auto_Local_Images");
            payload.put("use_blob_urls", true);
            payload.put("regex_filter", IMAGE_TASK_REGEX);
            payload.put("recursive_scan", true);
            payload.put("scan_on_creation", true);
            payload.put("can_delete_objects", false);
            payload.put("presign", true);
            payload.put("presign_ttl", 1);
            payload.put("description", "自动挂载的本地存储");

            HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(payload), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.CREATED) {
                JSONObject storageData = JSON.parseObject(response.getBody());
                Long storageId = storageData.getLong("id");
                log.info("Label Studio 存储挂载成功: lsProjectId={}, storageId={}", lsProjectId, storageId);
                return storageId;
            } else {
                log.warn("Label Studio 存储挂载失败: lsProjectId={}, statusCode={}", lsProjectId, response.getStatusCode());
                return null;
            }

        } catch (Exception e) {
            log.error("Label Studio 挂载存储失败: lsProjectId={}, error={}", lsProjectId, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public boolean syncLocalStorage(Long storageId, Long userId) {
        try {
            String lsToken = getUserLsTokenWithFallback(userId);
            if (lsToken == null) {
                log.warn("无法获取用户 Token，跳过存储同步: userId={}", userId);
                return false;
            }

            String url = String.format("%s/api/storages/localfiles/%d/sync", labelStudioUrl, storageId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + lsToken);

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                log.info("Label Studio 存储同步成功: storageId={}", storageId);
                return true;
            } else {
                log.warn("Label Studio 存储同步失败: storageId={}, statusCode={}", storageId, response.getStatusCode());
                return false;
            }

        } catch (Exception e) {
            log.error("Label Studio 同步存储失败: storageId={}, error={}", storageId, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public Map<String, Object> cleanupNonImageTasks(Long lsProjectId, Long userId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("checked", 0);
        stats.put("deleted", 0);
        stats.put("failed", 0);
        stats.put("skipped", 0);

        try {
            String lsToken = getUserLsTokenWithFallback(userId);
            if (lsToken == null) {
                log.warn("无法获取用户 Token，跳过非图片 task 清理: userId={}", userId);
                return stats;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + lsToken);

            List<Map<String, Object>> tasks = fetchAllProjectTasks(lsProjectId, headers);
            stats.put("checked", tasks.size());
            for (Map<String, Object> task : tasks) {
                String imageName = extractTaskImageName(task);
                if (imageName == null || imageName.isBlank()) {
                    stats.put("skipped", (int) stats.get("skipped") + 1);
                    continue;
                }
                if (isSupportedImageFile(imageName)) {
                    continue;
                }
                Object taskId = task.get("id");
                if (taskId == null) {
                    stats.put("skipped", (int) stats.get("skipped") + 1);
                    continue;
                }
                String url = String.format("%s/api/tasks/%s", labelStudioUrl, taskId);
                try {
                    ResponseEntity<String> response = restTemplate.exchange(
                            url,
                            HttpMethod.DELETE,
                            new HttpEntity<>(headers),
                            String.class
                    );
                    if (response.getStatusCode().is2xxSuccessful()) {
                        stats.put("deleted", (int) stats.get("deleted") + 1);
                    } else {
                        stats.put("failed", (int) stats.get("failed") + 1);
                        log.warn("删除非图片 Label Studio task 失败: lsProjectId={}, taskId={}, imageName={}, status={}",
                                lsProjectId, taskId, imageName, response.getStatusCode());
                    }
                } catch (Exception e) {
                    stats.put("failed", (int) stats.get("failed") + 1);
                    log.warn("删除非图片 Label Studio task 异常: lsProjectId={}, taskId={}, imageName={}, error={}",
                            lsProjectId, taskId, imageName, e.getMessage());
                }
            }
            log.info("Label Studio 非图片 task 清理完成: lsProjectId={}, checked={}, deleted={}, failed={}, skipped={}",
                    lsProjectId, stats.get("checked"), stats.get("deleted"), stats.get("failed"), stats.get("skipped"));
        } catch (Exception e) {
            log.error("Label Studio 非图片 task 清理失败: lsProjectId={}, error={}", lsProjectId, e.getMessage(), e);
        }
        return stats;
    }

    @Override
    public Map<String, Object> prepareProjectReviewWorkspace(Project project, Long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("storageSynced", false);
        result.put("cleanup", Map.of());

        if (project == null || project.getId() == null) {
            result.put("reason", "项目不存在，无法准备 Label Studio 复核工作台");
            return result;
        }

        try {
            Long lsProjectId = syncProjectToLS(project, userId);
            if (lsProjectId == null) {
                lsProjectId = project.getLsProjectId();
            }
            result.put("lsProjectId", lsProjectId);
            if (lsProjectId == null) {
                result.put("reason", "Label Studio 项目不可用");
                return result;
            }

            Path projectUploadDir = Path.of(uploadBasePath, String.valueOf(project.getId())).toAbsolutePath().normalize();
            if (!Files.exists(projectUploadDir)) {
                result.put("available", true);
                result.put("reason", "项目图片目录尚不存在");
                result.put("localPath", projectUploadDir.toString());
                return result;
            }

            Long storageId = mountLocalStorage(lsProjectId, projectUploadDir.toString(), userId);
            result.put("storageId", storageId);
            if (storageId != null) {
                boolean synced = syncLocalStorage(storageId, userId);
                result.put("storageSynced", synced);
                int taskCount = getProjectTaskCount(lsProjectId, userId);
                result.put("taskCount", taskCount);
                if (taskCount > 500) {
                    result.put("cleanup", Map.of(
                            "skipped", true,
                            "reason", "large_project_skip_repeated_non_image_cleanup",
                            "taskCount", taskCount));
                } else {
                    result.put("cleanup", cleanupNonImageTasks(lsProjectId, userId));
                }
            }
            result.put("available", true);
            return result;
        } catch (Exception e) {
            log.warn("准备 Label Studio 复核工作台失败: projectId={}, error={}", project.getId(), e.getMessage());
            result.put("reason", e.getMessage());
            return result;
        }
    }

    @Override
    public Map<String, Object> importPredictions(Long lsProjectId, 
                                               List<Map<String, Object>> predictions, 
                                               Long userId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("success", 0);
        stats.put("failed", 0);
        stats.put("skipped", 0);
        stats.put("total", 0);

        try {
            String lsToken = getUserLsTokenWithFallback(userId);
            if (lsToken == null) {
                log.warn("无法获取用户 Token，跳过预测导入: userId={}", userId);
                return stats;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + lsToken);

            List<Map<String, Object>> tasks = fetchAllProjectTasks(lsProjectId, headers);

            stats.put("total", tasks.size());

            if (tasks.isEmpty()) {
                log.warn("Label Studio 项目中没有任务: lsProjectId={}", lsProjectId);
                return stats;
            }

            Map<String, Map<String, Object>> predictionsByImageName = new LinkedHashMap<>();
            for (Map<String, Object> pred : predictions) {
                Object imageNameObj = pred.get("image_name");
                if (imageNameObj instanceof String imageName && !imageName.isBlank()) {
                    predictionsByImageName.putIfAbsent(imageName, pred);
                }
            }

            List<Map<String, Object>> bulkPayload = new ArrayList<>();
            for (Map<String, Object> task : tasks) {
                String imageName = extractTaskImageName(task);
                if (imageName == null || imageName.isBlank()) {
                    stats.put("skipped", (int) stats.get("skipped") + 1);
                    continue;
                }

                Map<String, Object> prediction = predictionsByImageName.get(imageName);
                if (prediction == null) {
                    stats.put("skipped", (int) stats.get("skipped") + 1);
                    continue;
                }

                List<Map<String, Object>> results = (List<Map<String, Object>>) prediction.get("results");
                if (results == null || results.isEmpty()) {
                    stats.put("skipped", (int) stats.get("skipped") + 1);
                    continue;
                }

                Double avgScore = prediction.get("avg_score") != null ?
                        ((Number) prediction.get("avg_score")).doubleValue() : 0.95;

                Map<String, Object> payload = new HashMap<>();
                payload.put("task", task.get("id"));
                payload.put("result", results);
                payload.put("model_version", prediction.getOrDefault("model_version", "dino_threshold_v1"));
                payload.put("score", avgScore);
                bulkPayload.add(payload);
            }

            if (bulkPayload.isEmpty()) {
                log.info("没有可导入的 Label Studio 预测: lsProjectId={}, skipped={}", lsProjectId, stats.get("skipped"));
                return stats;
            }

            importPredictionsInBatches(lsProjectId, bulkPayload, headers, stats);

        } catch (Exception e) {
            log.error("批量导入预测失败: lsProjectId={}, error={}", lsProjectId, e.getMessage(), e);
        }

        return stats;
    }

    private void importPredictionsInBatches(Long lsProjectId,
                                            List<Map<String, Object>> bulkPayload,
                                            HttpHeaders headers,
                                            Map<String, Object> stats) {
        String bulkUrl = String.format("%s/api/projects/%d/import/predictions", labelStudioUrl, lsProjectId);
        for (int start = 0; start < bulkPayload.size(); start += PREDICTION_IMPORT_BATCH_SIZE) {
            int end = Math.min(start + PREDICTION_IMPORT_BATCH_SIZE, bulkPayload.size());
            List<Map<String, Object>> batch = bulkPayload.subList(start, end);

            try {
                HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(batch), headers);
                ResponseEntity<String> response = restTemplate.exchange(
                        bulkUrl,
                        HttpMethod.POST,
                        entity,
                        String.class
                );

                if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                    Integer created = extractCreatedPredictionCount(response.getBody());
                    if (created == null) {
                        stats.put("success", (int) stats.get("success") + batch.size());
                        log.info("Label Studio 批量预测导入成功: lsProjectId={}, batch={}-{}, created=unknown, assumed={}",
                                lsProjectId, start, end - 1, batch.size());
                    } else if (created > 0) {
                        stats.put("success", (int) stats.get("success") + created);
                        log.info("Label Studio 批量预测导入成功: lsProjectId={}, batch={}-{}, created={}",
                                lsProjectId, start, end - 1, created);
                    } else {
                        log.warn("Label Studio 批量预测导入 created=0，回退逐条导入: lsProjectId={}, batch={}-{}, body={}",
                                lsProjectId, start, end - 1, response.getBody());
                        importPredictionsOneByOne(batch, headers, stats);
                    }
                } else {
                    log.warn("Label Studio 批量预测导入返回非成功状态，回退逐条导入: lsProjectId={}, status={}, body={}",
                            lsProjectId, response.getStatusCode(), response.getBody());
                    importPredictionsOneByOne(batch, headers, stats);
                }
            } catch (Exception e) {
                log.warn("Label Studio 批量预测导入失败，回退逐条导入: lsProjectId={}, batch={}-{}, error={}",
                        lsProjectId, start, end - 1, e.getMessage());
                importPredictionsOneByOne(batch, headers, stats);
            }
        }
    }

    private Integer extractCreatedPredictionCount(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JSONObject body = JSON.parseObject(responseBody);
            return body.getInteger("created");
        } catch (Exception e) {
            return null;
        }
    }

    private void importPredictionsOneByOne(List<Map<String, Object>> payloads,
                                           HttpHeaders headers,
                                           Map<String, Object> stats) {
        String predictionsUrl = String.format("%s/api/predictions", labelStudioUrl);
        for (Map<String, Object> payload : payloads) {
            try {
                HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(payload), headers);
                ResponseEntity<String> response = restTemplate.exchange(
                        predictionsUrl,
                        HttpMethod.POST,
                        entity,
                        String.class
                );

                if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                    stats.put("success", (int) stats.get("success") + 1);
                } else if (response.getStatusCode() == HttpStatus.GONE || response.getStatusCode() == HttpStatus.CONFLICT) {
                    stats.put("skipped", (int) stats.get("skipped") + 1);
                } else {
                    stats.put("failed", (int) stats.get("failed") + 1);
                    log.error("预测逐条导入失败: taskId={}, status={}, body={}",
                            payload.get("task"), response.getStatusCode(), response.getBody());
                }
            } catch (Exception e) {
                stats.put("failed", (int) stats.get("failed") + 1);
                log.error("预测逐条导入异常: taskId={}, error={}", payload.get("task"), e.getMessage(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchAllProjectTasks(Long lsProjectId, HttpHeaders headers) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        int page = 1;
        int pageSize = 100;

        while (true) {
            String tasksUrl = String.format("%s/api/tasks?project=%d&page=%d&page_size=%d&fields=all",
                    labelStudioUrl, lsProjectId, page, pageSize);
            HttpEntity<String> tasksEntity = new HttpEntity<>(headers);
            ResponseEntity<String> tasksResponse = restTemplate.exchange(
                    tasksUrl,
                    HttpMethod.GET,
                    tasksEntity,
                    String.class
            );

            if (tasksResponse.getStatusCode() != HttpStatus.OK) {
                log.error("获取 Label Studio 任务列表失败: lsProjectId={}, status={}", lsProjectId, tasksResponse.getStatusCode());
                return tasks;
            }

            JSONObject tasksData = JSON.parseObject(tasksResponse.getBody());
            com.alibaba.fastjson2.JSONArray taskArray = tasksData.getJSONArray("tasks");
            if (taskArray == null || taskArray.isEmpty()) {
                return tasks;
            }

            for (Object obj : taskArray) {
                tasks.add((Map<String, Object>) obj);
            }

            Integer total = tasksData.getInteger("total");
            if (taskArray.size() < pageSize || (total != null && tasks.size() >= total)) {
                return tasks;
            }
            page++;
        }
    }

    @SuppressWarnings("unchecked")
    private String extractTaskImageName(Map<String, Object> task) {
        Object dataObj = task.get("data");
        if (!(dataObj instanceof Map)) {
            return null;
        }

        Map<String, Object> data = (Map<String, Object>) dataObj;
        Object imageObj = data.get("image");
        if (imageObj == null) {
            imageObj = data.get("$undefined$");
        }
        if (!(imageObj instanceof String imageUrl) || imageUrl.isBlank()) {
            return null;
        }

        String imageName = imageUrl;
        int dIndex = imageUrl.indexOf("?d=");
        if (dIndex >= 0) {
            imageName = imageUrl.substring(dIndex + 3);
            int ampIndex = imageName.indexOf('&');
            if (ampIndex >= 0) {
                imageName = imageName.substring(0, ampIndex);
            }
        }
        try {
            imageName = URLDecoder.decode(imageName, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Keep the raw value if URL decoding fails.
        }
        int slashIndex = imageName.lastIndexOf('/');
        imageName = slashIndex >= 0 ? imageName.substring(slashIndex + 1) : imageName;
        int queryIndex = imageName.indexOf('?');
        if (queryIndex >= 0) {
            imageName = imageName.substring(0, queryIndex);
        }
        return imageName;
    }

    @Override
    public void syncOrganizationToLS(Organization organization, User createdBy) {
        if (organization == null) {
            return;
        }

        try {
            if (organization.getLsOrgId() != null) {
                return;
            }

            String url = String.format("%s/api/organizations", labelStudioUrl);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String effectiveAdminToken = resolveLabelStudioAdminToken();
            if (effectiveAdminToken == null) {
                log.warn("缺少 Label Studio admin token，跳过组织同步: orgId={}", organization.getId());
                return;
            }
            headers.set("Authorization", "Token " + effectiveAdminToken);

            Map<String, Object> orgData = new HashMap<>();
            orgData.put("title", organization.getDisplayName());
            orgData.put("description", "Organization from Annotation Platform");

            HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(orgData), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                JSONObject lsOrg = JSON.parseObject(response.getBody());
                organization.setLsOrgId(lsOrg.getLong("id"));
                organization.setUpdatedAt(LocalDateTime.now());
                organizationRepository.save(organization);

                if (createdBy != null && createdBy.getLsUserId() != null) {
                    updateOrganizationCreatedByInLSDB(lsOrg.getLong("id"), createdBy.getLsUserId());
                }
            }

        } catch (Exception e) {
            log.warn("同步组织到 Label Studio 失败: {}, 忽略此错误", e.getMessage());
        }
    }

    private Long getExistingLocalStorage(Long lsProjectId, String normalizedPath, String lsToken) {
        try {
            String url = String.format("%s/api/storages/localfiles?project=%d&page_size=100", labelStudioUrl, lsProjectId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + lsToken);

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                String responseBody = response.getBody();
                log.info("获取存储列表响应: lsProjectId={}, statusCode={}, body={}", lsProjectId, response.getStatusCode(), responseBody);
                
                if (responseBody == null || responseBody.trim().isEmpty()) {
                    log.warn("存储列表响应为空: lsProjectId={}", lsProjectId);
                    return null;
                }
                
                if (responseBody.startsWith("[")) {
                    com.alibaba.fastjson2.JSONArray results = JSON.parseArray(responseBody);
                    for (int i = 0; i < results.size(); i++) {
                        JSONObject storage = results.getJSONObject(i);
                        String storagePath = storage.getString("path");
                        if (storagePath != null && new File(storagePath).getAbsolutePath().equals(normalizedPath)) {
                            return storage.getLong("id");
                        }
                    }
                } else {
                    JSONObject data = JSON.parseObject(responseBody);
                    Object resultsObj = data.get("results");
                    
                    if (resultsObj instanceof com.alibaba.fastjson2.JSONArray) {
                        com.alibaba.fastjson2.JSONArray results = (com.alibaba.fastjson2.JSONArray) resultsObj;
                        for (int i = 0; i < results.size(); i++) {
                            JSONObject storage = results.getJSONObject(i);
                            String storagePath = storage.getString("path");
                            if (storagePath != null && new File(storagePath).getAbsolutePath().equals(normalizedPath)) {
                                return storage.getLong("id");
                            }
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.error("获取已存在的存储失败: lsProjectId={}, error={}", lsProjectId, e.getMessage(), e);
            return null;
        }
    }

    private void updateLocalStorageImageFilter(Long storageId, String normalizedPath, String lsToken) {
        try {
            String url = String.format("%s/api/storages/localfiles/%d", labelStudioUrl, storageId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + lsToken);

            Map<String, Object> payload = new HashMap<>();
            // Label Studio 1.22 在 PATCH 时仍会校验必填 path，必须连同过滤器一起传回。
            payload.put("path", normalizedPath);
            payload.put("regex_filter", IMAGE_TASK_REGEX);
            payload.put("recursive_scan", true);
            payload.put("use_blob_urls", true);
            payload.put("presign", true);
            payload.put("presign_ttl", 1);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.PATCH,
                    new HttpEntity<>(JSON.toJSONString(payload), headers),
                    String.class
            );
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Label Studio local storage 图片过滤器已更新: storageId={}", storageId);
            } else {
                log.warn("更新 Label Studio local storage 图片过滤器失败: storageId={}, status={}",
                        storageId, response.getStatusCode());
            }
        } catch (Exception e) {
            log.warn("更新 Label Studio local storage 图片过滤器异常: storageId={}, error={}", storageId, e.getMessage());
        }
    }

    private boolean isSupportedImageFile(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        String lower = filename.toLowerCase();
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".bmp")
                || lower.endsWith(".gif")
                || lower.endsWith(".webp");
    }

    @Override
    public int getProjectTaskCount(Long lsProjectId, Long userId) {
        try {
            String lsToken = getUserLsTokenWithFallback(userId);
            if (lsToken == null) {
                log.warn("无法获取用户 Token，跳过获取 task 数量: userId={}", userId);
                return 0;
            }

            String url = String.format("%s/api/tasks?project=%d&page=1&page_size=1", labelStudioUrl, lsProjectId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + lsToken);

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                String responseBody = response.getBody();
                log.info("获取项目 task 列表响应: lsProjectId={}, statusCode={}, body={}", lsProjectId, response.getStatusCode(), responseBody);
                
                if (responseBody == null || responseBody.trim().isEmpty()) {
                    log.warn("task 列表响应为空: lsProjectId={}", lsProjectId);
                    return 0;
                }
                
                JSONObject data = JSON.parseObject(responseBody);
                Integer totalCount = data.getInteger("total_count");
                if (totalCount == null) {
                    totalCount = data.getInteger("total");
                }
                if (totalCount == null) {
                    log.warn("total_count 字段为空，尝试从 tasks 数组获取: lsProjectId={}", lsProjectId);
                    Object tasksObj = data.get("tasks");
                    if (tasksObj instanceof com.alibaba.fastjson2.JSONArray) {
                        com.alibaba.fastjson2.JSONArray tasks = (com.alibaba.fastjson2.JSONArray) tasksObj;
                        return tasks.size();
                    }
                    return 0;
                }
                return totalCount;
            }
            return 0;
        } catch (Exception e) {
            log.error("获取项目 task 数量失败: lsProjectId={}, error={}", lsProjectId, e.getMessage());
            return 0;
        }
    }

    private String getUserLsToken(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return null;
        }

        if (user.getLsToken() != null && !user.getLsToken().isBlank()) {
            return user.getLsToken();
        }

        syncUserToLS(user, null);
        User updatedUser = userRepository.findById(userId).orElse(null);
        return updatedUser != null ? updatedUser.getLsToken() : null;
    }

    private String getUserLsTokenWithFallback(Long userId) {
        String lsToken = getUserLsToken(userId);
        if (lsToken != null) {
            return lsToken;
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user != null && user.getOrganization() != null) {
            String organizationToken = getOrganizationAdminToken(user.getOrganization());
            if (organizationToken != null) {
                return organizationToken;
            }
        }

        return resolveLabelStudioAdminToken();
    }

    private String resolveLabelStudioAdminToken() {
        if (adminToken != null && !adminToken.isBlank()) {
            return adminToken.trim();
        }
        if (cachedAdminToken != null && !cachedAdminToken.isBlank()) {
            return cachedAdminToken;
        }

        String sql = """
                SELECT t.key, t.user_id
                FROM authtoken_token t
                JOIN htx_user u ON u.id = t.user_id
                WHERE t.key IS NOT NULL AND length(trim(t.key)) > 0 AND u.is_active = 1
                ORDER BY u.id
                LIMIT 1
                """;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + labelStudioDbPath);
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                cachedAdminToken = rs.getString("key");
                log.info("从 Label Studio 本地数据库读取可用 Token: lsUserId={}", rs.getLong("user_id"));
                return cachedAdminToken;
            }
        } catch (SQLException e) {
            log.warn("从 Label Studio 本地数据库读取可用 Token 失败: {}", e.getMessage());
        }
        return null;
    }

    private String getOrganizationAdminToken(Organization organization) {
        if (organization == null || organization.getId() == null) {
            return null;
        }

        Organization org = organizationRepository.findById(organization.getId()).orElse(null);
        if (org == null || org.getCreatedBy() == null) {
            return null;
        }

        User admin = userRepository.findById(org.getCreatedBy().getId()).orElse(null);
        if (admin == null || admin.getLsToken() == null || admin.getLsToken().isBlank()) {
            return null;
        }

        return admin.getLsToken();
    }

    private String generateLabelConfig(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            log.warn("generateLabelConfig labels 为空，返回空配置");
            return "<View></View>";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<View>\n");
        sb.append("  <Image name=\"image\" value=\"$image\" zoom=\"true\"/>\n");
        sb.append("  <RectangleLabels name=\"label\" toName=\"image\">\n");

        String[] colors = {
            "#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#FF00FF", "#00FFFF",
            "#FFA500", "#800080", "#008000", "#FFC0CB", "#A52A2A", "#808080"
        };

        for (int i = 0; i < labels.size(); i++) {
            String color = colors[i % colors.length];
            String labelValue = escapeXmlAttribute(labels.get(i));
            sb.append("    <Label value=\"").append(labelValue).append("\" background=\"").append(color).append("\"/>\n");
        }

        sb.append("  </RectangleLabels>\n");
        sb.append("</View>");
        return sb.toString();
    }

    private String escapeXmlAttribute(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private void updateOrganizationCreatedByInLSDB(Long lsOrgId, Long lsUserId) {
        String sql = "UPDATE organization SET created_by_id = ? WHERE id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + labelStudioDbPath);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, lsUserId);
            stmt.setLong(2, lsOrgId);
            int updated = stmt.executeUpdate();
            log.info("更新 LS 组织 created_by_id: lsOrgId={}, lsUserId={}, updatedRows={}", lsOrgId, lsUserId, updated);
        } catch (SQLException e) {
            log.error("更新 LS 组织 created_by_id 失败: lsOrgId={}, lsUserId={}, error={}", lsOrgId, lsUserId, e.getMessage(), e);
        }
    }

    private void updateUserActiveOrganizationInLSDB(Long lsUserId, Long lsOrgId) {
        String sql = "UPDATE htx_user SET active_organization_id = ? WHERE id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + labelStudioDbPath);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, lsOrgId);
            stmt.setLong(2, lsUserId);
            int updated = stmt.executeUpdate();
            log.info("更新 LS 用户 active_organization_id: lsUserId={}, lsOrgId={}, updatedRows={}", lsUserId, lsOrgId, updated);
        } catch (SQLException e) {
            log.error("更新 LS 用户 active_organization_id 失败: lsUserId={}, lsOrgId={}, error={}", lsUserId, lsOrgId, e.getMessage(), e);
        }
    }

    private void ensureProjectVisibleInOrganization(Project project, Organization organization, Long userId) {
        if (project == null || project.getLsProjectId() == null || organization == null || organization.getLsOrgId() == null) {
            return;
        }
        updateProjectOrganizationInLSDB(project.getLsProjectId(), organization.getLsOrgId());
        if (userId == null) {
            return;
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return;
        }
        if (user.getLsUserId() == null) {
            syncUserToLS(user, null);
            user = userRepository.findById(userId).orElse(null);
        }
        if (user != null && user.getLsUserId() != null) {
            updateUserActiveOrganizationInLSDB(user.getLsUserId(), organization.getLsOrgId());
            addUserToOrganizationInLSDB(organization.getLsOrgId(), user.getLsUserId());
        }
    }

    private void updateProjectOrganizationInLSDB(Long lsProjectId, Long lsOrgId) {
        String sql = "UPDATE project SET organization_id = ? WHERE id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + labelStudioDbPath);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, lsOrgId);
            stmt.setLong(2, lsProjectId);
            int updated = stmt.executeUpdate();
            log.info("更新 LS 项目 organization_id: lsProjectId={}, lsOrgId={}, updatedRows={}",
                    lsProjectId, lsOrgId, updated);
        } catch (SQLException e) {
            log.error("更新 LS 项目 organization_id 失败: lsProjectId={}, lsOrgId={}, error={}",
                    lsProjectId, lsOrgId, e.getMessage(), e);
        }
    }

    private void addUserToOrganizationInLSDB(Long lsOrgId, Long lsUserId) {
        String checkSql = "SELECT COUNT(*) FROM organizations_organizationmember WHERE organization_id = ? AND user_id = ?";
        String insertSql = "INSERT INTO organizations_organizationmember (organization_id, user_id, created_at, updated_at) VALUES (?, ?, ?, ?)";
        
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + labelStudioDbPath)) {
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setLong(1, lsOrgId);
                checkStmt.setLong(2, lsUserId);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        log.info("用户已在组织成员表中: lsOrgId={}, lsUserId={}", lsOrgId, lsUserId);
                        return;
                    }
                }
            }
            
            String now = java.time.LocalDateTime.now().toString();
            try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                insertStmt.setLong(1, lsOrgId);
                insertStmt.setLong(2, lsUserId);
                insertStmt.setString(3, now);
                insertStmt.setString(4, now);
                int rows = insertStmt.executeUpdate();
                log.info("添加用户到组织成员表: lsOrgId={}, lsUserId={}, rows={}", lsOrgId, lsUserId, rows);
            }
        } catch (SQLException e) {
            log.error("添加用户到组织成员表失败: lsOrgId={}, lsUserId={}, error={}", lsOrgId, lsUserId, e.getMessage());
        }
    }

    private void removeUserFromOrganizationInLSDB(Long lsOrgId, Long lsUserId) {
        String sql = "DELETE FROM organizations_organizationmember WHERE organization_id = ? AND user_id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + labelStudioDbPath);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, lsOrgId);
            stmt.setLong(2, lsUserId);
            int deleted = stmt.executeUpdate();
            log.info("从 LS 组织成员表移除用户: lsOrgId={}, lsUserId={}, deletedRows={}", lsOrgId, lsUserId, deleted);
        } catch (SQLException e) {
            log.error("从 LS 组织成员表移除用户失败: lsOrgId={}, lsUserId={}, error={}", lsOrgId, lsUserId, e.getMessage(), e);
        }
    }

    private String normalizeLabelStudioReturnPath(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) {
            return "/projects";
        }
        String next = returnUrl.trim();
        if (!next.startsWith("/") || next.startsWith("//") || next.contains("\r") || next.contains("\n")) {
            return "/projects";
        }
        return next;
    }

    private String buildLabelStudioPublicUrl(String path) {
        String base = labelStudioPublicUrl != null ? labelStudioPublicUrl.trim() : "";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + normalizeLabelStudioReturnPath(path);
    }

    private Long resolveLabelStudioSessionOrganization(User user) {
        if (user.getLsOrgId() != null) {
            return user.getLsOrgId();
        }
        if (user.getOrganization() != null && user.getOrganization().getLsOrgId() != null) {
            return user.getOrganization().getLsOrgId();
        }
        return 1L;
    }

    private String resolveLabelStudioSecretKey() {
        if (labelStudioSecretKey != null && !labelStudioSecretKey.isBlank()) {
            return labelStudioSecretKey.trim();
        }
        if (cachedLabelStudioSecretKey != null && !cachedLabelStudioSecretKey.isBlank()) {
            return cachedLabelStudioSecretKey;
        }
        try {
            for (String line : Files.readAllLines(Path.of(labelStudioEnvPath), StandardCharsets.UTF_8)) {
                if (line.startsWith("SECRET_KEY=")) {
                    String secret = line.substring("SECRET_KEY=".length()).trim();
                    if (!secret.isBlank()) {
                        cachedLabelStudioSecretKey = secret;
                        return secret;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("读取 Label Studio SECRET_KEY 失败: path={}, error={}", labelStudioEnvPath, e.getMessage());
        }
        return null;
    }

    private String signDjangoSession(Map<String, Object> session, String secretKey) throws Exception {
        byte[] json = JSON.toJSONString(session).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = zlibCompress(json);
        boolean useCompressed = compressed.length < json.length - 1;
        String payload = base64Url(useCompressed ? compressed : json);
        if (useCompressed) {
            payload = "." + payload;
        }
        String value = payload + ":" + base62Encode(Instant.now().getEpochSecond());
        String signature = base64Url(saltedHmacDigest(DJANGO_SESSION_SALT + "signer", value, secretKey));
        return value + ":" + signature;
    }

    private byte[] zlibCompress(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(data);
        }
        return out.toByteArray();
    }

    private String saltedHmacHex(String keySalt, String value, String secretKey) throws Exception {
        byte[] digest = saltedHmacDigest(keySalt, value, secretKey);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    private byte[] saltedHmacDigest(String keySalt, String value, String secretKey) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] key = sha256.digest((keySalt + secretKey).getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private String base62Encode(long value) {
        if (value == 0) {
            return "0";
        }
        StringBuilder encoded = new StringBuilder();
        long current = Math.abs(value);
        while (current > 0) {
            int remainder = (int) (current % 62);
            encoded.append(BASE62_ALPHABET.charAt(remainder));
            current = current / 62;
        }
        if (value < 0) {
            encoded.append("-");
        }
        return encoded.reverse().toString();
    }

    private String fetchPasswordFromLSDB(Long lsUserId) {
        String sql = "SELECT password FROM htx_user WHERE id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + labelStudioDbPath);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, lsUserId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("password");
                }
            }
        } catch (SQLException e) {
            log.error("从 Label Studio 数据库读取密码失败: lsUserId={}, error={}", lsUserId, e.getMessage(), e);
        }
        return null;
    }

    private void setLSUserPassword(Long lsUserId, String plainPassword) {
        String passwordHash = generateDjangoPasswordHash(plainPassword);
        if (passwordHash == null) {
            log.warn("生成密码哈希失败，跳过设置密码: lsUserId={}", lsUserId);
            return;
        }

        String sql = "UPDATE htx_user SET password = ? WHERE id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + labelStudioDbPath);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, passwordHash);
            stmt.setLong(2, lsUserId);
            int rows = stmt.executeUpdate();
            if (rows > 0) {
                log.info("设置 LS 用户密码成功: lsUserId={}", lsUserId);
            }
        } catch (SQLException e) {
            log.error("设置 LS 用户密码失败: lsUserId={}, error={}", lsUserId, e.getMessage(), e);
        }
    }

    private String generateDjangoPasswordHash(String password) {
        try {
            int iterations = 870000;
            byte[] saltBytes = new byte[16];
            new java.security.SecureRandom().nextBytes(saltBytes);
            String salt = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(saltBytes);

            javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            java.security.spec.KeySpec spec = new javax.crypto.spec.PBEKeySpec(
                password.toCharArray(), salt.getBytes(java.nio.charset.StandardCharsets.UTF_8), iterations, 256);
            javax.crypto.SecretKey key = factory.generateSecret(spec);
            String hash = java.util.Base64.getEncoder().encodeToString(key.getEncoded());

            return String.format("pbkdf2_sha256$%d$%s$%s", iterations, salt, hash);
        } catch (Exception e) {
            log.error("生成密码哈希失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Map<String, Object> getProjectReviewStats(Long lsProjectId, Long userId) {
        try {
            String lsToken = getUserLsToken(userId);
            if (lsToken == null) {
                log.warn("无法获取用户 Token，跳过获取审核统计: userId={}", userId);
                return createEmptyReviewStats();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Token " + lsToken);

            List<Map<String, Object>> tasks = fetchAllProjectTasks(lsProjectId, headers);
            if (tasks.isEmpty()) {
                return createEmptyReviewStats();
            }

            int totalTasks = tasks.size();
            int reviewedTasks = 0;
            int pendingTasks = 0;
            int tasksWithPredictions = 0;
            int totalPredictions = 0;
            int totalPredictionResults = 0;
            int totalAnnotationResults = 0;

            for (Map<String, Object> task : tasks) {
                com.alibaba.fastjson2.JSONArray annotations = toJsonArray(task.get("annotations"));
                com.alibaba.fastjson2.JSONArray predictions = toJsonArray(task.get("predictions"));
                int taskAnnotationCount = countFromArrayOrSummary(annotations, task.get("total_annotations"));
                int taskPredictionCount = countFromArrayOrSummary(predictions, task.get("total_predictions"));
                if (taskAnnotationCount > 0 || Boolean.TRUE.equals(task.get("is_labeled"))) {
                    reviewedTasks++;
                    totalAnnotationResults += annotations != null && !annotations.isEmpty()
                            ? countNestedResults(annotations)
                            : taskAnnotationCount;
                } else {
                    pendingTasks++;
                }
                if (taskPredictionCount > 0) {
                    tasksWithPredictions++;
                    totalPredictions += taskPredictionCount;
                    totalPredictionResults += predictions != null && !predictions.isEmpty()
                            ? countNestedResults(predictions)
                            : taskPredictionCount;
                }
            }

            Map<String, Object> stats = new HashMap<>();
            stats.put("totalTasks", totalTasks);
            stats.put("reviewedTasks", reviewedTasks);
            stats.put("pendingTasks", pendingTasks);
            stats.put("tasksWithPredictions", tasksWithPredictions);
            stats.put("totalPredictions", totalPredictions);
            stats.put("totalPredictionResults", totalPredictionResults);
            stats.put("totalAnnotationResults", totalAnnotationResults);

            log.info("获取审核统计成功: lsProjectId={}, total={}, reviewed={}, pending={}, predicted={}",
                    lsProjectId, totalTasks, reviewedTasks, pendingTasks, tasksWithPredictions);

            return stats;
        } catch (Exception e) {
            log.error("获取审核统计失败: lsProjectId={}, error={}", lsProjectId, e.getMessage(), e);
            return createEmptyReviewStats();
        }
    }

    @Override
    public Map<String, Object> getProjectReviewResults(Long lsProjectId, Long userId) {
        try {
            String lsToken = getUserLsToken(userId);
            if (lsToken == null) {
                log.warn("无法获取用户 Token，跳过获取审核结果: userId={}", userId);
                return createEmptyReviewResults();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Token " + lsToken);

            List<Map<String, Object>> tasks = fetchAllProjectTasks(lsProjectId, headers);
            List<Map<String, Object>> taskList = new ArrayList<>();

            for (Map<String, Object> task : tasks) {
                Map<String, Object> taskInfo = new HashMap<>();
                taskInfo.put("taskId", task.get("id"));
                taskInfo.put("imageName", extractTaskImageName(task));

                com.alibaba.fastjson2.JSONArray annotations = toJsonArray(task.get("annotations"));
                com.alibaba.fastjson2.JSONArray predictions = toJsonArray(task.get("predictions"));
                int summaryAnnotationCount = countFromArrayOrSummary(annotations, task.get("total_annotations"));
                int summaryPredictionCount = countFromArrayOrSummary(predictions, task.get("total_predictions"));
                boolean isReviewed = summaryAnnotationCount > 0 || Boolean.TRUE.equals(task.get("is_labeled"));
                taskInfo.put("isReviewed", isReviewed);

                int annotationCount = 0;
                int predictionCount = 0;
                String completedAt = null;
                String annotatedBy = null;

                if (predictions != null && !predictions.isEmpty()) {
                    predictionCount = countNestedResults(predictions);
                } else {
                    predictionCount = summaryPredictionCount;
                }

                if (isReviewed) {
                    annotationCount = summaryAnnotationCount;
                    if (annotations != null && !annotations.isEmpty()) {
                        JSONObject annotation = annotations.getJSONObject(0);
                        com.alibaba.fastjson2.JSONArray results = annotation.getJSONArray("result");
                        annotationCount = results != null ? results.size() : annotationCount;
                        completedAt = annotation.getString("created_at");

                        Object completedBy = annotation.get("completed_by");
                        Long completedById = extractUserId(completedBy);
                        if (completedById != null) {
                            annotatedBy = getUserEmailById(completedById);
                        }
                    }
                }

                taskInfo.put("annotationCount", annotationCount);
                taskInfo.put("predictionCount", predictionCount);
                taskInfo.put("displayCount", isReviewed ? annotationCount : predictionCount);
                taskInfo.put("completedAt", completedAt);
                taskInfo.put("annotatedBy", annotatedBy);

                taskList.add(taskInfo);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("tasks", taskList);

            log.info("获取审核结果成功: lsProjectId={}, taskCount={}", lsProjectId, taskList.size());

            return result;
        } catch (Exception e) {
            log.error("获取审核结果失败: lsProjectId={}, error={}", lsProjectId, e.getMessage(), e);
            return createEmptyReviewResults();
        }
    }

    private com.alibaba.fastjson2.JSONArray toJsonArray(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof com.alibaba.fastjson2.JSONArray jsonArray) {
            return jsonArray;
        }
        return JSON.parseArray(JSON.toJSONString(value));
    }

    private int countNestedResults(com.alibaba.fastjson2.JSONArray items) {
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            com.alibaba.fastjson2.JSONArray results = item.getJSONArray("result");
            if (results != null) {
                count += results.size();
            }
        }
        return count;
    }

    private int countFromArrayOrSummary(com.alibaba.fastjson2.JSONArray items, Object summaryValue) {
        if (items != null && !items.isEmpty()) {
            return items.size();
        }
        if (summaryValue instanceof Number number) {
            return number.intValue();
        }
        if (summaryValue != null) {
            try {
                return Integer.parseInt(String.valueOf(summaryValue));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private Long extractUserId(Object completedBy) {
        if (completedBy instanceof Number number) {
            return number.longValue();
        }
        if (completedBy instanceof Map<?, ?> map) {
            Object id = map.get("id");
            return id instanceof Number number ? number.longValue() : null;
        }
        return null;
    }

    private Map<String, Object> createEmptyReviewStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalTasks", 0);
        stats.put("reviewedTasks", 0);
        stats.put("pendingTasks", 0);
        stats.put("tasksWithPredictions", 0);
        stats.put("totalPredictions", 0);
        stats.put("totalPredictionResults", 0);
        stats.put("totalAnnotationResults", 0);
        return stats;
    }

    private Map<String, Object> createEmptyReviewResults() {
        Map<String, Object> result = new HashMap<>();
        result.put("tasks", new ArrayList<>());
        return result;
    }

    private String getUserEmailById(Long lsUserId) {
        String sql = "SELECT email FROM htx_user WHERE id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + labelStudioDbPath);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, lsUserId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("email");
                }
            }
        } catch (SQLException e) {
            log.error("从 Label Studio 数据库读取用户邮箱失败: lsUserId={}, error={}", lsUserId, e.getMessage(), e);
        }
        return null;
    }

    @Override
    public List<Map<String, Object>> exportAnnotations(Long lsProjectId, Long userId, String format) {
        try {
            String lsToken = getUserLsTokenWithFallback(userId);
            if (lsToken == null) {
                log.warn("无法获取用户 Token，跳过导出: userId={}", userId);
                return new ArrayList<>();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Token " + lsToken);

            List<Map<String, Object>> tasks = fetchAllProjectTasks(lsProjectId, headers);
            List<Map<String, Object>> annotations = new ArrayList<>();
            boolean reviewedOnly = "reviewed-json".equalsIgnoreCase(format) || "reviewed".equalsIgnoreCase(format);

            for (Map<String, Object> task : tasks) {
                com.alibaba.fastjson2.JSONArray taskAnnotations = toJsonArray(task.get("annotations"));
                com.alibaba.fastjson2.JSONArray taskPredictions = toJsonArray(task.get("predictions"));

                JSONObject source = null;
                String sourceType = null;
                if (taskAnnotations != null && !taskAnnotations.isEmpty()) {
                    source = taskAnnotations.getJSONObject(0);
                    sourceType = "annotation";
                } else if (!reviewedOnly && taskPredictions != null && !taskPredictions.isEmpty()) {
                    source = taskPredictions.getJSONObject(0);
                    sourceType = "prediction";
                }

                if (source == null) {
                    continue;
                }

                com.alibaba.fastjson2.JSONArray results = source.getJSONArray("result");
                if (results == null) {
                    results = new com.alibaba.fastjson2.JSONArray();
                }

                Map<String, Object> annotationData = new HashMap<>();
                annotationData.put("image_name", extractTaskImageName(task));
                annotationData.put("image_width", extractOriginalSize(results, true));
                annotationData.put("image_height", extractOriginalSize(results, false));
                annotationData.put("task_id", task.get("id"));
                annotationData.put("source", sourceType);

                List<Map<String, Object>> boxes = new ArrayList<>();
                for (int j = 0; j < results.size(); j++) {
                    JSONObject result = results.getJSONObject(j);
                    JSONObject value = result.getJSONObject("value");

                    if (value != null && value.containsKey("rectanglelabels")) {
                        com.alibaba.fastjson2.JSONArray labels = value.getJSONArray("rectanglelabels");
                        if (labels != null && !labels.isEmpty()) {
                            Map<String, Object> box = new HashMap<>();
                            box.put("label", labels.getString(0));
                            box.put("x", value.getDouble("x"));
                            box.put("y", value.getDouble("y"));
                            box.put("width", value.getDouble("width"));
                            box.put("height", value.getDouble("height"));
                            boxes.add(box);
                        }
                    }
                }

                annotationData.put("annotations", boxes);
                annotations.add(annotationData);
            }

            log.info("导出标注成功: lsProjectId={}, format={}, count={}", lsProjectId, format, annotations.size());
            return annotations;
        } catch (Exception e) {
            log.error("导出标注失败: lsProjectId={}, error={}", lsProjectId, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private Integer extractOriginalSize(com.alibaba.fastjson2.JSONArray results, boolean width) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        JSONObject firstResult = results.getJSONObject(0);
        String snakeKey = width ? "original_width" : "original_height";
        Integer size = firstResult.getInteger(snakeKey);
        if (size != null) {
            return size;
        }

        JSONObject value = firstResult.getJSONObject("value");
        if (value == null) {
            return null;
        }
        String camelKey = width ? "originalWidth" : "originalHeight";
        return value.getInteger(camelKey);
    }
}
