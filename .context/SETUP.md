# 服务启动与配置

## 一键启动脚本（推荐）

项目根目录提供了 `startup.sh`，可一键管理全部 5 个服务。脚本会自动跳过已运行的服务。

```bash
cd /root/autodl-fs/Annotation-Platform

./startup.sh            # 启动所有未运行的服务（默认）
./startup.sh status     # 检查所有服务状态
./startup.sh stop       # 停止所有服务
./startup.sh restart    # 重启所有服务
./startup.sh build      # 重新编译后端 JAR 并启动所有服务
```

**服务端口一览：**

| 端口 | 服务 | 日志文件 |
|------|------|----------|
| 8080 | Spring Boot 后端 | `/tmp/springboot.log` |
| 5001 | Label Studio | `/tmp/labelstudio.log` |
| 5003 | DINO 模型服务 | `/tmp/dino.log` |
| 8001 | 算法服务 FastAPI | `/tmp/algorithm.log` |
| 6006 | 前端 Vue | `/tmp/frontend.log` |

> **注意**：DINO 模型约 662MB，CPU 模式加载需要 30~60 秒，脚本会自动等待。

## 一键状态检查（手动）

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

### 开发验证（临时内存库与改端口）

当遇到 H2 文件锁或端口冲突时，可使用临时参数进行隔离验证：

```bash
cd /root/autodl-fs/Annotation-Platform/backend-springboot
mvn clean package -DskipTests
kill $(lsof -ti:8090) 2>/dev/null; sleep 3
nohup java -jar target/platform-backend-1.0.0.jar \
  --server.port=8090 \
  --spring.datasource.url="jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1" \
  > /tmp/springboot-dev.log 2>&1 &
sleep 12 && grep "Started" /tmp/springboot-dev.log

# 接口快速验证（DatasetSearchResult）
curl -s -X POST http://localhost:8090/api/v1/feasibility/assessments/1/datasets \
  -H "Content-Type: application/json" \
  -d '{"categoryName":"大型石块","source":"ROBOFLOW","datasetName":"Rock Detection Dataset","relevanceScore":0.85}' | head -c 200
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

# 需求解析（LLM + VLM 透传由后端自动完成，需要 Authorization）
token=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test"}' | python -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

# 创建可行性评估（替换 rawRequirement）
assessmentId=$(curl -s -X POST http://localhost:8080/api/v1/feasibility/assessments \
  -H "Authorization: Bearer $token" \
  -H "Content-Type: application/json" \
  -d '{"assessmentName":"解析测试","rawRequirement":"矿道内大型异物检测+销钉松动+线缆断裂"}' \
  | python -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

# 串联解析（状态应变为 PARSED，并自动写入 categories）
curl -s -X POST http://localhost:8080/api/v1/feasibility/assessments/$assessmentId/parse \
  -H "Authorization: Bearer $token" | head -c 300

curl -s http://localhost:8080/api/v1/feasibility/assessments/$assessmentId/categories \
  -H "Authorization: Bearer $token" | head -c 600
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

### 依赖安装（首次或更新时）

```bash
source $(conda info --base)/etc/profile.d/conda.sh
conda activate algo_service
# 安装Roboflow搜索所需的HTML解析库
pip install beautifulsoup4 lxml -q
```

### 启动服务

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

端口 6006 是 AutoDL 自定义服务默认暴露端口，公网可直接访问。`vite.config.js` 默认端口已改为 6006（2026-04-13），无需再通过 `--port 6006` 覆盖，但保留命令行参数以明确意图。

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

## 可行性评估完整流程验证（阶段4-5）

### 动态资源估算验证

```bash
# 获取token
token=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test"}' | python -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

# 提交用户判断（数据集匹配度）
curl -s -X POST http://localhost:8080/api/v1/feasibility/assessments/1/user-judgment \
  -H "Authorization: Bearer $token" \
  -H "Content-Type: application/json" \
  -d '{"datasetMatchLevel":"PARTIAL_MATCH","userNotes":"数据集部分相关，需要补充标注"}' | head -c 300

# 资源估算（调用算法服务动态计算）
curl -s -X POST http://localhost:8080/api/v1/feasibility/assessments/1/estimate \
  -H "Authorization: Bearer $token" | head -c 300

# 查询资源估算结果（包含publicDatasetImages和trainingApproach）
curl -s http://localhost:8080/api/v1/feasibility/assessments/1/resource-estimations \
  -H "Authorization: Bearer $token" | python -m json.tool | head -c 500
```

### AI可行性报告生成验证

```bash
# 生成AI报告（调用LLM，耗时10-30秒）
curl -s -X POST http://localhost:8080/api/v1/feasibility/assessments/1/ai-report \
  -H "Authorization: Bearer $token" | python -m json.tool | head -c 1000

# 验证报告包含Markdown格式内容
curl -s -X POST http://localhost:8080/api/v1/feasibility/assessments/1/ai-report \
  -H "Authorization: Bearer $token" | python -c "import sys,json; print(json.load(sys.stdin)['data']['report'][:500])"
