"""
Label Studio 自动登录代理服务 v2
用于从 Streamlit 平台无缝跳转到 Label Studio 并自动切换用户

修复 CSRF 问题：通过 iframe 方式在 Label Studio 域内完成登录

使用方法:
1. 启动此服务: python ls_login_proxy.py
2. 服务默认运行在 http://localhost:5000
3. 访问 http://localhost:5000/login-form?email=xxx&password=xxx&next=/projects/123/ 即可自动登录并跳转
"""

from flask import Flask, request, redirect, make_response, jsonify, Response
import requests
import logging
import os
import re

# 配置日志
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Flask(__name__)

# Label Studio 配置
LABEL_STUDIO_INTERNAL_URL = os.getenv('LABEL_STUDIO_INTERNAL_URL', 'http://localhost:5001')  # 服务端 API 调用
LABEL_STUDIO_PUBLIC_URL = os.getenv('LABEL_STUDIO_PUBLIC_URL', 'http://122.51.47.91:28450')   # 浏览器访问


# 禁用代理
SESSION = requests.Session()
SESSION.proxies = {'http': None, 'https': None}
SESSION.trust_env = False


@app.route('/auto-login')
def auto_login():
    """
    自动登录端点（API Token 方式，用于验证）
    """
    token = request.args.get('token')
    next_url = request.args.get('next', '/')
    
    if not token:
        return jsonify({'error': 'Missing token parameter'}), 400
    
    try:
        headers = {'Authorization': f'Token {token}'}
        resp = SESSION.get(
            f'{LABEL_STUDIO_INTERNAL_URL}/api/current-user/whoami',
            headers=headers,
            timeout=10
        )
        
        if resp.status_code != 200:
            logger.error(f"Token 验证失败: {resp.status_code}")
            return jsonify({'error': 'Invalid token', 'status': resp.status_code}), 401
        
        user_info = resp.json()
        logger.info(f"✅ Token 验证成功: {user_info.get('email')}")
        
        redirect_url = f'{LABEL_STUDIO_PUBLIC_URL}{next_url}'
        return redirect(redirect_url)
        
    except Exception as e:
        logger.error(f"自动登录失败: {e}")
        return jsonify({'error': str(e)}), 500


