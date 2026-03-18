# 开发规范

## Git 规范

### 仓库信息

- **远程仓库**: `gitee.com:freddywards/annotation-platform.git`
- **本地路径**: `/root/autodl-fs/Annotation-Platform/`
- **默认分支**: `master`

### 提交格式

```
<type>: <中文描述>
```

| type | 用途 |
|------|------|
| `fix` | 修复 bug |
| `feat` | 新增功能 |
| `refactor` | 重构代码（不改功能） |
| `docs` | 文档更新 |
| `style` | 代码格式调整 |
| `test` | 测试相关 |
| `chore` | 构建/工具相关 |

示例：
- `fix: 修复概览页面最近项目显示问题`
- `feat: 新增可行性评估类别管理 CRUD 接口`
- `refactor: 项目名称唯一性从全局唯一改为组织内唯一`

### 提交流程

```bash
cd /root/autodl-fs/Annotation-Platform
git add .
git commit -m "<type>: <描述>"
git push origin master
```

## 代码风格

### Java（Spring Boot）

- **Lombok**: 所有实体类用 `@Data`、`@Builder`、`@NoArgsConstructor`、`@AllArgsConstructor`；Service 类用 `@Slf4j` + `@RequiredArgsConstructor`
- **Controller**: 用 `@RestController` + `@RequestMapping`，不要在 RequestMapping 里写 `/api/v1` 前缀（context-path 已配置）
- **响应格式**: 统一使用 `Result<T>` 包装类，`Result.success(data)` 或 `Result.error(message)`
- **异常处理**: 资源不存在抛 `ResourceNotFoundException`，业务错误抛 `BusinessException`
- **事务**: Service 层写操作加 `@Transactional`
- **DTO 分离**: 创建用 `CreateXxxRequest`（有 `@NotBlank` 等校验），更新用 `UpdateXxxRequest`（字段全部可选），响应用 `XxxResponse`
- **权限**: 从 `HttpServletRequest` 的 attribute 中获取 `userId` 和 `organizationId`（JWT 过滤器设置的）
- **白名单**: 新增需要匿名访问的接口时，同步更新 `SecurityConfig` 的 `requestMatchers(...).permitAll()`

### Vue（前端）

- **API 封装**: 所有后端调用写在 `src/api/index.js`，不在组件里直接写 fetch
- **路由**: 在 `src/router/index.js` 注册，侧边栏菜单在 `src/layout/index.vue` 添加
- **请求前缀**: 前端 API 调用路径以 `/api/v1` 开头
- **Token**: 存在 `localStorage.getItem('token')`，请求头加 `Authorization: Bearer ${token}`

### Python（算法服务）

- **路由模块化**: 新功能在 `routers/` 下新建文件，在 `main.py` 中 `include_router` 注册
- **参数传递**: MultipartFile 转发时数值参数必须用 `String.valueOf()` 转字符串，不能直接传 Integer