```

### 算法服务直接验证

```bash
# 测试动态资源估算（算法服务）
curl -s -X POST http://localhost:8001/api/v1/feasibility/estimate-resources \
  -H "Content-Type: application/json" \
  -d '{
    "categories": [
      {
        "categoryName": "大型石块",
        "feasibilityBucket": "CUSTOM_MEDIUM",
        "sceneComplexity": "medium",
        "sceneDiversity": "medium",
        "datasetMatchLevel": "PARTIAL_MATCH",
        "availablePublicSamples": 3390
      }
    ]
  }' | python -m json.tool | head -c 800

# 测试AI报告生成（算法服务）
curl -s -X POST http://localhost:8001/api/v1/feasibility/generate-report \
  -H "Content-Type: application/json" \
  -d '{
    "assessmentName": "测试评估",
    "rawRequirement": "矿道异物检测",
    "categories": [{"categoryName": "石块", "feasibilityBucket": "CUSTOM_LOW"}],
    "resourceEstimations": [{"categoryName": "石块", "estimatedImages": 240, "trainingApproach": "微调"}]
  }' | python -c "import sys,json; print(json.load(sys.stdin)['report'][:300])"
```

## 杀进程备忘

```bash
# 按端口杀
kill $(lsof -ti:8080) 2>/dev/null
lsof -ti:8001 | xargs kill -9 2>/dev/null

# 按进程名杀
pkill -f "vite"
pkill -f "label-studio"
pkill -f "dino_model_server"
pkill -f "uvicorn"
```


## Label Studio 排查命令（2026-03-25 新增）

### 查看 LS 日志错误
```bash
tail -100 /tmp/labelstudio.log | grep -E "ERROR|WARNING|500"
```

### 查找孤立的 storage 记录（关联项目已删除）
```bash
sqlite3 /root/.local/share/label-studio/label_studio.sqlite3 \
  "SELECT m.id, m.path, i.project_id FROM io_storages_localfilesmixin m JOIN io_storages_localfilesimportstorage i ON m.id = i.localfilesmixin_ptr_id LEFT JOIN project p ON i.project_id = p.id WHERE p.id IS NULL;"
```

### 查看 local storage 与项目的关联关系
```bash
sqlite3 /root/.local/share/label-studio/label_studio.sqlite3 \
  "SELECT m.id, m.path, i.project_id, p.title FROM io_storages_localfilesmixin m JOIN io_storages_localfilesimportstorage i ON m.id = i.localfilesmixin_ptr_id LEFT JOIN project p ON i.project_id = p.id ORDER BY m.id DESC LIMIT 10;"
```

### 手动测试 Label Studio API
```bash
# 获取 LS 用户 token（替换 user_id）
sqlite3 /root/.local/share/label-studio/label_studio.sqlite3 "SELECT key FROM authtoken_token WHERE user_id=24;"

# 测试项目是否存在
curl -s -w "\n%{http_code}" -H "Authorization: Token <TOKEN>" "http://localhost:5001/api/projects/44/"

# 测试 tasks API（空项目返回 404，有 tasks 返回 JSON 数组）
curl -s -w "\n%{http_code}" -H "Authorization: Token <TOKEN>" "http://localhost:5001/api/projects/44/tasks"

# 测试 local storage 列表
curl -s -H "Authorization: Token <TOKEN>" "http://localhost:5001/api/storages/localfiles?project=44"
```

### 清理孤立 storage 记录（谨慎操作）
```bash
# 先查出孤立记录的 ID
sqlite3 /root/.local/share/label-studio/label_studio.sqlite3 \
  "SELECT m.id FROM io_storages_localfilesmixin m JOIN io_storages_localfilesimportstorage i ON m.id = i.localfilesmixin_ptr_id LEFT JOIN project p ON i.project_id = p.id WHERE p.id IS NULL;"

# 删除（替换 ID 列表）
sqlite3 /root/.local/share/label-studio/label_studio.sqlite3 \
  "DELETE FROM io_storages_localfilesimportstorage WHERE localfilesmixin_ptr_id IN (1, 2, 12, 13, 14);"
sqlite3 /root/.local/share/label-studio/label_studio.sqlite3 \
  "DELETE FROM io_storages_localfilesmixin WHERE id IN (1, 2, 12, 13, 14);"
```

## 训练功能验证（2026-03-25 新增）

### 验证训练 API 接口
```bash
# 获取训练状态（替换 project_id 和 JWT token）
curl -s -H "Authorization: Bearer <JWT_TOKEN>" \
  "http://localhost:8080/api/v1/projects/354/training/status"

# 启动训练（替换 project_id 和 JWT token）
curl -s -X POST -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"modelName":"test-model","epochs":100,"batchSize":16,"imageSize":640,"trainRatio":70,"valRatio":20,"testRatio":10}' \
  "http://localhost:8080/api/v1/projects/354/training/start"
```

### 验证导出功能（训练数据准备）
```bash
# 导出 COCO 格式（替换 project_id 和 JWT token）
curl -s -X POST -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"projectId":354,"format":"coco"}' \
  "http://localhost:8080/api/v1/projects/354/export"

# 导出 YOLO 格式
curl -s -X POST -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"projectId":354,"format":"yolo"}' \
  "http://localhost:8080/api/v1/projects/354/export"