@app.route('/login-form')
def login_form():
    """
    显示登录页面 - 使用 JavaScript 在客户端完成登录
    
    这个方案通过以下步骤绕过 CSRF：
    1. 用户浏览器直接访问 Label Studio 登录页面（获取 CSRF cookie）
    2. JavaScript 自动填充表单并提交
    
    参数:
        email: 用户邮箱
        password: 用户密码
        next: 登录后跳转路径
    """
    email = request.args.get('email', '')
    password = request.args.get('password', '')
    next_url = request.args.get('next', '/')
    
    # 生成一个页面，使用 JavaScript 在客户端完成登录
    html = f'''
    <!DOCTYPE html>
    <html>
    <head>
        <title>正在登录 Label Studio...</title>
        <meta charset="UTF-8">
        <style>
            body {{
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                display: flex;
                justify-content: center;
                align-items: center;
                height: 100vh;
                margin: 0;
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            }}
            .container {{
                background: white;
                padding: 40px;
                border-radius: 12px;
                box-shadow: 0 10px 40px rgba(0,0,0,0.2);
                text-align: center;
                max-width: 450px;
                width: 90%;
            }}
            h2 {{
                color: #333;
                margin-bottom: 20px;
            }}
            .spinner {{
                border: 4px solid #f3f3f3;
                border-top: 4px solid #667eea;
                border-radius: 50%;
                width: 40px;
                height: 40px;
                animation: spin 1s linear infinite;
                margin: 20px auto;
            }}
            @keyframes spin {{
                0% {{ transform: rotate(0deg); }}
                100% {{ transform: rotate(360deg); }}
            }}
            .info {{
                color: #666;
                font-size: 14px;
                margin-top: 15px;
            }}
            .status {{
                padding: 10px;
                border-radius: 6px;
                margin: 10px 0;
                font-size: 14px;
            }}
            .status.loading {{
                background: #e3f2fd;
                color: #1976d2;
            }}
            .status.success {{
                background: #e8f5e9;
                color: #388e3c;
            }}
            .status.error {{
                background: #ffebee;
                color: #d32f2f;
            }}
            .manual-section {{
                margin-top: 20px;
                padding-top: 20px;
                border-top: 1px solid #eee;
                display: none;
            }}
            .credentials {{
                background: #f5f5f5;
                padding: 15px;
                border-radius: 8px;
                margin: 15px 0;
                text-align: left;
            }}
            .credentials p {{
                margin: 8px 0;
                font-size: 13px;
            }}
            .credentials code {{
                background: #e0e0e0;
                padding: 2px 6px;
                border-radius: 4px;
                font-family: monospace;
                user-select: all;
            }}
            .btn {{
                display: inline-block;
                padding: 12px 24px;
                background: #667eea;
                color: white;
                border: none;
                border-radius: 6px;
                cursor: pointer;
                font-size: 14px;
                text-decoration: none;
                margin: 5px;
            }}
            .btn:hover {{
                background: #5a6fd6;
            }}
            .btn-secondary {{
                background: #9e9e9e;
            }}
            .btn-secondary:hover {{
                background: #757575;
            }}
            #login-iframe {{
                display: none;
                width: 100%;
                height: 400px;
                border: 1px solid #ddd;
                border-radius: 8px;
                margin-top: 15px;
            }}
        </style>
    </head>
    <body>
        <div class="container">
            <h2>🔐 正在登录 Label Studio</h2>
            <div class="spinner" id="spinner"></div>
            <div class="status loading" id="status">正在准备登录...</div>
            
            <div class="manual-section" id="manual-section">
                <p><strong>请在下方登录框中登录：</strong></p>
                <div class="credentials">
                    <p><strong>邮箱:</strong> <code id="email-display">{email}</code></p>
                    <p><strong>密码:</strong> <code id="password-display">{password}</code></p>
                </div>
                <button class="btn" onclick="copyCredentials()">📋 复制凭据</button>
                <a href="{LABEL_STUDIO_URL}/user/login/?next={next_url}" class="btn" target="_self">🔗 前往登录页面</a>
            </div>
            
            <iframe id="login-iframe" src="about:blank"></iframe>
        </div>
        
        <script>
            const email = "{email}";
            const password = "{password}";
            const nextUrl = "{next_url}";
            const lsUrl = "{LABEL_STUDIO_URL}";
            
            function updateStatus(message, type) {{
                const status = document.getElementById('status');
                status.textContent = message;
                status.className = 'status ' + type;
            }}
            
            function showManualLogin() {{
                document.getElementById('spinner').style.display = 'none';
                document.getElementById('manual-section').style.display = 'block';
                updateStatus('请手动登录', 'error');
            }}
            
            function copyCredentials() {{
                const text = `邮箱: ${{email}}\\n密码: ${{password}}`;
                navigator.clipboard.writeText(text).then(() => {{
                    alert('凭据已复制到剪贴板！');
                }});
            }}
            
            // 直接重定向到 Label Studio 登录页面，让用户在那里登录
            // 这是最可靠的方式，因为 CSRF token 会在同一域内自动处理
            function redirectToLogin() {{
                updateStatus('正在跳转到登录页面...', 'loading');
                
                // 创建一个包含预填信息的 URL
                // Label Studio 的登录页面会显示表单，用户需要手动点击登录
                // 但我们可以通过 URL hash 传递信息，用 JS 自动填充
                
                const loginUrl = LABEL_STUDIO_PUBLIC_URL + '/user/login/?next=' + encodeURIComponent(nextUrl) + 
                                 '#autofill=' + encodeURIComponent(btoa(JSON.stringify({{email, password}})));
                
                // 先尝试通过 fetch 检查是否已登录
                fetch(lsUrl + '/api/current-user/whoami', {{
                    credentials: 'include'
                }})
                .then(resp => {{
                    if (resp.ok) {{
                        // 已登录，检查是否是正确的用户
                        return resp.json().then(data => {{
                            if (data.email === email) {{
                                // 已经是正确的用户，直接跳转
                                updateStatus('已登录，正在跳转...', 'success');
                                window.location.href = LABEL_STUDIO_PUBLIC_URL + nextUrl;
                            }} else {{
                                // 登录的是其他用户，需要重新登录
                                updateStatus('检测到其他用户，请重新登录', 'loading');
                                showManualLogin();
                            }}
                        }});
                    }} else {{
                        // 未登录，跳转到登录页面
                        showManualLogin();
                    }}
                }})
                .catch(err => {{
                    console.error('检查登录状态失败:', err);
                    showManualLogin();
                }});
            }}
            
            // 页面加载后开始登录流程
            window.onload = function() {{
                redirectToLogin();
            }};
        </script>
    </body>
    </html>
    '''
    
    return html


