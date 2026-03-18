# 服务启动与配置

## 一键状态检查

```bash
echo "=== 端口检查 ==="
for port in 5001 5003 8001 8080 6006; do
  if lsof -ti:$port > /dev/null 2>&1; then
    echo "  端口 $port: ✅ 运行中"
  else
    echo "  端口 $port: ❌ 未运行"
  fi
done
```

## Spring Boot 后端（端口 8080）

```bash
cd /root/autodl-fs/Annotation-Platform/backend-springboot
mvn clean package -DskipTests
kill $(lsof -ti:8080) 2>/dev/null; sleep 3
nohup java -jar target/platform-backend-1.0.0.jar --server.port=8080 > /tmp/springboot.log 2>&1 &
# 等待启动完成
sleep 15 && grep "Started" /tmp/springboot.log
```

**⚠️ 日志必须输出到 `/tmp/springboot.log`，绝不能输出到 `/dev/null`！**

验证：
```bash
curl -s http://localhost:8080/api/v1/auth/login -X POST \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test"}' | head -c 100
```

可行性评估（OVD/VLM）接口验证：
```bash
# 创建 OVD 检测结果（bboxJson 是字符串字段，curl 中尽量先用 {} 验证通路）
curl -s http://localhost:8080/api/v1/feasibility/assessments/1/ovd-results -X POST \
  -H "Content-Type: application/json" \
  -d '{"categoryName":"car","imagePath":"/path/to/image.jpg","promptUsed":"car","detectedCount":2,"averageConfidence":0.82,"bboxJson":"{}","annotatedImagePath":"/path/to/annotated.jpg"}' | head -c 200

# 查询评估下所有 OVD 结果
curl -s http://localhost:8080/api/v1/feasibility/assessments/1/ovd-results | head -c 200

# 创建 VLM 质量评分（关联 ovdResultId=1）
curl -s http://localhost:8080/api/v1/feasibility/ovd-results/1/quality-scores -X POST \
  -H "Content-Type: application/json" \
  -d '{"totalGtEstimated":3,"detected":2,"falsePositive":0,"precisionEstimate":0.9,"recallEstimate":0.67,"bboxQuality":"GOOD","overallVerdict":"feasible","notes":"ok"}' | head -c 200

# 查询 OVD 结果下所有质量评分
curl -s http://localhost:8080/api/v1/feasibility/ovd-results/1/quality-scores | head -c 200
```

## Label Studio（端口 5001）

```bash
# 检查是否已在运行
ps aux | grep label-studio | grep -v grep

# 启动（必须设置两个环境变量）
source $(conda info --base)/etc/profile.d/conda.sh
conda activate web_annotation
export LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED=true
export LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT=/root/autodl-fs
nohup label-studio start --port 5001 --no-browser --log-level INFO > /tmp/labelstudio.log 2>&1 &
```

**⚠️ 每次重启 LS 都必须设置 `LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED` 和 `LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT`，否则本地文件存储不可用。**

## 算法服务（两个进程，两个 conda 环境）

```bash
source $(conda info --base)/etc/profile.d/conda.sh

# DINO 服务 (端口 5003)
conda activate groundingdino310
export CUDA_VISIBLE_DEVICES=""
cd /root/autodl-fs/Annotation-Platform/algorithm-service
nohup python dino_model_server.py > /tmp/dino.log 2>&1 &

# FastAPI 算法服务 (端口 8001)
conda activate algo_service
export CUDA_VISIBLE_DEVICES=""
cd /root/autodl-fs/Annotation-Platform/algorithm-service
nohup uvicorn main:app --host 0.0.0.0 --port 8001 > /tmp/algorithm.log 2>&1 &
```

**⚠️ 必须先 `conda activate`，不要用系统 Python 直接运行！**

## 前端（端口 6006）

```bash
pkill -f "vite" 2>/dev/null; sleep 2
cd /root/autodl-fs/Annotation-Platform/frontend-vue
nohup npx vite --host 0.0.0.0 --port 6006 > /tmp/frontend.log 2>&1 &
```

端口 6006 是 AutoDL 自定义服务默认暴露端口，公网可直接访问。

## LS SQLite 常用查询

```bash
# 查看所有用户
sqlite3 ~/.local/share/label-studio/label_studio.sqlite3 \
  "SELECT id, email, active_organization_id FROM htx_user ORDER BY id;"

# 查看所有组织
sqlite3 ~/.local/share/label-studio/label_studio.sqlite3 \
  "SELECT id, title, created_by_id FROM organization ORDER BY id;"

# 查看组织成员
sqlite3 ~/.local/share/label-studio/label_studio.sqlite3 \
  "SELECT organization_id, user_id FROM organizations_organizationmember ORDER BY organization_id, user_id;"

# 查看所有项目
sqlite3 ~/.local/share/label-studio/label_studio.sqlite3 \
  "SELECT id, title, organization_id, created_by_id, substr(label_config, 1, 60) FROM project ORDER BY id;"

# 查看 Token
sqlite3 ~/.local/share/label-studio/label_studio.sqlite3 \
  "SELECT t.key, t.user_id, u.email, u.active_organization_id FROM authtoken_token t JOIN htx_user u ON t.user_id = u.id;"
```

## 杀进程备忘

```bash
# 按端口杀
kill $(lsof -ti:8080) 2>/dev/null

# 按进程名杀
pkill -f "vite"
pkill -f "label-studio"
pkill -f "dino_model_server"
pkill -f "uvicorn"
```
