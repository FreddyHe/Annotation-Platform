#!/bin/bash
# DINO详细调试脚本

IMAGE_DIR="$1"
LABELS="$2"

if [ -z "$IMAGE_DIR" ] || [ -z "$LABELS" ]; then
    echo "用法: $0 <图片目录> <类别列表>"
    exit 1
fi

echo "========================================"
echo "🔍 DINO 详细调试"
echo "========================================"
echo ""

# 1. 检查Python脚本是否存在
SCRIPT_PATH="/root/autodl-fs/web_biaozhupingtai/scripts/run_dino_detection.py"
echo "1️⃣ 检查脚本文件..."
if [ -f "$SCRIPT_PATH" ]; then
    echo "   ✅ 脚本存在: $SCRIPT_PATH"
else
    echo "   ❌ 脚本不存在: $SCRIPT_PATH"
    exit 1
fi

# 2. 检查配置文件
echo ""
echo "2️⃣ 检查配置文件..."
CONFIG_PATH="/root/autodl-fs/GroundingDINO/groundingdino/config/GroundingDINO_SwinT_OGC.py"
WEIGHTS_PATH="/root/autodl-fs/GroundingDINO/weights/groundingdino_swint_ogc.pth"

if [ -f "$CONFIG_PATH" ]; then
    echo "   ✅ 配置存在: $CONFIG_PATH"
else
    echo "   ❌ 配置不存在: $CONFIG_PATH"
    exit 1
fi

if [ -f "$WEIGHTS_PATH" ]; then
    echo "   ✅ 权重存在: $WEIGHTS_PATH"
    ls -lh "$WEIGHTS_PATH"
else
    echo "   ❌ 权重不存在: $WEIGHTS_PATH"
    exit 1
fi

# 3. 检查图片
echo ""
echo "3️⃣ 检查图片目录..."
if [ -d "$IMAGE_DIR" ]; then
    echo "   ✅ 目录存在: $IMAGE_DIR"
    echo "   📷 图片列表:"
    find "$IMAGE_DIR" -maxdepth 1 -type f \( -iname "*.jpg" -o -iname "*.jpeg" -o -iname "*.png" -o -iname "*.bmp" \) | head -5
else
    echo "   ❌ 目录不存在: $IMAGE_DIR"
    exit 1
fi

# 4. 检查conda环境
echo ""
echo "4️⃣ 检查conda环境..."
if conda env list | grep -q "groundingdino310"; then
    echo "   ✅ groundingdino310 环境存在"
else
    echo "   ❌ groundingdino310 环境不存在"
    echo "   可用环境:"
    conda env list
    exit 1
fi

# 5. 测试Python脚本语法
echo ""
echo "5️⃣ 测试脚本语法..."
conda run -n groundingdino310 python -m py_compile "$SCRIPT_PATH" 2>&1
if [ $? -eq 0 ]; then
    echo "   ✅ 脚本语法正确"
else
    echo "   ❌ 脚本有语法错误"
    exit 1
fi

# 6. 运行脚本并捕获所有输出
echo ""
echo "6️⃣ 运行DINO检测（详细模式）..."
echo "========================================"
echo ""

OUTPUT_FILE="/tmp/debug_dino_output.json"

conda run -n groundingdino310 python "$SCRIPT_PATH" \
    --config "$CONFIG_PATH" \
    --weights "$WEIGHTS_PATH" \
    --image-dir "$IMAGE_DIR" \
    --labels "$LABELS" \
    --output "$OUTPUT_FILE" \
    --box-threshold 0.3 \
    2>&1 | tee /tmp/dino_debug.log

EXIT_CODE=${PIPESTATUS[0]}

echo ""
echo "========================================"
echo "7️⃣ 检查结果..."
echo ""
echo "   退出码: $EXIT_CODE"

if [ -f "$OUTPUT_FILE" ]; then
    FILE_SIZE=$(stat -f%z "$OUTPUT_FILE" 2>/dev/null || stat -c%s "$OUTPUT_FILE" 2>/dev/null)
    echo "   ✅ 输出文件已生成: $OUTPUT_FILE"
    echo "   📊 文件大小: $FILE_SIZE 字节"
    
    if [ $FILE_SIZE -gt 2 ]; then
        echo ""
        echo "   📝 文件内容预览:"
        head -20 "$OUTPUT_FILE"
        echo ""
        echo "   📊 检测统计:"
        python3 << EOF
import json
try:
    with open("$OUTPUT_FILE") as f:
        data = json.load(f)
    print(f"      检测数量: {len(data)}")
    if len(data) > 0:
        print(f"      第一个结果: {data[0]}")
except Exception as e:
    print(f"      ❌ 无法解析JSON: {e}")
EOF
    else
        echo "   ⚠️  文件为空"
    fi
else
    echo "   ❌ 输出文件未生成"
    echo ""
    echo "   🔍 检查完整日志:"
    echo "   cat /tmp/dino_debug.log"
fi

echo ""
echo "========================================"
echo "✅ 调试完成"
echo "========================================"