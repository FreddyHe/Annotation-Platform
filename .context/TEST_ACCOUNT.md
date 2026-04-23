# 测试账户信息

> 后续所有开发和测试均使用此账户。

| 字段   | 值               |
|--------|------------------|
| 用户名 | admin            |
| 邮箱   | admin@qq.com     |
| 密码   | 123456           |
| 昵称   | 管理员           |
| 组织   | 管理员组织       |

## 获取 JWT Token

```bash
token=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}' | python -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
echo $token
```
