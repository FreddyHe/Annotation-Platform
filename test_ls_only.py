#!/usr/bin/env python3
"""
直接使用已有的 vlm_cleaned.json 测试 Label Studio 设置
跳过 VLM 处理阶段

使用方法:
    export LABEL_STUDIO_API_KEY=your_token
    python test_ls_only.py
"""

import os
import sys
import json
import time
from pathlib import Path

# 配置
LABEL_STUDIO_URL = "http://localhost:5001"
API_KEY = os.getenv('LABEL_STUDIO_API_KEY')

# 使用你最新的清洗结果
VLM_CLEANED_PATH = "/root/autodl-fs/web_biaozhupingtai/data/organizations/xingmu/processed/animaljj/vlm_cleaned.json"
UPLOAD_DIR = "/root/autodl-fs/web_biaozhupingtai/data/organizations/xingmu/uploads/animaljj/extracted"

# 如果 extracted 目录不存在，尝试其他路径
if not Path(UPLOAD_DIR).exists():
    alt_dir = "/root/autodl-fs/web_biaozhupingtai/data/organizations/xingmu/uploads/animaljj"
    if Path(alt_dir).exists():
        UPLOAD_DIR = alt_dir
        print(f"使用备用目录: {UPLOAD_DIR}")

PROJECT_TITLE = "test_animaljj_标注"
LABELS_DEF = {"animal": "动物目标"}  # 根据你的实际标签修改

def main():
    print("=" * 60)
    print("🧪 直接测试 Label Studio 设置（跳过 VLM）")
    print("=" * 60)
    
    # 检查文件
    if not Path(VLM_CLEANED_PATH).exists():
        print(f"❌ VLM 清洗结果不存在: {VLM_CLEANED_PATH}")
        return
    
    if not Path(UPLOAD_DIR).exists():
        print(f"❌ 图片目录不存在: {UPLOAD_DIR}")
        return
    
    if not API_KEY:
        print("❌ 未设置 LABEL_STUDIO_API_KEY")
        return
    
    # 加载 VLM 结果
    with open(VLM_CLEANED_PATH, 'r', encoding='utf-8') as f:
        vlm_data = json.load(f)
    
    print(f"✅ VLM 清洗结果: 总数 {len(vlm_data)}")
    
    # 统计图片
    img_count = len(list(Path(UPLOAD_DIR).glob("*.jpg"))) + len(list(Path(UPLOAD_DIR).glob("*.png")))
    print(f"✅ 图片目录: {UPLOAD_DIR} ({img_count} 张)")
    
    # 导入并测试
    print("\n📝 导入 Label Studio 集成模块...")
    sys.path.insert(0, '/root/autodl-fs/web_biaozhupingtai')
    
    try:
        from label_studio_integration_enhanced import LabelStudioIntegrationAsync
        print("✅ 导入 LabelStudioIntegrationAsync 成功")
        
        ls_integration = LabelStudioIntegrationAsync(
            url=LABEL_STUDIO_URL,
            api_key=API_KEY
        )
        
        print("\n🚀 开始设置 Label Studio 项目...")
        start_time = time.time()
        
        setup_result = ls_integration.setup_project_complete(
            project_title=PROJECT_TITLE,
            labels=LABELS_DEF,
            upload_dir=UPLOAD_DIR,
            vlm_cleaned_path=VLM_CLEANED_PATH
        )
        
        elapsed = time.time() - start_time
        
        print("\n" + "=" * 60)
        print(f"⏱️ 总耗时: {elapsed:.2f} 秒")
        print("=" * 60)
        
        if setup_result['success']:
            print("🎉 成功！")
            print(f"   项目 ID: {setup_result.get('project_id')}")
            print(f"   项目 URL: {setup_result.get('project_url')}")
            stats = setup_result.get('stats', {})
            print(f"   总任务数: {stats.get('total_tasks', 0)}")
            print(f"   成功: {stats.get('success', 0)}")
            print(f"   跳过: {stats.get('skipped', 0)}")
        else:
            print(f"❌ 失败: {setup_result.get('error')}")
            
    except Exception as e:
        print(f"❌ 异常: {e}")
        import traceback
        traceback.print_exc()


if __name__ == "__main__":
    main()