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
import java.io.File;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LabelStudioProxyServiceImpl implements LabelStudioProxyService {

    @Value("${app.label-studio.url}")
    private String labelStudioUrl;

    @Value("${app.label-studio.public-url}")
    private String labelStudioPublicUrl;

    @Value("${app.label-studio.timeout}")
    private Integer timeout;

    @Value("${app.label-studio.admin-token}")
    private String adminToken;

    @Value("${app.label-studio.db-path}")
    private String labelStudioDbPath;

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final OrganizationRepository organizationRepository;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String getLoginUrl(Long userId, String returnUrl) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(com.annotation.platform.common.ErrorCode.USER_001));

        String lsToken = createLSToken(userId);

        String loginUrl = String.format("%s/api/auth/login?token=%s", labelStudioPublicUrl, lsToken);
        if (returnUrl != null && !returnUrl.isBlank()) {
            loginUrl += "&next=" + returnUrl;
        }

        log.info("生成 Label Studio 登录链接: userId={}, loginUrl={}", userId, loginUrl);
        return loginUrl;
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
            if (user.getLsUserId() != null && user.getLsSynced() && user.getLsToken() != null && !user.getLsToken().isBlank()) {
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
                        log.info("为已有 LS 用户补充密码: lsUserId={}", user.getLsUserId());
                    }
                }
                return;
            }

            syncOrganizationToLS(user.getOrganization(), user);

            String url = String.format("%s/api/users", labelStudioUrl);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + adminToken);

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

                if (user.getOrganization() != null 
                        && user.getOrganization().getCreatedBy() != null 
                        && user.getOrganization().getCreatedBy().getId().equals(user.getId()) 
                        && user.getOrganization().getLsOrgId() != null) {
                    updateOrganizationCreatedByInLSDB(user.getOrganization().getLsOrgId(), user.getLsUserId());
                }

                if (plainPassword != null && !plainPassword.isBlank()) {
                    setLSUserPassword(lsUser.getLong("id"), plainPassword);
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
            if (project.getLsProjectId() != null) {
                log.info("项目已同步到 Label Studio: projectId={}, lsProjectId={}", project.getId(), project.getLsProjectId());
                return project.getLsProjectId();
            }

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

            String lsToken = getOrganizationAdminToken(organization);
            if (lsToken == null) {
                log.warn("无法获取组织管理员 Token，fallback 到 admin token: orgId={}", organization.getId());
                lsToken = adminToken;
            }

            String labelConfig = generateLabelConfig(project.getLabels());

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
        } catch (Exception e) {
            log.warn("Label Studio 删除项目失败: lsProjectId={}, error={}", lsProjectId, e.getMessage());
            throw e;
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
            payload.put("regex_filter", ".*");
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

            String tasksUrl = String.format("%s/api/tasks?project=%d", labelStudioUrl, lsProjectId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + lsToken);

            HttpEntity<String> tasksEntity = new HttpEntity<>(headers);
            ResponseEntity<String> tasksResponse = restTemplate.exchange(
                    tasksUrl,
                    HttpMethod.GET,
                    tasksEntity,
                    String.class
            );

            if (tasksResponse.getStatusCode() != HttpStatus.OK) {
                log.error("获取 Label Studio 任务列表失败: lsProjectId={}, status={}", lsProjectId, tasksResponse.getStatusCode());
                return stats;
            }

            JSONObject tasksData = JSON.parseObject(tasksResponse.getBody());
            List<Map<String, Object>> tasks = tasksData.getJSONArray("tasks")
                    .toJavaList(Object.class).stream()
                    .map(obj -> (Map<String, Object>) obj)
                    .collect(java.util.stream.Collectors.toList());

            stats.put("total", tasks.size());

            if (tasks.isEmpty()) {
                log.warn("Label Studio 项目中没有任务: lsProjectId={}", lsProjectId);
                return stats;
            }

            String predictionsUrl = String.format("%s/api/predictions", labelStudioUrl);
            for (Map<String, Object> task : tasks) {
                String imageUrl = (String) ((Map<String, Object>) task.get("data")).get("image");
                String imageName = imageUrl.substring(imageUrl.lastIndexOf("/") + 1);

                Map<String, Object> prediction = null;
                for (Map<String, Object> pred : predictions) {
                    String predImageName = (String) pred.get("image_name");
                    if (predImageName != null && predImageName.equals(imageName)) {
                        prediction = pred;
                        break;
                    }
                }

                if (prediction == null) {
                    stats.put("skipped", (int) stats.get("skipped") + 1);
                    continue;
                }

                try {
                    List<Map<String, Object>> results = (List<Map<String, Object>>) prediction.get("results");
                    Double avgScore = prediction.get("avg_score") != null ? 
                            ((Number) prediction.get("avg_score")).doubleValue() : 0.95;

                    Map<String, Object> payload = new HashMap<>();
                    payload.put("task", task.get("id"));
                    payload.put("result", results);
                    payload.put("model_version", "vlm_cleaned_v1");
                    payload.put("score", avgScore);

                    log.info("导入预测: taskId={}, imageName={}, resultCount={}, score={}", 
                            task.get("id"), imageName, results.size(), avgScore);

                    HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(payload), headers);
                    ResponseEntity<String> response = restTemplate.exchange(
                            predictionsUrl,
                            HttpMethod.POST,
                            entity,
                            String.class
                    );

                    if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                        stats.put("success", (int) stats.get("success") + 1);
                        log.info("预测导入成功: taskId={}", task.get("id"));
                    } else if (response.getStatusCode() == HttpStatus.GONE || response.getStatusCode() == HttpStatus.CONFLICT) {
                        stats.put("skipped", (int) stats.get("skipped") + 1);
                        log.info("预测已存在: taskId={}", task.get("id"));
                    } else {
                        stats.put("failed", (int) stats.get("failed") + 1);
                        log.error("预测导入失败: taskId={}, status={}, body={}", 
                                task.get("id"), response.getStatusCode(), response.getBody());
                    }

                } catch (Exception e) {
                    log.error("导入预测失败: taskId={}, error={}", task.get("id"), e.getMessage(), e);
                    stats.put("failed", (int) stats.get("failed") + 1);
                }
            }

        } catch (Exception e) {
            log.error("批量导入预测失败: lsProjectId={}, error={}", lsProjectId, e.getMessage(), e);
        }

        return stats;
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
            headers.set("Authorization", "Token " + adminToken);

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
            return getOrganizationAdminToken(user.getOrganization());
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
            sb.append("    <Label value=\"").append(labels.get(i)).append("\" background=\"").append(color).append("\"/>\n");
        }

        sb.append("  </RectangleLabels>\n");
        sb.append("</View>");

        return sb.toString();
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
}