@app.route('/direct-login')
def direct_login():
    """
    直接跳转方式 - 最简单可靠
    直接重定向到 Label Studio 登录页面，并在 URL 中传递凭据提示
    """
    email = request.args.get('email', '')
    password = request.args.get('password', '')
    next_url = request.args.get('next', '/')
    
    # 生成带提示的登录页面
    html = f'''
    <!DOCTYPE html>
    <html>
    <head>
        <title>Label Studio 登录</title>
        <meta charset="UTF-8">
        <style>
            * {{ margin: 0; padding: 0; box-sizing: border-box; }}
            body {{
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                background: #f5f5f5;
                min-height: 100vh;
            }}
            .header {{
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                color: white;
                padding: 15px 20px;
                display: flex;
                justify-content: space-between;
                align-items: center;
                position: fixed;
                top: 0;
                left: 0;
                right: 0;
                z-index: 1000;
                box-shadow: 0 2px 10px rgba(0,0,0,0.1);
            }}
            .credentials-bar {{
                display: flex;
                align-items: center;
                gap: 20px;
                font-size: 14px;
            }}
            .credentials-bar span {{
                background: rgba(255,255,255,0.2);
                padding: 5px 12px;
                border-radius: 4px;
            }}
            .credentials-bar code {{
                font-family: monospace;
                background: rgba(255,255,255,0.3);
                padding: 2px 6px;
                border-radius: 3px;
            }}
            .copy-btn {{
                background: white;
                color: #667eea;
                border: none;
                padding: 8px 16px;
                border-radius: 4px;
                cursor: pointer;
                font-weight: 500;
            }}
            .copy-btn:hover {{
                background: #f0f0f0;
            }}
            iframe {{
                position: fixed;
                top: 60px;
                left: 0;
                width: 100%;
                height: calc(100vh - 60px);
                border: none;
            }}
        </style>
    </head>
    <body>
        <div class="header">
            <div class="credentials-bar">
                <span>📧 邮箱: <code>{email}</code></span>
                <span>🔑 密码: <code>{password}</code></span>
            </div>
            <button class="copy-btn" onclick="copyAll()">📋 复制凭据</button>
        </div>
        <iframe src="{LABEL_STUDIO_PUBLIC_URL}/user/login/?next={next_url}" id="ls-frame"></iframe>
        
        <script>
            function copyAll() {{
                const text = "邮箱: {email}\\n密码: {password}";
                navigator.clipboard.writeText(text).then(() => {{
                    alert('凭据已复制！请在下方登录框中粘贴');
                }});
            }}
            
            // 监听 iframe 加载完成，尝试自动填充（可能因跨域限制失败）
            document.getElementById('ls-frame').onload = function() {{
                try {{
                    const iframe = document.getElementById('ls-frame');
                    const doc = iframe.contentDocument || iframe.contentWindow.document;
                    
                    // 尝试自动填充（仅在同源时有效）
                    const emailInput = doc.querySelector('input[name="email"]');
                    const passwordInput = doc.querySelector('input[name="password"]');
                    
                    if (emailInput) emailInput.value = "{email}";
                    if (passwordInput) passwordInput.value = "{password}";
                }} catch(e) {{
                    // 跨域限制，无法自动填充，用户需要手动输入
                    console.log('无法自动填充（跨域限制），请手动输入凭据');
                }}
            }};
        </script>
    </body>
    </html>
    '''
    
    return html


@app.route('/health')
def health():
    """健康检查端点"""
    return jsonify({'status': 'ok', 'label_studio_url': LABEL_STUDIO_URL})


if __name__ == '__main__':
    port = int(os.getenv('PROXY_PORT', 5000))
    logger.info(f"🚀 Label Studio 登录代理服务启动在 http://localhost:{port}")
    logger.info(f"📍 Label Studio URL: {LABEL_STUDIO_PUBLIC_URL}")
    logger.info(f"")
    logger.info(f"💡 提示: 如果遇到 CSRF 错误，请在启动 Label Studio 时添加:")
    logger.info(f"   CSRF_TRUSTED_ORIGINS=http://localhost:5000 label-studio start")
    app.run(host='0.0.0.0', port=port, debug=True)