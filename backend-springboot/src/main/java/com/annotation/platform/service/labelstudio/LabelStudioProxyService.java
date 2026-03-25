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
     * 删除LS项目关联的所有local storage
     * 必须在删除项目之前调用，避免产生孤立的storage记录
     * @param lsProjectId Label Studio项目ID
     * @param userId 用户ID
     */
    void deleteLocalStorageByProject(Long lsProjectId, Long userId);

    /**
     * 更新LS项目的label_config配置
     * @param lsProjectId Label Studio项目ID
     * @param labels 新的标签列表
     * @param userId 用户ID
     */
    void updateProjectLabelConfig(Long lsProjectId, List<String> labels, Long userId);

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

    /**
     * 获取项目审核统计
     * @param lsProjectId Label Studio项目ID
     * @param userId 用户ID
     * @return 审核统计信息
     */
    Map<String, Object> getProjectReviewStats(Long lsProjectId, Long userId);

    /**
     * 获取项目审核结果
     * @param lsProjectId Label Studio项目ID
     * @param userId 用户ID
     * @return 审核结果列表
     */
    Map<String, Object> getProjectReviewResults(Long lsProjectId, Long userId);

    /**
     * 导出项目标注结果
     * @param lsProjectId Label Studio项目ID
     * @param userId 用户ID
     * @param format 导出格式 (coco, yolo, voc, csv, json)
     * @return 导出的标注数据
     */
    List<Map<String, Object>> exportAnnotations(Long lsProjectId, Long userId, String format);
}
