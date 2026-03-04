#!/bin/bash
# 项目重构脚本 - 修复导入问题和架构调整

set -e

PROJECT_DIR="/root/autodl-fs/web_biaozhupingtai"
BACKUP_DIR="/root/autodl-fs/web_biaozhupingtai_backup_$(date +%Y%m%d_%H%M%S)"

echo "="*80
echo "🔧 开始重构项目结构"
echo "="*80

# 1. 备份现有项目
echo "📦 备份现有项目到: $BACKUP_DIR"
cp -r "$PROJECT_DIR" "$BACKUP_DIR"
echo "✅ 备份完成"

# 2. 创建新的目录结构
echo ""
echo "📁 重新组织目录结构..."

mkdir -p "$PROJECT_DIR/backend/pipeline"
mkdir -p "$PROJECT_DIR/scripts"
mkdir -p "$PROJECT_DIR/data/uploads"
mkdir -p "$PROJECT_DIR/data/processed"
mkdir -p "$PROJECT_DIR/data/annotations"
mkdir -p "$PROJECT_DIR/logs"

# 3. 复制新文件
echo ""
echo "📄 复制新文件..."

# 配置文件
cp /home/claude/config.py "$PROJECT_DIR/config.py"

# 脚本文件
cp /home/claude/run_dino_detection.py "$PROJECT_DIR/scripts/"
cp /home/claude/run_vlm_clean.py "$PROJECT_DIR/scripts/"
chmod +x "$PROJECT_DIR/scripts/"*.py

# 推理管道
cp /home/claude/inference_pipeline.py "$PROJECT_DIR/backend/pipeline/"

# 主程序
cp /home/claude/main_simple.py "$PROJECT_DIR/main.py"

# 创建空的__init__.py
touch "$PROJECT_DIR/backend/__init__.py"
touch "$PROJECT_DIR/backend/pipeline/__init__.py"

echo "✅ 文件复制完成"

# 4. 更新配置中的路径
echo ""
echo "🔧 更新配置文件中的脚本路径..."

cd "$PROJECT_DIR"

# 使用sed更新config.py中的路径
sed -i "s|DINO_SCRIPT_PATH = .*|DINO_SCRIPT_PATH = \"$PROJECT_DIR/scripts/run_dino_detection.py\"|" config.py
sed -i "s|VLM_SCRIPT_PATH = .*|VLM_SCRIPT_PATH = \"$PROJECT_DIR/scripts/run_vlm_clean.py\"|" config.py

echo "✅ 配置更新完成"

# 5. 创建新的启动脚本
echo ""
echo "📝 创建新的启动脚本..."

cat > "$PROJECT_DIR/start_new.sh" << 'EOF'
#!/bin/bash
# 新的启动脚本

cd /root/autodl-fs/web_biaozhupingtai

echo "=========================================="
echo "🚀 启动自动标注平台（重构版）"
echo "=========================================="
echo "环境: web_annotation"
echo "端口: 6006"
echo "=========================================="

# 激活环境
source $(conda info --base)/etc/profile.d/conda.sh 2>/dev/null || true
conda activate web_annotation 2>/dev/null || true

# 启动Streamlit
streamlit run main.py \
    --server.port 6006 \
    --server.address 0.0.0.0 \
    --server.headless true \
    --browser.gatherUsageStats false
EOF

chmod +x "$PROJECT_DIR/start_new.sh"

echo "✅ 启动脚本创建完成"

# 6. 显示项目结构
echo ""
echo "📂 新的项目结构:"
tree -L 3 -I '__pycache__|*.pyc' "$PROJECT_DIR" || find "$PROJECT_DIR" -maxdepth 3 -type d

# 7. 测试导入
echo ""
echo "🔍 测试Python导入..."

cd "$PROJECT_DIR"

python3 << 'PYTEST'
import sys
sys.path.insert(0, '/root/autodl-fs/web_biaozhupingtai')

try:
    import config
    print("✅ config 导入成功")
    
    from backend.pipeline.inference_pipeline import InferencePipeline
    print("✅ InferencePipeline 导入成功")
    
    print("\n🎉 所有导入测试通过!")
except Exception as e:
    print(f"❌ 导入失败: {e}")
    import traceback
    traceback.print_exc()
PYTEST

echo ""
echo "="*80
echo "✅ 项目重构完成!"
echo "="*80
echo ""
echo "📋 下一步操作:"
echo ""
echo "1️⃣ 测试新架构:"
echo "   cd $PROJECT_DIR"
echo "   python3 -c 'from backend.pipeline.inference_pipeline import InferencePipeline; print(\"导入成功\")'"
echo ""
echo "2️⃣ 启动项目:"
echo "   cd $PROJECT_DIR"
echo "   ./start_new.sh"
echo ""
echo "3️⃣ 如果有问题,可以恢复备份:"
echo "   rm -rf $PROJECT_DIR"
echo "   mv $BACKUP_DIR $PROJECT_DIR"
echo ""
echo "="*80