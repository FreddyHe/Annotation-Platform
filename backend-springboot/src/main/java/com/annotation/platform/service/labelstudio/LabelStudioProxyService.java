package com.annotation.platform.service.labelstudio;

import com.annotation.platform.dto.response.auth.LoginResponse;
import com.annotation.platform.entity.Organization;
import com.annotation.platform.entity.Project;
import com.annotation.platform.entity.User;

import java.util.List;
import java.util.Map;

public interface LabelStudioProxyService {

    String getLoginUrl(Long userId, String returnUrl);

    LoginResponse.UserInfo getUserInfo(String lsToken);

    String createLSToken(Long userId);

    // ========== 管理级操作（内部使用 admin-token）==========

    /**
     * 在LS中创建用户，并将 lsUserId + lsToken 写回 User 实体
     * 使用 admin-token 调用 LS API
     * @param user 用户对象
     * @param plainPassword 用户明文密码（用于设置 LS 用户密码）
     */
    void syncUserToLS(User user, String plainPassword);

    /**
     * 同步组织到LS
     * 使用 admin-token
     * @param organization 组织对象
     * @param createdBy 组织创建者（用于设置 LS 数据库中的 created_by_id）
     */
    void syncOrganizationToLS(Organization organization, User createdBy);

    // ========== 项目级操作（内部自动读取用户 ls_token）==========

    /**
     * 在LS中创建项目
     * @param project 项目对象
     * @param userId 项目创建者ID，方法内部读取该用户的ls_token
     */
    Long syncProjectToLS(Project project, Long userId);

    /**
     * 删除LS中的项目
     */
    void deleteProject(Long lsProjectId, Long userId);

    /**
     * 挂载本地存储
     */
    Long mountLocalStorage(Long lsProjectId, String localPath, Long userId);

    /**
     * 同步本地存储
     */
    boolean syncLocalStorage(Long storageId, Long userId);

    /**
     * 导入预测结果
     */
    Map<String, Object> importPredictions(Long lsProjectId, 
                                           List<Map<String, Object>> predictions, 
                                           Long userId);

    /**
     * 获取项目 task 数量
     */
    int getProjectTaskCount(Long lsProjectId, Long userId);
}
