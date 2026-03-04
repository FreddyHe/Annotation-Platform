#!/usr/bin/env python3
"""
大文件上传服务 - 支持组织隔离版本
支持分块上传，绕过 Streamlit 的文件大小限制

使用方法:
1. 启动服务: python file_upload_server.py
2. 访问 http://localhost:5002?org=组织名称 上传文件
3. 文件会保存到 /root/autodl-fs/uploads/{组织名称}/ 目录
4. 在 Streamlit 中使用「从服务器路径导入」功能导入

安全特性:
- 每个组织只能访问自己的上传目录
- 组织名称通过 URL 参数传递（由 Streamlit 主应用控制）
"""

from flask import Flask, request, jsonify, render_template_string
from flask_cors import CORS
from pathlib import Path
import os
import hashlib
import shutil
import logging
import re

# 配置
BASE_UPLOAD_DIR = Path("/root/autodl-fs/uploads")
BASE_UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

CHUNK_DIR = Path("/tmp/upload_chunks")
CHUNK_DIR.mkdir(parents=True, exist_ok=True)

# 日志
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Flask(__name__)
CORS(app)  # 允许跨域


def normalize_org_name(name: str) -> str:
    """标准化组织名称，防止路径遍历攻击"""
    if not name:
        return "default"
    # 只保留中文、字母、数字、下划线
    normalized = re.sub(r'[^\w\u4e00-\u9fff]', '_', name.strip())
    # 防止空字符串
    return normalized.lower() if normalized else "default"


def get_org_upload_dir(org_name: str) -> Path:
    """获取组织的上传目录"""
    safe_org = normalize_org_name(org_name)
    org_dir = BASE_UPLOAD_DIR / safe_org
    org_dir.mkdir(parents=True, exist_ok=True)
    return org_dir


