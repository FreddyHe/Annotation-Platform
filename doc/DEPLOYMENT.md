# 非 Docker 部署与模型准备

本次交付是 Annotation Platform（标注平台），不是视频智析平台。仓库只交付代码、模型适配器和注册表；不交付权重、业务图片/视频、数据库、登录账号或密钥。

## 1. 支持的部署方式

当前版本仍有绝对路径约定，请在 **Linux** 上使用 `/root/autodl-fs/Annotation-Platform`，Miniconda 安装到 `/root/miniconda3`。Windows 请使用 Linux 虚拟机/服务器。任意目录部署尚未完成适配。

准备 Git、Java 17、Maven、Node.js 22、npm、Miniconda、ffmpeg、gcc/g++、lsof。GPU 增强模型还需要匹配的 NVIDIA 驱动和 CUDA；不启动这些服务时不占用 GPU。不要在共享 GPU 上直接执行全量 startup.sh。

```bash
mkdir -p /root/autodl-fs
git clone https://github.com/FreddyHe/Annotation-Platform.git /root/autodl-fs/Annotation-Platform
cd /root/autodl-fs/Annotation-Platform
cp .env.example .env.local
chmod 600 .env.local
# 编辑 .env.local：换掉 JWT 示例，设置自己服务器的标注服务公网地址。
openssl rand -hex 48
mkdir -p /root/autodl-fs/uploads backend-springboot/data logs
```

私有仓库需要仓库所有者授予协作者访问权限。不要提交 `.env.local`。本机 AutoDL 下载需使用 `with-redcloud` 包装下载命令，其他机器不依赖这个本地代理工具。

## 2. 模型清单（必须自行准备）

| 能力 | 自备内容及来源 | 默认路径 | 必要性 |
|---|---|---|---|
| 文本提示目标框预标注 | GroundingDINO Swin-T OGC，源码/权重说明：https://github.com/IDEA-Research/GroundingDINO | `/root/autodl-fs/GroundingDINO/weights/groundingdino_swint_ogc.pth` | DINO 预标注必需 |
| DINO 文本编码器 | https://huggingface.co/google-bert/bert-base-uncased 完整快照，含配置、词表和权重 | `/root/autodl-fs/models/bert-base-uncased` | DINO 必需，使用 `DINO_BERT_PATH` 指定离线目录 |
| 增强定位 | https://huggingface.co/nvidia/LocateAnything-3B 完整快照；另准备 https://github.com/NVlabs/Eagle/tree/main/Embodied 的 `locateanything_worker.py` 及其依赖 | `/root/autodl-fs/models/LocateAnything-3B`；源码 `/root/autodl-fs/external/Eagle/Embodied` | 选择 LocateAnything 路由时必需 |
| 图像语义复核 | https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct 完整快照 | `/root/autodl-fs/models/Qwen3-VL-4B-Instruct` | 使用本地 VLM 复核时必需 |
| 自训练检测器 | 自己的数据集训练出的 Ultralytics YOLO `.pt` 权重及对应类别表 | 由训练/模型配置指定；旧默认路径见 application.yml，必须修改为自己的路径 | YOLO 推理/训练相关功能需要 |
| 星目场景能力 | `config/xingmu_model_registry.yaml` 中的模型组及兼容 HTTP 推理服务 | 默认服务 localhost:9000，按自己的服务配置 | 可选；注册表不是模型包，克隆不能自动获得这些能力 |
| 第三方边境模型 | `config/border_model_registry.yaml` 对应厂商授权服务 | 自己的 endpoint/凭据 | 可选，不提供厂商模型 |

模型名不同于权重文件：HF 模型请下载完整仓库快照，不要只下载一个 safetensors。下载时记录 revision、SHA-256 和许可；尤其 LocateAnything 的模型卡标注研究开发用途，不承诺商用授权。第三方源码与模型许可由使用方自行遵守。未配置的模型不要在平台标成可用。

历史接口另有 `YOLO_MODEL_PATH=models/yolov8n.pt` 和 `VLM_MODEL_PATH=models/Qwen-VL-Chat`（来源分别为 Ultralytics 与 `Qwen/Qwen-VL-Chat`）。只有使用这些旧接口才准备它们；Qwen-VL-Chat 不能用 Qwen3-VL 权重直接替换。相对路径以算法服务工作目录为准。

示例（安装 huggingface_hub CLI 后）：

```bash
hf download google-bert/bert-base-uncased --local-dir /root/autodl-fs/models/bert-base-uncased
hf download nvidia/LocateAnything-3B --local-dir /root/autodl-fs/models/LocateAnything-3B
hf download Qwen/Qwen3-VL-4B-Instruct --local-dir /root/autodl-fs/models/Qwen3-VL-4B-Instruct
```

## 3. 环境与构建

