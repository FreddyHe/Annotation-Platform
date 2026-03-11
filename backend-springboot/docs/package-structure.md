# Spring Boot 项目包结构设计

## 📦 完整包结构

```
com.annotation.platform
├── AnnotationPlatformApplication.java          # 主启动类
│
├── config/                                    # 配置类
│   ├── SecurityConfig.java                     # Spring Security + JWT 配置
│   ├── JpaConfig.java                         # JPA 配置
│   ├── RedisConfig.java                       # Redis 配置（可选）
│   ├── FileStorageConfig.java                 # 文件存储配置
│   ├── AlgorithmServiceConfig.java            # 算法服务客户端配置
│   └── LabelStudioConfig.java                # Label Studio 集成配置
│
├── entity/                                    # JPA 实体类
│   ├── Organization.java                      # 组织实体
│   ├── User.java                              # 用户实体
│   ├── Session.java                           # 会话实体
│   ├── Project.java                           # 项目实体
│   ├── ProjectImage.java                      # 项目图片实体
│   ├── AnnotationTask.java                    # 标注任务实体
│   └── DetectionResult.java                   # 检测结果实体
│
├── repository/                                 # 数据访问层
│   ├── OrganizationRepository.java
│   ├── UserRepository.java
│   ├── SessionRepository.java
│   ├── ProjectRepository.java
│   ├── ProjectImageRepository.java
│   ├── AnnotationTaskRepository.java
│   └── DetectionResultRepository.java
│
├── dto/                                       # 数据传输对象
│   ├── request/                               # 请求 DTO
│   │   ├── auth/
│   │   │   ├── LoginRequest.java
│   │   │   └── RegisterRequest.java
│   │   ├── project/
│   │   │   ├── CreateProjectRequest.java
│   │   │   ├── UpdateProjectRequest.java
│   │   │   └── ImportToLabelStudioRequest.java
│   │   ├── upload/
│   │   │   ├── UploadChunkRequest.java
│   │   │   └── MergeChunksRequest.java
│   │   └── algorithm/
│   │       ├── RunDinoDetectionRequest.java
│   │       ├── RunVlmCleaningRequest.java
│   │       └── TrainYoloRequest.java
│   │
│   └── response/                              # 响应 DTO
│       ├── auth/
│   │   ├── LoginResponse.java
│   │   └── RegisterResponse.java
│       ├── project/
│   │   ├── ProjectDetailResponse.java
│   │   ├── ProjectListResponse.java
│       │   └── ProjectStatisticsResponse.java
│       ├── upload/
│       │   ├── UploadProgressResponse.java
│       │   └── FileListResponse.java
│       └── algorithm/
│           ├── TaskStatusResponse.java
│           └── DetectionResultResponse.java
│
├── service/                                   # 业务逻辑层
│   ├── auth/                                  # 认证服务
│   │   ├── AuthService.java
│   │   ├── JwtService.java
│   │   └── PasswordService.java
│   │
│   ├── user/                                  # 用户服务
│   │   ├── UserService.java
│   │   └── OrganizationService.java
│   │
│   ├── project/                               # 项目服务
│   │   ├── ProjectService.java
│   │   ├── ProjectImageService.java
│   │   └── ProjectStatisticsService.java
│   │
│   ├── upload/                                # 文件上传服务
│   │   ├── FileUploadService.java
│   │   ├── ChunkUploadService.java
│   │   └── FileStorageService.java
│   │
│   ├── algorithm/                             # 算法服务
│   │   ├── AlgorithmServiceClient.java          # 算法服务 HTTP 客户端
│   │   ├── DinoDetectionService.java
│   │   ├── VlmCleaningService.java
│   │   └── YoloTrainingService.java
│   │
│   ├── labelstudio/                           # Label Studio 集成
│   │   ├── LabelStudioService.java
│   │   ├── LabelStudioAuthService.java
│   │   └── LabelStudioProjectService.java
│   │
│   └── session/                              # 会话管理
│       └── SessionService.java
│
├── controller/                                # 控制器层
│   ├── AuthController.java                    # 认证控制器
│   ├── UserController.java                    # 用户管理控制器
│   ├── OrganizationController.java            # 组织管理控制器
│   ├── ProjectController.java                 # 项目管理控制器
│   ├── FileUploadController.java              # 文件上传控制器
│   ├── AlgorithmController.java              # 算法任务控制器
│   └── LabelStudioController.java            # Label Studio 集成控制器
│
├── security/                                  # 安全相关
│   ├── JwtAuthenticationFilter.java           # JWT 认证过滤器
│   ├── JwtAuthenticationEntryPoint.java       # JWT 认证入口点
│   └── UserDetailsServiceImpl.java            # 用户详情服务实现
│
├── exception/                                 # 异常处理
│   ├── GlobalExceptionHandler.java            # 全局异常处理器
│   ├── ResourceNotFoundException.java        # 资源未找到异常
│   ├── AuthenticationException.java          # 认证异常
│   ├── BusinessException.java              # 业务异常
│   └── FileUploadException.java              # 文件上传异常
│
├── util/                                      # 工具类
│   ├── FileUtils.java                        # 文件工具类
│   ├── JsonUtils.java                        # JSON 工具类
│   ├── ValidationUtils.java                  # 校验工具类
│   └── DateUtils.java                        # 日期工具类
│
├── constant/                                  # 常量定义
│   ├── SecurityConstants.java                 # 安全常量
│   ├── FileConstants.java                    # 文件常量
│   ├── AlgorithmConstants.java              # 算法常量
│   └── LabelStudioConstants.java            # Label Studio 常量
│
└── enums/                                     # 枚举类
    ├── Role.java                            # 角色枚举
    ├── TaskStatus.java                      # 任务状态枚举
    ├── ProjectStatus.java                   # 项目状态枚举
    └── ImageStatus.java                    # 图片状态枚举
```

## 🎯 分层架构说明

### 1. **Controller 层**
- 负责接收 HTTP 请求
- 参数校验
- 调用 Service 层处理业务
- 返回统一格式的响应

### 2. **Service 层**
- 核心业务逻辑
- 事务管理
- 调用 Repository 层访问数据库
- 调用外部服务（算法服务、Label Studio）

### 3. **Repository 层**
- 继承 JpaRepository
- 自定义查询方法
- 数据库操作

### 4. **DTO 层**
- Request: 接收前端请求参数
- Response: 返回给前端的数据
- 隔离 Entity，避免直接暴露数据库结构

### 5. **Config 层**
- Spring Security 配置
- JPA 配置
- 外部服务客户端配置

### 6. **Security 层**
- JWT 认证过滤器
- 权限控制
- 用户认证

### 7. **Exception 层**
- 全局异常处理
- 自定义业务异常
- 统一错误响应格式

## 🔧 技术栈

- **框架**: Spring Boot 3.x
- **数据库**: MySQL 8.0+ / PostgreSQL 14+
- **ORM**: Spring Data JPA + Hibernate
- **安全**: Spring Security + JWT
- **文档**: SpringDoc OpenAPI (Swagger)
- **缓存**: Redis (可选)
- **工具**: Lombok, MapStruct, Hutool
- **测试**: JUnit 5, Mockito