# HTML 页面 - 支持组织参数
UPLOAD_PAGE = '''
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>文件上传服务</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            display: flex;
            justify-content: center;
            align-items: center;
            padding: 20px;
        }
        .container {
            background: white;
            padding: 40px;
            border-radius: 16px;
            box-shadow: 0 20px 60px rgba(0,0,0,0.3);
            max-width: 600px;
            width: 100%;
        }
        h1 {
            color: #333;
            margin-bottom: 10px;
            font-size: 24px;
        }
        .subtitle {
            color: #666;
            margin-bottom: 10px;
            font-size: 14px;
        }
        .org-info {
            background: #e8f5e9;
            border: 1px solid #4caf50;
            border-radius: 8px;
            padding: 10px 15px;
            margin-bottom: 20px;
            font-size: 14px;
        }
        .org-info.warning {
            background: #fff3e0;
            border-color: #ff9800;
        }
        .upload-area {
            border: 3px dashed #ddd;
            border-radius: 12px;
            padding: 40px;
            text-align: center;
            cursor: pointer;
            transition: all 0.3s;
            margin-bottom: 20px;
        }
        .upload-area:hover, .upload-area.dragover {
            border-color: #667eea;
            background: #f8f9ff;
        }
        .upload-area.uploading {
            border-color: #ffa500;
            background: #fff8f0;
        }
        .upload-area.disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }
        .upload-icon {
            font-size: 48px;
            margin-bottom: 15px;
        }
        .upload-text {
            color: #666;
            font-size: 16px;
        }
        .upload-hint {
            color: #999;
            font-size: 12px;
            margin-top: 10px;
        }
        input[type="file"] {
            display: none;
        }
        .progress-container {
            display: none;
            margin-top: 20px;
        }
        .progress-bar {
            height: 24px;
            background: #eee;
            border-radius: 12px;
            overflow: hidden;
            margin-bottom: 10px;
        }
        .progress-fill {
            height: 100%;
            background: linear-gradient(90deg, #667eea, #764ba2);
            width: 0%;
            transition: width 0.3s;
            display: flex;
            align-items: center;
            justify-content: center;
            color: white;
            font-size: 12px;
            font-weight: bold;
        }
        .progress-text {
            color: #666;
            font-size: 14px;
            text-align: center;
        }
        .result {
            display: none;
            margin-top: 20px;
            padding: 20px;
            border-radius: 8px;
        }
        .result.success {
            background: #e8f5e9;
            border: 1px solid #4caf50;
        }
        .result.error {
            background: #ffebee;
            border: 1px solid #f44336;
        }
        .result h3 {
            margin-bottom: 10px;
        }
        .result code {
            display: block;
            background: #f5f5f5;
            padding: 10px;
            border-radius: 4px;
            font-family: monospace;
            word-break: break-all;
            margin-top: 10px;
        }
        .file-list {
            margin-top: 30px;
            padding-top: 20px;
            border-top: 1px solid #eee;
        }
        .file-list h3 {
            color: #333;
            margin-bottom: 15px;
            font-size: 16px;
        }
        .file-item {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 10px;
            background: #f9f9f9;
            border-radius: 6px;
            margin-bottom: 8px;
        }
        .file-item .name {
            font-weight: 500;
        }
        .file-item .size {
            color: #666;
            font-size: 12px;
        }
        .btn {
            display: inline-block;
            padding: 8px 16px;
            background: #667eea;
            color: white;
            border: none;
            border-radius: 6px;
            cursor: pointer;
            font-size: 14px;
            text-decoration: none;
        }
        .btn:hover {
            background: #5a6fd6;
        }
        .btn-small {
            padding: 4px 10px;
            font-size: 12px;
        }
        .btn-danger {
            background: #f44336;
        }
        .btn-danger:hover {
            background: #d32f2f;
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>📤 文件上传服务</h1>
        <p class="subtitle">支持大文件上传，自动分块传输</p>
        
        <div class="org-info" id="orgInfo">
            🏢 当前工作区: <strong id="orgName">加载中...</strong>
        </div>
        
        <div class="upload-area" id="uploadArea">
            <div class="upload-icon">📁</div>
            <div class="upload-text">点击选择文件 或 拖拽文件到此处</div>
            <div class="upload-hint">支持 ZIP、图片、视频等格式，无大小限制</div>
            <input type="file" id="fileInput" multiple>
        </div>
        
        <div class="progress-container" id="progressContainer">
            <div class="progress-bar">
                <div class="progress-fill" id="progressFill">0%</div>
            </div>
            <div class="progress-text" id="progressText">准备上传...</div>
        </div>
        
        <div class="result" id="result">
            <h3 id="resultTitle"></h3>
            <p id="resultMessage"></p>
            <code id="resultPath"></code>
        </div>
        
        <div class="file-list" id="fileList">
            <h3>📂 已上传的文件</h3>
            <div id="fileListContent">加载中...</div>
        </div>
    </div>
    
    <script>
        const CHUNK_SIZE = 5 * 1024 * 1024; // 5MB 分块
        
        // 从 URL 获取组织名称
        const urlParams = new URLSearchParams(window.location.search);
        const ORG_NAME = urlParams.get('org') || '';
        
        const uploadArea = document.getElementById('uploadArea');
        const fileInput = document.getElementById('fileInput');
        const progressContainer = document.getElementById('progressContainer');
        const progressFill = document.getElementById('progressFill');
        const progressText = document.getElementById('progressText');
        const result = document.getElementById('result');
        const orgInfo = document.getElementById('orgInfo');
        const orgNameSpan = document.getElementById('orgName');
        
        // 显示组织信息
        if (ORG_NAME) {
            orgNameSpan.textContent = decodeURIComponent(ORG_NAME);
            orgInfo.classList.remove('warning');
        } else {
            orgNameSpan.textContent = '未指定（请从主应用访问）';
            orgInfo.classList.add('warning');
            uploadArea.classList.add('disabled');
        }
        
        // 点击上传区域
        uploadArea.addEventListener('click', () => {
            if (!ORG_NAME) {
                alert('请从主应用（Streamlit）访问此页面');
                return;
            }
            fileInput.click();
        });
        
        // 拖拽
        uploadArea.addEventListener('dragover', (e) => {
            e.preventDefault();
            if (ORG_NAME) uploadArea.classList.add('dragover');
        });
        uploadArea.addEventListener('dragleave', () => {
            uploadArea.classList.remove('dragover');
        });
        uploadArea.addEventListener('drop', (e) => {
            e.preventDefault();
            uploadArea.classList.remove('dragover');
            if (!ORG_NAME) {
                alert('请从主应用（Streamlit）访问此页面');
                return;
            }
            handleFiles(e.dataTransfer.files);
        });
        
        // 选择文件
        fileInput.addEventListener('change', (e) => {
            handleFiles(e.target.files);
        });
        
        async function handleFiles(files) {
            for (const file of files) {
                await uploadFile(file);
            }
            loadFileList();
        }
        
        async function uploadFile(file) {
            const totalChunks = Math.ceil(file.size / CHUNK_SIZE);
            const fileId = Date.now() + '_' + Math.random().toString(36).substr(2, 9);
            
            progressContainer.style.display = 'block';
            result.style.display = 'none';
            uploadArea.classList.add('uploading');
            
            try {
                for (let i = 0; i < totalChunks; i++) {
                    const start = i * CHUNK_SIZE;
                    const end = Math.min(start + CHUNK_SIZE, file.size);
                    const chunk = file.slice(start, end);
                    
                    const formData = new FormData();
                    formData.append('chunk', chunk);
                    formData.append('filename', file.name);
                    formData.append('fileId', fileId);
                    formData.append('chunkIndex', i);
                    formData.append('totalChunks', totalChunks);
                    formData.append('fileSize', file.size);
                    formData.append('org', ORG_NAME);  // 传递组织名称
                    
                    const response = await fetch('/upload_chunk', {
                        method: 'POST',
                        body: formData
                    });
                    
                    if (!response.ok) {
                        throw new Error('上传失败');
                    }
                    
                    const progress = Math.round(((i + 1) / totalChunks) * 100);
                    progressFill.style.width = progress + '%';
                    progressFill.textContent = progress + '%';
                    progressText.textContent = `正在上传 ${file.name}... (${i + 1}/${totalChunks} 块)`;
                }
                
                // 合并文件
                progressText.textContent = '正在合并文件...';
                const mergeResponse = await fetch('/merge_chunks', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        fileId: fileId,
                        filename: file.name,
                        totalChunks: totalChunks,
                        org: ORG_NAME  // 传递组织名称
                    })
                });
                
                const mergeResult = await mergeResponse.json();
                
                if (mergeResult.success) {
                    showResult('success', '✅ 上传成功！', 
                        `文件已保存，可在 Streamlit 中使用「从服务器路径导入」：`,
                        mergeResult.path);
                } else {
                    throw new Error(mergeResult.error || '合并失败');
                }
                
            } catch (error) {
                showResult('error', '❌ 上传失败', error.message, '');
            } finally {
                uploadArea.classList.remove('uploading');
            }
        }
        
        function showResult(type, title, message, path) {
            result.className = 'result ' + type;
            result.style.display = 'block';
            document.getElementById('resultTitle').textContent = title;
            document.getElementById('resultMessage').textContent = message;
            document.getElementById('resultPath').textContent = path;
            document.getElementById('resultPath').style.display = path ? 'block' : 'none';
        }
        
        async function loadFileList() {
            if (!ORG_NAME) {
                document.getElementById('fileListContent').innerHTML = '<p style="color:#999">请从主应用访问</p>';
                return;
            }
            
            try {
                const response = await fetch(`/list_files?org=${encodeURIComponent(ORG_NAME)}`);
                const files = await response.json();
                
                const container = document.getElementById('fileListContent');
                if (files.length === 0) {
                    container.innerHTML = '<p style="color:#999">暂无文件</p>';
                    return;
                }
                
                container.innerHTML = files.map(f => `
                    <div class="file-item">
                        <div>
                            <span class="name">${f.icon} ${f.name}</span>
                            <span class="size">(${f.size})</span>
                        </div>
                        <button class="btn btn-small btn-danger" onclick="deleteFile('${f.name}')">删除</button>
                    </div>
                `).join('');
            } catch (e) {
                document.getElementById('fileListContent').innerHTML = '<p style="color:red">加载失败</p>';
            }
        }
        
        async function deleteFile(filename) {
            if (!confirm(`确定删除 ${filename}？`)) return;
            
            try {
                await fetch('/delete_file', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ filename, org: ORG_NAME })
                });
                loadFileList();
            } catch (e) {
                alert('删除失败');
            }
        }
        
        // 初始化
        loadFileList();
    </script>
</body>
</html>
'''


