# 第一阶段总结：数据库设计与架构规划

## ✅ 完成内容

### 1. 数据库 ER 图设计
- ✅ 设计了 7 个核心实体：Organization, User, Session, Project, ProjectImage, AnnotationTask, DetectionResult
- ✅ 定义了实体之间的关系（一对一、一对多、多对一）
- ✅ 使用 JSON 类型存储复杂数据（labels, result_data, parameters）
- ✅ 添加了必要的索引以提升查询性能

### 2. JPA 实体类实现
已创建 7 个实体类，位于 `/root/autodl-fs/Annotation-Platform/backend-springboot/src/main/java/com/annotation/platform/entity/`：

1. **Organization.java** - 组织实体
2. **User.java** - 用户实体
3. **Session.java** - 会话实体
4. **Project.java** - 项目实体
5. **ProjectImage.java** - 项目图片实体
6. **AnnotationTask.java** - 标注任务实体
7. **DetectionResult.java** - 检测结果实体

### 3. Spring Boot 项目包结构设计
- ✅ 设计了完整的分层架构（Controller, Service, Repository, DTO）
- ✅ 定义了 8 个核心模块（认证、用户、组织、项目、上传、算法、Label Studio、统计）
- ✅ 规划了配置、安全、异常处理、工具类等辅助模块

### 4. 核心业务 API 接口清单
设计了 8 大模块共 30+ 个 RESTful API 接口：

1. **认证模块** (4 个接口)
   - 用户注册、登录、登出、刷新 Token

2. **用户管理模块** (3 个接口)
   - 获取当前用户信息、更新用户信息、修改密码

3. **组织管理模块** (2 个接口)
   - 获取组织列表、获取组织详情

4. **项目管理模块** (6 个接口)
   - 创建、查询、更新、删除项目
   - 获取项目图片列表、项目详情

5. **文件上传模块** (5 个接口)
   - 分块上传、合并文件、获取进度
   - 获取文件列表、删除文件

6. **算法任务模块** (6 个接口)
   - 运行 DINO 检测、VLM 清洗、YOLO 训练
   - 获取任务状态、任务列表、检测结果

7. **Label Studio 集成模块** (3 个接口)
   - 导入到 Label Studio、从 Label Studio 同步
   - 获取 Label Studio 登录链接

8. **统计分析模块** (2 个接口)
   - 获取项目统计、获取组织统计

## 📁 文件结构

```
/root/autodl-fs/Annotation-Platform/backend-springboot/
├── docs/
│   ├── database-design.md          # 数据库 ER 图设计
│   ├── package-structure.md       # 包结构设计
│   └── api-design.md             # API 接口清单
└── src/main/java/com/annotation/platform/
    └── entity/                   # JPA 实体类
        ├── Organization.java
        ├── User.java
        ├── Session.java
        ├── Project.java
        ├── ProjectImage.java
        ├── AnnotationTask.java
        └── DetectionResult.java
```

## 🎯 设计亮点

### 1. 高内聚低耦合
- 实体类只负责数据映射，不包含业务逻辑
- Service 层封装业务逻辑，Controller 层只负责请求处理
- DTO 层隔离内部数据结构，避免直接暴露 Entity

### 2. 可扩展性
- 使用 JSON 类型存储灵活的数据结构（labels, parameters, result_data）
- 任务类型使用枚举，便于扩展新的算法类型
- 统一的响应格式和错误码体系

### 3. 性能优化
- 为常用查询字段添加索引
- 使用懒加载（FetchType.LAZY）减少不必要的查询
- 支持分页查询，避免一次性加载大量数据

### 4. 安全性
- Session 表使用 Token 作为主键，支持 JWT 认证
- 用户密码使用哈希存储
- 外键约束保证数据完整性

### 5. 微服务友好
- 实体类设计清晰，易于序列化为 JSON
- API 接口遵循 RESTful 规范
- 支持与算法服务、Label Studio 的集成

## 🔧 技术选型

| 组件 | 技术 | 说明 |
|------|------|------|
| 后端框架 | Spring Boot 3.x | 企业级 Java 框架 |
| ORM | Spring Data JPA + Hibernate | 对象关系映射 |
| 数据库 | MySQL 8.0+ / PostgreSQL 14+ | 关系型数据库 |
| 安全 | Spring Security + JWT | 认证与授权 |
| 文档 | SpringDoc OpenAPI | API 文档自动生成 |
| 工具 | Lombok, MapStruct, Hutool | 提高开发效率 |

## 📋 下一步计划

第二阶段将实现以下内容：
1. Spring Boot 项目初始化（pom.xml, application.yml）
2. 配置类实现（Security, JPA, 文件存储等）
3. Repository 层实现
4. DTO 类实现
5. 基础 Service 层实现（认证、用户、组织）

## 📊 数据库迁移建议

建议使用 Flyway 或 Liquibase 进行数据库版本管理：

```sql
-- V1__init_schema.sql
CREATE TABLE organizations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) UNIQUE NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    ls_org_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(200) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    organization_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_login TIMESTAMP,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    ls_user_id BIGINT,
    ls_token VARCHAR(255),
    ls_org_id BIGINT,
    FOREIGN KEY (organization_id) REFERENCES organizations(id)
);

-- 其他表的 SQL 脚本...
```

## 🎓 架构原则

1. **单一职责原则**：每个类只负责一个功能
2. **开闭原则**：对扩展开放，对修改关闭
3. **依赖倒置原则**：依赖抽象而非具体实现
4. **接口隔离原则**：使用细粒度的接口
5. **迪米特法则**：最少知识原则

---

**第一阶段已完成！请确认设计方案，然后我们可以进入第二阶段的实现。**