Python 环境需要隔离，不能将旧 Torch 2.1 和 vLLM 0.11 混装。

```bash
source /root/miniconda3/etc/profile.d/conda.sh
conda create -n annotation_algo python=3.10 -y
conda activate annotation_algo
pip install -r algorithm-service/requirements-runtime.txt
conda create -n web_annotation python=3.10 -y
conda activate web_annotation
pip install 'label-studio==1.22.0' 'aiohttp==3.11.12'
cd /root/autodl-fs/Annotation-Platform/backend-springboot
mvn -DskipTests package
cd ../frontend-vue
npm ci
npm run build
```

`requirements-runtime.txt` 记录当前算法服务的主要依赖，不是所有平台/驱动组合的完整锁文件。旧 requirements.txt 保留供历史参考。GroundingDINO 使用独立 `groundingdino310` Python 3.10 环境，按上游安装其源码扩展，再安装 Flask；本机使用 Torch 2.1。必须验证 `import groundingdino` 后再启动 DINO。

增强模型单独使用 `LLM_DL` Python 3.11 环境；本机组合为 Torch 2.8.0、transformers 4.57.1、vLLM 0.11.0，另需 FastAPI、uvicorn、Pillow、accelerate、timm 及模型仓库声明的依赖。vLLM 的 CUDA 轮子须匹配硬件。训练脚本默认使用 `xingmu_yolo` 环境（Python、Ultralytics、Torch），请按自身硬件安装，不提供训练数据。

## 4. 先启动平台，不启动大模型

每个命令在不同终端执行，先载入配置：

```bash
cd /root/autodl-fs/Annotation-Platform
set -a; source .env.local; set +a
```

后端：
```bash
cd backend-springboot
java -jar target/platform-backend-1.0.0.jar
```

标注内部服务：
```bash
source /root/miniconda3/etc/profile.d/conda.sh
conda activate web_annotation
export LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED=true
export LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT=/root/autodl-fs
export COLLECT_ANALYTICS=false
label-studio start --host 127.0.0.1 --port 15001 --no-browser
```

标注代理（主题/中文切换由这里提供）：
```bash
/root/miniconda3/envs/web_annotation/bin/python scripts/label_studio_proxy.py
```

算法 API：
```bash
cd algorithm-service
/root/miniconda3/envs/annotation_algo/bin/uvicorn main:app --host 127.0.0.1 --port 8001
```

前端：
```bash
cd frontend-vue
npm run dev -- --host 0.0.0.0 --port 6006
```

访问 `http://服务器:6006`。标注工作区入口是 `LABEL_STUDIO_PUBLIC_URL`（代理 5001），不是内部 15001。首次在标注工作区注册自己的账号；本项目没有随仓库发放的服务器 admin 密码，平台账号使用注册接口/页面创建。后端读取本机标注数据库与 secret 用于集成，因此两者需要同机、同用户运行。数据不会从原服务器自动迁移。

公网上建议通过 HTTPS 反向代理只开放前端和标注代理，限制注册与管理入口；不要直接暴露 H2 控制台、8080、8001、15001 或模型端口。Vite 开发服务器用于验收，正式部署应托管 `frontend-vue/dist` 并将 `/api` 代理至后端，不能只托管静态页面不配 API。HTTP 非安全上下文可能限制浏览器功能。

## 5. 开启所需模型与验收

DINO 准备完成后，在加载 .env.local 的终端执行：
```bash
conda activate groundingdino310
python algorithm-service/dino_model_server.py
```

LocateAnything：`bash scripts/start_locate_anything_service.sh`（先配置 `.env.local` 并 export）。Qwen 使用 startup.sh 中 vLLM 命令或自行部署兼容 OpenAI 的服务到 `LOCAL_VLM_BASE_URL`。`bash startup.sh start` 会尝试启动全部模型服务，只有全部依赖/权重到位且显存允许才使用它；它不是自动安装程序。模型启动失败不能视作预标注可用。

验收顺序：登录 → 上传自己的小规模图片/视频 → 建项目 → 选择已启动的模型 → 预标注 → 检查检测框及类别 → 打开标注工作区编辑/保存 → 回平台确认同步 → 导出数据。训练、评估、边端发布另需训练环境与真实边端服务，不能用首页打开代替全流程验收。

故障排查：模型连接失败查 5003/5008/5010；标注跳转失败查公网地址和 5001 代理；缺少模型文件查清单路径；首次数据为空是正常的新数据库，不要复制原服务器账号或数据到 Git。

## 6. 本次交付的验证边界

构建和检查结果记录在 `doc/github_release_20260907.progress.md`。未在另一台全新机器下载全部模型执行完整复现，因此不能保证克隆后一条命令即运行全部能力；本指南明确列出了必须自行安装/准备的外部依赖。