@app.route('/')
def index():
    return render_template_string(UPLOAD_PAGE)


@app.route('/upload_chunk', methods=['POST'])
def upload_chunk():
    """接收文件分块"""
    try:
        chunk = request.files['chunk']
        filename = request.form['filename']
        file_id = request.form['fileId']
        chunk_index = int(request.form['chunkIndex'])
        total_chunks = int(request.form['totalChunks'])
        org_name = request.form.get('org', '')
        
        # 验证组织名称
        if not org_name:
            return jsonify({'success': False, 'error': '未指定组织'}), 400
        
        # 创建临时目录存放分块（包含组织信息防止冲突）
        safe_org = normalize_org_name(org_name)
        chunk_dir = CHUNK_DIR / safe_org / file_id
        chunk_dir.mkdir(parents=True, exist_ok=True)
        
        # 保存分块
        chunk_path = chunk_dir / f"{chunk_index:06d}"
        chunk.save(str(chunk_path))
        
        logger.info(f"[{safe_org}] 收到分块 {chunk_index + 1}/{total_chunks} for {filename}")
        
        return jsonify({'success': True})
    except Exception as e:
        logger.error(f"上传分块失败: {e}")
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/merge_chunks', methods=['POST'])
def merge_chunks():
    """合并文件分块"""
    try:
        data = request.json
        file_id = data['fileId']
        filename = data['filename']
        total_chunks = data['totalChunks']
        org_name = data.get('org', '')
        
        # 验证组织名称
        if not org_name:
            return jsonify({'success': False, 'error': '未指定组织'}), 400
        
        safe_org = normalize_org_name(org_name)
        chunk_dir = CHUNK_DIR / safe_org / file_id
        
        # 检查所有分块是否存在
        for i in range(total_chunks):
            if not (chunk_dir / f"{i:06d}").exists():
                return jsonify({'success': False, 'error': f'缺少分块 {i}'}), 400
        
        # 获取组织的上传目录
        org_upload_dir = get_org_upload_dir(org_name)
        output_path = org_upload_dir / filename
        
        # 如果文件已存在，添加序号
        if output_path.exists():
            base = output_path.stem
            ext = output_path.suffix
            counter = 1
            while output_path.exists():
                output_path = org_upload_dir / f"{base}_{counter}{ext}"
                counter += 1
        
        with open(output_path, 'wb') as outfile:
            for i in range(total_chunks):
                chunk_path = chunk_dir / f"{i:06d}"
                with open(chunk_path, 'rb') as infile:
                    outfile.write(infile.read())
        
        # 清理分块
        shutil.rmtree(chunk_dir, ignore_errors=True)
        
        logger.info(f"[{safe_org}] ✅ 文件合并完成: {output_path}")
        
        return jsonify({
            'success': True,
            'path': str(output_path),
            'size': output_path.stat().st_size
        })
    except Exception as e:
        logger.error(f"合并失败: {e}")
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/list_files')
def list_files():
    """列出组织已上传的文件"""
    org_name = request.args.get('org', '')
    
    if not org_name:
        return jsonify([])
    
    org_upload_dir = get_org_upload_dir(org_name)
    
    files = []
    for f in sorted(org_upload_dir.iterdir(), key=lambda x: x.stat().st_mtime if x.is_file() else 0, reverse=True):
        if f.is_file():
            size = f.stat().st_size
            if size < 1024:
                size_str = f"{size} B"
            elif size < 1024 * 1024:
                size_str = f"{size / 1024:.1f} KB"
            elif size < 1024 * 1024 * 1024:
                size_str = f"{size / (1024 * 1024):.1f} MB"
            else:
                size_str = f"{size / (1024 * 1024 * 1024):.1f} GB"
            
            ext = f.suffix.lower()
            if ext == '.zip':
                icon = '📦'
            elif ext in ['.jpg', '.jpeg', '.png', '.gif', '.bmp', '.webp']:
                icon = '🖼️'
            elif ext in ['.mp4', '.avi', '.mov', '.mkv', '.wmv']:
                icon = '🎬'
            else:
                icon = '📄'
            
            files.append({
                'name': f.name,
                'size': size_str,
                'icon': icon
            })
    
    return jsonify(files[:50])  # 最多返回50个


@app.route('/delete_file', methods=['POST'])
def delete_file():
    """删除文件"""
    try:
        data = request.json
        filename = data['filename']
        org_name = data.get('org', '')
        
        if not org_name:
            return jsonify({'success': False, 'error': '未指定组织'}), 400
        
        org_upload_dir = get_org_upload_dir(org_name)
        file_path = org_upload_dir / filename
        
        # 安全检查：确保文件在组织目录内
        try:
            file_path.resolve().relative_to(org_upload_dir.resolve())
        except ValueError:
            return jsonify({'success': False, 'error': '非法路径'}), 403
        
        if file_path.exists() and file_path.is_file():
            file_path.unlink()
            logger.info(f"[{normalize_org_name(org_name)}] 已删除: {filename}")
            return jsonify({'success': True})
        else:
            return jsonify({'success': False, 'error': '文件不存在'}), 404
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/health')
def health():
    return jsonify({'status': 'ok', 'base_upload_dir': str(BASE_UPLOAD_DIR)})


if __name__ == '__main__':
    print("=" * 60)
    print("📤 大文件上传服务 (组织隔离版)")
    print("=" * 60)
    print(f"📂 基础上传目录: {BASE_UPLOAD_DIR}")
    print(f"🌐 本地访问: http://localhost:5002?org=组织名称")
    print("=" * 60)
    print("💡 使用方法:")
    print("   1. 从 Streamlit 主应用点击上传按钮（自动带上组织参数）")
    print("   2. 每个组织的文件相互隔离")
    print("   3. 直接访问需要添加 ?org=组织名称 参数")
    print("=" * 60)
    
    app.run(host='0.0.0.0', port=5002, debug=False, threaded=True)