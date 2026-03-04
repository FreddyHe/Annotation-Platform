"""
自动标注与清洗平台 - v3 (现代化UI版本)
支持多用户登录、组织级项目共享、Label Studio 集成

主要特性:
1. 同一组织内的用户共享项目池
2. Label Studio 链接按钮随时可见
3. 详细的处理进度日志
4. 现代化界面设计
"""
import streamlit as st
import os
import json
import zipfile
import shutil
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import pandas as pd
from datetime import datetime
import logging
import sys
import tempfile
import uuid
import subprocess
from urllib.parse import quote
from training_manager import TrainingManager

# ==================== 自定义 CSS 样式 ====================
def inject_custom_css():
    """注入现代化的自定义 CSS 样式"""
    st.markdown("""
    <style>
    /* 全局字体和颜色方案 */
    :root {
        --primary-color: #667eea;
        --secondary-color: #764ba2;
        --success-color: #10b981;
        --warning-color: #f59e0b;
        --error-color: #ef4444;
        --bg-gradient: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        --card-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
        --card-hover-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05);
    }
    
    /* 主容器样式 */
    .main .block-container {
        padding-top: 2rem;
        padding-bottom: 2rem;
        max-width: 1400px;
    }
    
    /* 标题样式 */
    h1 {
        background: var(--bg-gradient);
        -webkit-background-clip: text;
        -webkit-text-fill-color: transparent;
        background-clip: text;
        font-weight: 800;
        letter-spacing: -0.5px;
    }
    
    h2, h3 {
        color: #1f2937;
        font-weight: 700;
        margin-top: 1.5rem;
        margin-bottom: 1rem;
    }
    
    /* 卡片容器 */
    .stCard {
        background: white;
        border-radius: 12px;
        padding: 1.5rem;
        box-shadow: var(--card-shadow);
        transition: all 0.3s ease;
        border: 1px solid #e5e7eb;
    }
    
    .stCard:hover {
        box-shadow: var(--card-hover-shadow);
        transform: translateY(-2px);
    }
    
    /* 按钮样式优化 */
    .stButton > button {
        border-radius: 8px;
        font-weight: 600;
        padding: 0.5rem 1.5rem;
        transition: all 0.2s ease;
        border: none;
        box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
    }
    
    .stButton > button:hover {
        transform: translateY(-1px);
        box-shadow: 0 4px 8px rgba(0, 0, 0, 0.15);
    }
    
    .stButton > button[kind="primary"] {
        background: var(--bg-gradient) !important;
        color: white !important;
    }
    
    .stButton > button[kind="secondary"] {
        background: white !important;
        color: var(--primary-color) !important;
        border: 2px solid var(--primary-color) !important;
    }
    
    /* 输入框样式 */
    .stTextInput > div > div > input,
    .stTextArea > div > div > textarea,
    .stSelectbox > div > div > select,
    .stNumberInput > div > div > input {
        border-radius: 8px;
        border: 2px solid #e5e7eb;
        padding: 0.75rem;
        transition: all 0.2s ease;
    }
    
    .stTextInput > div > div > input:focus,
    .stTextArea > div > div > textarea:focus,
    .stSelectbox > div > div > select:focus,
    .stNumberInput > div > div > input:focus {
        border-color: var(--primary-color);
        box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1);
    }
    
    /* Metric 卡片样式 */
    [data-testid="stMetricValue"] {
        font-size: 2rem;
        font-weight: 700;
        background: var(--bg-gradient);
        -webkit-background-clip: text;
        -webkit-text-fill-color: transparent;
        background-clip: text;
    }
    
    [data-testid="stMetricLabel"] {
        font-weight: 600;
        color: #6b7280;
        font-size: 0.875rem;
        text-transform: uppercase;
        letter-spacing: 0.05em;
    }
    
    /* 进度条样式 */
    .stProgress > div > div > div > div {
        background: var(--bg-gradient);
        border-radius: 10px;
    }
    
    /* Tab 样式 */
    .stTabs [data-baseweb="tab-list"] {
        gap: 8px;
        background: #f9fafb;
        padding: 0.5rem;
        border-radius: 12px;
    }
    
    .stTabs [data-baseweb="tab"] {
        border-radius: 8px;
        padding: 0.75rem 1.5rem;
        font-weight: 600;
        transition: all 0.2s ease;
    }
    
    .stTabs [aria-selected="true"] {
        background: var(--bg-gradient) !important;
        color: white !important;
    }
    
    /* 侧边栏样式 */
    [data-testid="stSidebar"] {
        background: linear-gradient(180deg, #f9fafb 0%, #ffffff 100%);
    }
    
    [data-testid="stSidebar"] .stButton > button {
        width: 100%;
        justify-content: center;
    }
    
    /* 信息框样式 */
    .stAlert {
        border-radius: 10px;
        border-left: 4px solid;
        padding: 1rem;
        margin: 1rem 0;
    }
    
    /* 成功消息 */
    .stSuccess {
        background-color: #ecfdf5;
        border-left-color: var(--success-color);
        color: #065f46;
    }
    
    /* 警告消息 */
    .stWarning {
        background-color: #fffbeb;
        border-left-color: var(--warning-color);
        color: #92400e;
    }
    
    /* 错误消息 */
    .stError {
        background-color: #fef2f2;
        border-left-color: var(--error-color);
        color: #991b1b;
    }
    
    /* 信息消息 */
    .stInfo {
        background-color: #eff6ff;
        border-left-color: #3b82f6;
        color: #1e40af;
    }
    
    /* 数据框样式 */
    .dataframe {
        border-radius: 8px;
        overflow: hidden;
        box-shadow: var(--card-shadow);
    }
    
    /* Expander 样式 */
    .streamlit-expanderHeader {
        background: #f9fafb;
        border-radius: 8px;
        padding: 0.75rem;
        font-weight: 600;
        transition: all 0.2s ease;
    }
    
    .streamlit-expanderHeader:hover {
        background: #f3f4f6;
    }
    
    /* 图片预览容器 */
    .image-preview-container {
        border-radius: 12px;
        overflow: hidden;
        box-shadow: var(--card-shadow);
        transition: all 0.3s ease;
        background: white;
        padding: 0.5rem;
    }
    
    .image-preview-container:hover {
        box-shadow: var(--card-hover-shadow);
        transform: scale(1.02);
    }
    
    /* 分页按钮容器 */
    .pagination-container {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 1rem;
        padding: 1rem;
        background: #f9fafb;
        border-radius: 12px;
        margin: 1rem 0;
    }
    
    /* 代码块样式 */
    code {
        background: #f3f4f6;
        padding: 0.25rem 0.5rem;
        border-radius: 4px;
        font-size: 0.875rem;
        color: #1f2937;
    }
    
    /* 隐藏 Streamlit 默认元素 */
    #MainMenu {visibility: hidden;}
    footer {visibility: hidden;}
    
    /* 响应式设计 */
    @media (max-width: 768px) {
        .main .block-container {
            padding-left: 1rem;
            padding-right: 1rem;
        }
    }
    </style>
    """, unsafe_allow_html=True)


# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[logging.StreamHandler()],
)
logging.getLogger('streamlit').setLevel(logging.WARNING)
logger = logging.getLogger(__name__)

# 导入用户管理模块
from user_auth import UserManager, LabelStudioUserManager
from org_project_manager import OrganizationProjectManager
from login_page import check_authentication, render_login_page, render_user_menu

# 导入配置
try:
    from config import ALLOWED_IMAGE_EXTENSIONS
except ImportError:
    ALLOWED_IMAGE_EXTENSIONS = {'.jpg', '.jpeg', '.png', '.bmp', '.webp'}

# ==================== 配置 ====================
LABEL_STUDIO_URL = os.getenv('LABEL_STUDIO_URL', 'http://localhost:5001')
LOGIN_PROXY_URL = os.getenv('LOGIN_PROXY_URL', 'http://localhost:5000')

# ==================== 初始化 ====================
if 'user_manager' not in st.session_state:
    st.session_state.user_manager = UserManager()

if 'ls_user_manager' not in st.session_state:
    st.session_state.ls_user_manager = LabelStudioUserManager()

user_manager = st.session_state.user_manager
ls_user_manager = st.session_state.ls_user_manager

# ==================== 认证检查 ====================
username = check_authentication(user_manager)

if not username:
    render_login_page(user_manager)
    st.stop()

# ==================== 已登录，显示主界面 ====================
st.set_page_config(
    page_title="自动标注与清洗平台",
    page_icon="🎯",
    layout="wide",
    initial_sidebar_state="expanded"
)

# 注入自定义 CSS
inject_custom_css()

# 获取用户信息
user_info = user_manager.get_user(username)
organization_name = user_info.get('organization_name', f"{username}_workspace") if user_info else f"{username}_workspace"

# 初始化组织项目管理器
if 'project_manager' not in st.session_state or st.session_state.get('pm_org') != organization_name:
    st.session_state.project_manager = OrganizationProjectManager(organization_name)
    st.session_state.pm_org = organization_name

pm = st.session_state.project_manager

# 初始化其他 session state
if 'current_project' not in st.session_state:
    st.session_state.current_project = None

if 'page_size' not in st.session_state:
    st.session_state.page_size = 50

if 'current_page' not in st.session_state:
    st.session_state.current_page = 0

# ==================== 工具函数 ====================
def generate_label_studio_login_url(user_info: dict, project_id: int = None) -> str:
    """生成自动登录 Label Studio 的 URL"""
    email = user_info.get('email', f"{user_info.get('username')}@auto-annotation.local")
    password = f"AutoAnnotation_{user_info.get('username')}_2024!"
    
    if project_id:
        next_path = f"/projects/{project_id}/"
    else:
        next_path = "/"
    
    login_url = (
        f"{LOGIN_PROXY_URL}/direct-login"
        f"?email={quote(email)}"
        f"&password={quote(password)}"
        f"&next={quote(next_path)}"
    )
    
    return login_url


def render_label_studio_button(user_info: dict, project_id: int = None, context: str = "default", show_title: bool = True):
    """渲染现代化的 Label Studio 访问按钮"""
    if show_title:
        st.markdown("### 🎯 Label Studio")
    
    auto_login_url = generate_label_studio_login_url(user_info, project_id)
    button_text = "🚀 打开项目" if project_id else "🚀 打开 Label Studio"
    
    st.markdown(f"""
    <a href="{auto_login_url}" target="_blank" style="
        display: inline-block;
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        color: white;
        padding: 12px 24px;
        border-radius: 10px;
        text-decoration: none;
        font-weight: 700;
        font-size: 14px;
        box-shadow: 0 4px 15px rgba(102, 126, 234, 0.4);
        transition: all 0.3s ease;
        text-align: center;
        width: 100%;
    " onmouseover="this.style.transform='translateY(-2px)'; this.style.boxShadow='0 6px 20px rgba(102, 126, 234, 0.5)';"
       onmouseout="this.style.transform='translateY(0)'; this.style.boxShadow='0 4px 15px rgba(102, 126, 234, 0.4)';">
        {button_text}
    </a>
    """, unsafe_allow_html=True)
    
    if project_id:
        st.caption(f"📌 项目 ID: {project_id}")


def render_label_studio_link_full(user_info: dict, project_id: int, project_url: str, context: str = "default"):
    """渲染完整的 Label Studio 项目链接"""
    st.markdown("### 🎯 Label Studio 项目链接")
    st.markdown(f"**项目ID:** `{project_id}`")
    
    auto_login_url = generate_label_studio_login_url(user_info, project_id)
    
    st.markdown(f"""
    <a href="{auto_login_url}" target="_blank" style="
        display: inline-block;
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        color: white;
        padding: 14px 28px;
        border-radius: 10px;
        text-decoration: none;
        font-weight: 700;
        margin: 10px 0;
        box-shadow: 0 4px 15px rgba(102, 126, 234, 0.4);
        transition: all 0.3s ease;
    " onmouseover="this.style.transform='translateY(-2px)';"
       onmouseout="this.style.transform='translateY(0)';">
        🚀 打开项目（自动登录）
    </a>
    """, unsafe_allow_html=True)
    
    with st.expander("📋 登录信息（如自动登录失败）"):
        email = user_info.get('email', f"{user_info.get('username')}@auto-annotation.local")
        password = f"AutoAnnotation_{user_info.get('username')}_2024!"
        org_name = user_info.get('organization_name', 'N/A')
        
        col1, col2 = st.columns(2)
        with col1:
            st.text_input("邮箱", value=email, disabled=True, key=f"ls_email_{project_id}_{context}")
        with col2:
            st.text_input("密码", value=password, disabled=True, key=f"ls_pwd_{project_id}_{context}")
        
        st.info(f"🏢 工作区: **{org_name}**")
        st.markdown(f"[📎 直接链接（需手动登录）]({project_url})")


def ensure_label_studio_account(username: str, user_manager, ls_user_manager):
    """确保用户有对应的 Label Studio 账号"""
    user = user_manager.get_user(username)
    if not user:
        return None, None, None
    
    if (user.get('label_studio_user_id') and 
        user.get('label_studio_token') and 
        user.get('label_studio_org_id')):
        return (
            user['label_studio_user_id'], 
            user['label_studio_token'],
            user['label_studio_org_id']
        )
    
    organization_name = user.get('organization_name', f"{username}_workspace")
    email = user.get('email', f"{username}@auto-annotation.local")
    password = f"AutoAnnotation_{username}_2024!"
    
    ls_user_id, ls_token, ls_org_id = ls_user_manager.get_or_create_user_with_token(
        username=username,
        email=email,
        password=password,
        organization_name=organization_name
    )
    
    if ls_user_id and ls_token:
        user_manager.update_user(username, {
            'label_studio_user_id': ls_user_id,
            'label_studio_token': ls_token,
            'label_studio_org_id': ls_org_id,
        })
        logger.info(f"✅ 为用户 {username} 创建 Label Studio 账号成功")
        return ls_user_id, ls_token, ls_org_id
    
    return None, None, None


# ==================== 数据管理类 ====================
class DataManager:
    """数据管理器"""
    
    @staticmethod
    def scan_images(directory):
        dir_path = Path(directory)
        if not dir_path.exists():
            return []
        
        image_paths = []
        for ext in ALLOWED_IMAGE_EXTENSIONS:
            image_paths.extend(dir_path.glob(f'*{ext}'))
            image_paths.extend(dir_path.glob(f'*{ext.upper()}'))
        
        return sorted([str(p) for p in image_paths])
    
    @staticmethod
    def extract_zip(zip_path, extract_dir, check_duplicates=True):
        extract_dir = Path(extract_dir).resolve()
        extract_dir.mkdir(parents=True, exist_ok=True)
        
        existing_files = set()
        if check_duplicates:
            try:
                for item in list(extract_dir.iterdir()):
                    if item.is_file():
                        existing_files.add(item.name)
            except (OSError, PermissionError):
                existing_files = set()
        
        success_count = 0
        skip_count = 0
        errors = []
        
        try:
            with zipfile.ZipFile(zip_path, 'r') as zip_ref:
                for file_info in zip_ref.filelist:
                    if file_info.is_dir() or file_info.filename.startswith('.'):
                        continue
                    
                    filename = Path(file_info.filename).name
                    
                    if check_duplicates and filename in existing_files:
                        skip_count += 1
                        continue
                    
                    try:
                        content = zip_ref.read(file_info.filename)
                        target_path = extract_dir / filename
                        with open(target_path, 'wb') as f:
                            f.write(content)
                        success_count += 1
                    except Exception as e:
                        errors.append(f"{filename}: {str(e)}")
        except Exception as e:
            errors.append(f"解压失败: {str(e)}")
        
        return success_count, skip_count, errors
    
    @staticmethod
    def get_unprocessed_images(all_images, processed_images):
        processed_set = set(processed_images)
        return [img for img in all_images if img not in processed_set]


# ==================== 侧边栏 ====================
with st.sidebar:
    st.markdown("""
    <div style="text-align: center; padding: 1rem 0;">
        <h1 style="font-size: 1.75rem; margin: 0;">🎯 项目管理</h1>
    </div>
    """, unsafe_allow_html=True)
    
    # 用户菜单
    render_user_menu(user_manager, username)
    
    st.divider()
    
    # 显示组织信息（卡片样式）
    st.markdown(f"""
    <div style="
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        padding: 1rem;
        border-radius: 10px;
        color: white;
        text-align: center;
        margin-bottom: 1rem;
        box-shadow: 0 4px 15px rgba(102, 126, 234, 0.3);
    ">
        <div style="font-size: 0.875rem; opacity: 0.9;">🏢 当前工作区</div>
        <div style="font-size: 1.125rem; font-weight: 700; margin-top: 0.25rem;">{organization_name}</div>
    </div>
    """, unsafe_allow_html=True)
    
    # Label Studio 快捷入口
    if user_info:
        ensure_label_studio_account(username, user_manager, ls_user_manager)
        
        current_ls_project = None
        if st.session_state.current_project:
            ls_id, _ = pm.get_label_studio_project(st.session_state.current_project)
            current_ls_project = ls_id
        
        render_label_studio_button(user_info, current_ls_project, context="sidebar", show_title=True)
    
    st.divider()
    
    # 项目列表
    all_projects = pm.list_projects()
    
    if all_projects:
        st.markdown(f"""
        <div style="font-size: 1.125rem; font-weight: 700; margin-bottom: 0.75rem;">
            📋 组织项目 <span style="
                background: #667eea;
                color: white;
                padding: 0.125rem 0.5rem;
                border-radius: 12px;
                font-size: 0.875rem;
                margin-left: 0.5rem;
            ">{len(all_projects)}</span>
        </div>
        """, unsafe_allow_html=True)
        
        project_names = [p['name'] for p in all_projects]
        
        current_idx = 0
        if st.session_state.current_project in project_names:
            current_idx = project_names.index(st.session_state.current_project)
        
        selected_project = st.selectbox(
            "选择项目",
            project_names,
            index=current_idx,
            key="project_selector",
            label_visibility="collapsed"
        )
        
        if selected_project != st.session_state.current_project:
            if st.button("📂 切换到此项目", type="primary", use_container_width=True):
                st.session_state.current_project = selected_project
                st.rerun()
        
        if st.session_state.current_project:
            project_info = pm.get_project(st.session_state.current_project)
            if project_info:
                created_by = project_info.get('created_by', 'N/A')
                created_date = project_info.get('created_at', 'N/A')[:10]
                label_count = len(project_info.get('labels', {}))
                processed_count = len(project_info.get('processed_images', []))
                
                st.markdown(f"""
                <div style="
                    background: #ecfdf5;
                    border-left: 4px solid #10b981;
                    padding: 0.75rem;
                    border-radius: 8px;
                    margin: 1rem 0;
                    font-size: 0.875rem;
                ">
                    <div style="color: #065f46; font-weight: 600; margin-bottom: 0.5rem;">
                        ✅ 当前项目: {st.session_state.current_project}
                    </div>
                    <div style="color: #047857;">
                        <div>👤 创建者: {created_by}</div>
                        <div>📅 创建时间: {created_date}</div>
                        <div>🏷️ 类别数: {label_count}</div>
                        <div>✔️ 已处理: {processed_count} 张</div>
                    </div>
                </div>
                """, unsafe_allow_html=True)
                
                with st.expander("⚠️ 删除项目"):
                    st.warning("删除项目将清空配置")
                    if st.button("🗑️ 确认删除", use_container_width=True):
                        if pm.delete_project(st.session_state.current_project, deleted_by=username):
                            st.session_state.current_project = None
                            st.success("✅ 已删除")
                            st.rerun()
    else:
        st.info("暂无项目，请创建新项目开始使用")
    
    st.divider()
    
    with st.expander("➕ 创建新项目", expanded=False):
        new_project_name = st.text_input("项目名称", key="new_proj_name", placeholder="输入项目名称...")
        
        if st.button("✅ 创建项目", type="primary", use_container_width=True):
            if new_project_name:
                default_labels = {"object": "目标物体"}
                success, msg = pm.create_project(new_project_name, default_labels, created_by=username)
                if success:
                    st.session_state.current_project = new_project_name
                    st.success(f"✅ {msg}")
                    st.rerun()
                else:
                    st.error(f"❌ {msg}")
            else:
                st.warning("请输入项目名称")

# ==================== 主界面 ====================
if not st.session_state.current_project:
    # 欢迎页面（现代化设计）
    st.markdown("""
    <div style="text-align: center; padding: 3rem 0;">
        <h1 style="font-size: 3rem; margin-bottom: 1rem;">🎯 自动标注与清洗平台</h1>
        <p style="font-size: 1.25rem; color: #6b7280; margin-bottom: 2rem;">
            智能化数据标注，高效训练模型
        </p>
    </div>
    """, unsafe_allow_html=True)
    
    col1, col2, col3 = st.columns([1, 2, 1])
    with col2:
        st.markdown(f"""
        <div style="
            background: white;
            padding: 2rem;
            border-radius: 16px;
            box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
            text-align: center;
        ">
            <div style="font-size: 1.5rem; font-weight: 700; margin-bottom: 1rem;">
                👋 欢迎，{username}！
            </div>
            <div style="font-size: 1.125rem; color: #6b7280; margin-bottom: 1.5rem;">
                🏢 当前工作区：{organization_name}
            </div>
            <div style="
                background: #f3f4f6;
                padding: 1rem;
                border-radius: 10px;
                color: #374151;
            ">
                请在左侧选择或创建项目以开始使用
            </div>
        </div>
        """, unsafe_allow_html=True)
    
    if user_info:
        st.divider()
        st.subheader("🔗 快速访问")
        col1, col2, col3 = st.columns([1, 2, 1])
        with col2:
            render_label_studio_button(user_info, None, context="main_no_project", show_title=False)
            st.caption("点击上方按钮访问 Label Studio 工作区")
    
    st.stop()

# 获取当前项目信息
current_project = st.session_state.current_project
project_info = pm.get_project(current_project)

if not project_info:
    st.error(f"❌ 项目 '{current_project}' 不存在")
    st.session_state.current_project = None
    st.rerun()

# 数据目录
upload_dir = pm.get_upload_dir(current_project)
processed_dir = pm.get_processed_dir(current_project)
all_images = DataManager.scan_images(upload_dir)

# 主标题（现代化）
st.markdown(f"""
<div style="text-align: center; padding: 1rem 0 2rem 0;">
    <h1 style="font-size: 2.5rem; margin: 0;">{current_project}</h1>
    <p style="color: #6b7280; margin-top: 0.5rem;">智能标注与数据管理平台</p>
</div>
""", unsafe_allow_html=True)

# 标签页（使用原有逻辑，仅美化）
tab1, tab2, tab3, tab4, tab5, tab6 = st.tabs([
    "🏷️ 类别定义",
    "📤 数据管理",
    "🚀 批量处理",
    "📊 结果查看",
    "💾 导出结果",
    "🤖 模型训练",
])

# ==================== Tab 1: 类别定义 ====================
with tab1:
    st.markdown("## 🏷️ 类别定义")
    
    # Label Studio 入口
    ls_id, ls_url = pm.get_label_studio_project(current_project)
    if ls_id and user_info:
        col1, col2 = st.columns([3, 1])
        with col2:
            render_label_studio_button(user_info, ls_id, context="tab1", show_title=False)
    
    st.divider()
    
    current_labels = project_info.get('labels', {})
    
    num_labels = st.number_input(
        "类别数量", 
        min_value=1, 
        max_value=20, 
        value=max(len(current_labels), 1),
        help="定义检测目标的类别数量"
    )
    
    new_labels = {}
    
    for i in range(num_labels):
        st.markdown(f"""
        <div style="
            background: #f9fafb;
            padding: 1rem;
            border-radius: 10px;
            margin: 1rem 0;
            border-left: 4px solid #667eea;
        ">
            <h4 style="margin: 0 0 0.5rem 0; color: #1f2937;">类别 {i+1}</h4>
        """, unsafe_allow_html=True)
        
        col1, col2 = st.columns([1, 2])
        
        label_names = list(current_labels.keys())
        default_name = label_names[i] if i < len(label_names) else f"class_{i+1}"
        default_def = current_labels.get(default_name, "")
        
        with col1:
            label_name = st.text_input("类别名称", value=default_name, key=f"ln_{i}", placeholder="例如: person, car...")
        with col2:
            label_def = st.text_area("类别定义", value=default_def, key=f"ld_{i}", height=100, 
                                    placeholder="详细描述此类别的特征...")
        
        if label_name:
            new_labels[label_name] = label_def
        
        st.markdown("</div>", unsafe_allow_html=True)
    
    st.divider()
    
    if st.button("💾 保存类别定义", type="primary", use_container_width=True):
        if pm.update_project_labels(current_project, new_labels, updated_by=username):
            project_info['labels'] = new_labels
            st.success(f"✅ 类别定义已成功保存！（操作者: {username}）")
            st.balloons()

# ==================== Tab 2: 数据管理 ====================
with tab2:
    st.markdown("## 📤 数据管理")
    
    # Label Studio 入口
    ls_id, ls_url = pm.get_label_studio_project(current_project)
    if ls_id and user_info:
        col1, col2 = st.columns([3, 1])
        with col2:
            render_label_studio_button(user_info, ls_id, context="tab2", show_title=False)
    
    st.divider()
    
    # 初始化 session state
    if 'video_extract_result' not in st.session_state:
        st.session_state.video_extract_result = None
    if 'video_extracting' not in st.session_state:
        st.session_state.video_extracting = False
    
    tab2_images = DataManager.scan_images(upload_dir)
    
    # 数据统计（卡片式）
    col1, col2, col3 = st.columns(3)
    with col1:
        st.metric("📊 总图片数", len(tab2_images))
    with col2:
        processed_count = len(pm.get_processed_images(current_project))
        st.metric("✅ 已处理", processed_count)
    with col3:
        st.metric("⏳ 未处理", len(tab2_images) - processed_count)
    
    st.divider()
    
    # 上传方式选择
    st.markdown("### 📥 上传新数据")
    
    FILE_UPLOAD_SERVER_BASE_URL = os.getenv('FILE_UPLOAD_SERVER_URL', 'http://localhost:5002')
    FILE_UPLOAD_SERVER_URL = f"{FILE_UPLOAD_SERVER_BASE_URL}?org={quote(organization_name)}"
    
    upload_type = st.radio(
        "选择上传方式",
        [
            "🌐 网页上传服务 (推荐，支持大文件)",
            "📁 从服务器路径导入",
            "🎬 视频文件抽帧",
        ],
        horizontal=False,
        key=f"upload_type_{current_project}",
        help="推荐使用网页上传服务，支持任意大小文件的稳定上传"
    )
    
    # 方式1: 网页上传服务
    if upload_type == "🌐 网页上传服务 (推荐，支持大文件)":
        st.markdown("""
        <div style="
            background: #ecfdf5;
            border-left: 4px solid #10b981;
            padding: 1rem;
            border-radius: 8px;
            margin: 1rem 0;
        ">
            <div style="color: #065f46; font-weight: 600;">
                ✅ 推荐方式：使用独立上传服务，支持大文件分块上传，稳定可靠
            </div>
        </div>
        """, unsafe_allow_html=True)
        
        # 检查上传服务状态
        upload_service_available = False
        try:
            import requests
            response = requests.get(f"{FILE_UPLOAD_SERVER_URL}/health", timeout=3)
            if response.status_code == 200:
                upload_service_available = True
        except:
            pass
        
        if upload_service_available:
            st.success(f"🟢 上传服务运行中: {FILE_UPLOAD_SERVER_URL}")
        else:
            st.error(f"""
            🔴 上传服务未启动！请先在终端运行：
            ```bash
            cd /root/autodl-fs/web_biaozhupingtai
            python file_upload_server.py
            ```
            或在后台运行：
            ```bash
            nohup python file_upload_server.py > upload_server.log 2>&1 &
            ```
            """)
        
        st.markdown("---")
        
        # 方式 A: 嵌入式 iframe
        st.markdown("### 📤 方式一：直接上传（内嵌）")
        
        iframe_height = st.slider("上传区域高度", 400, 800, 600, key="iframe_height")
        
        st.components.v1.iframe(
            FILE_UPLOAD_SERVER_URL,
            height=iframe_height,
            scrolling=True
        )
        
        st.markdown("---")
        
        # 方式 B: 新窗口打开
        st.markdown("### 🔗 方式二：新窗口打开")
        st.markdown(f"""
        <a href="{FILE_UPLOAD_SERVER_URL}" target="_blank" style="
            display: inline-block;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            padding: 14px 28px;
            border-radius: 10px;
            text-decoration: none;
            font-weight: 700;
            font-size: 16px;
            box-shadow: 0 4px 15px rgba(102, 126, 234, 0.4);
            transition: all 0.3s ease;
        " onmouseover="this.style.transform='translateY(-2px)';"
           onmouseout="this.style.transform='translateY(0)';">
            🚀 打开上传页面
        </a>
        """, unsafe_allow_html=True)
        
        st.caption("点击上方按钮在新窗口中打开上传页面，上传完成后返回此页面导入")
        
        st.markdown("---")
        
        # 上传完成后的导入操作
        st.markdown("### 📥 上传完成后，导入到项目")
        
        upload_server_dir = pm.get_org_upload_root()
        
        col1, col2 = st.columns([3, 1])
        with col2:
            if st.button("🔄 刷新文件列表", key="refresh_upload_list"):
                pass
        
        if upload_server_dir.exists():
            files_in_upload = sorted(
                upload_server_dir.iterdir(),
                key=lambda x: x.stat().st_mtime if x.is_file() else 0,
                reverse=True
            )
            
            importable_files = []
            for f in files_in_upload[:20]:
                if f.is_file():
                    size_mb = f.stat().st_size / (1024 * 1024)
                    ext = f.suffix.lower()
                    if ext == '.zip':
                        importable_files.append({
                            'path': str(f),
                            'name': f.name,
                            'type': 'ZIP 压缩包',
                            'size': f"{size_mb:.1f} MB",
                            'icon': '📦'
                        })
                    elif ext in ['.jpg', '.jpeg', '.png', '.bmp', '.webp', '.gif']:
                        importable_files.append({
                            'path': str(f),
                            'name': f.name,
                            'type': '图片文件',
                            'size': f"{size_mb:.1f} MB",
                            'icon': '🖼️'
                        })
                    elif ext in ['.mp4', '.avi', '.mov', '.mkv']:
                        importable_files.append({
                            'path': str(f),
                            'name': f.name,
                            'type': '视频文件',
                            'size': f"{size_mb:.1f} MB",
                            'icon': '🎬'
                        })
                elif f.is_dir():
                    img_count = len(list(f.glob("*.jpg")) + list(f.glob("*.png")))
                    if img_count > 0:
                        importable_files.append({
                            'path': str(f),
                            'name': f.name,
                            'type': f'图片目录 ({img_count}张)',
                            'size': '-',
                            'icon': '📁'
                        })
            
            if importable_files:
                st.markdown("**可导入的文件/目录：**")
                
                selected_file = st.selectbox(
                    "选择要导入的文件",
                    options=range(len(importable_files)),
                    format_func=lambda i: f"{importable_files[i]['icon']} {importable_files[i]['name']} ({importable_files[i]['size']})",
                    key="select_import_file"
                )
                
                if selected_file is not None:
                    selected = importable_files[selected_file]
                    st.info(f"已选择: **{selected['name']}** ({selected['type']})")
                    
                    check_dup_import = st.checkbox("检查重复文件", value=True, key="check_dup_quick_import")
                    
                    if st.button("📥 导入到当前项目", type="primary", key="quick_import_btn", use_container_width=True):
                        selected_path = Path(selected['path'])
                        
                        if selected_path.suffix.lower() == '.zip':
                            with st.spinner(f"正在解压 {selected_path.name}..."):
                                success, skip, errors = DataManager.extract_zip(
                                    selected_path, upload_dir, check_dup_import
                                )
                            
                            if success > 0:
                                st.success(f"✅ 成功导入: {success} 张图片")
                            if skip > 0:
                                st.info(f"⏭️ 跳过重复: {skip} 张")
                            if errors:
                                with st.expander(f"❌ 错误详情 ({len(errors)} 个)"):
                                    for err in errors[:10]:
                                        st.text(err)
                        
                        elif selected_path.is_dir():
                            with st.spinner("正在复制图片..."):
                                success = 0
                                skip = 0
                                img_extensions = {'.jpg', '.jpeg', '.png', '.bmp', '.gif', '.webp'}
                                
                                for img_file in selected_path.iterdir():
                                    if img_file.suffix.lower() in img_extensions:
                                        dest = upload_dir / img_file.name
                                        if dest.exists() and check_dup_import:
                                            skip += 1
                                            continue
                                        shutil.copy2(img_file, dest)
                                        success += 1
                            
                            st.success(f"✅ 成功导入: {success} 张图片")
                            if skip > 0:
                                st.info(f"⏭️ 跳过重复: {skip} 张")
                        
                        elif selected_path.suffix.lower() in ['.jpg', '.jpeg', '.png', '.bmp', '.webp', '.gif']:
                            dest = upload_dir / selected_path.name
                            if dest.exists() and check_dup_import:
                                st.warning("⚠️ 文件已存在")
                            else:
                                shutil.copy2(selected_path, dest)
                                st.success(f"✅ 已导入: {selected_path.name}")
                        
                        else:
                            st.warning("⚠️ 不支持的文件类型，请选择 ZIP 压缩包、图片或图片目录")
            else:
                st.info("📭 上传目录为空，请先上传文件")
        else:
            st.warning(f"上传目录不存在: {upload_server_dir}")
        
        # 手动输入路径
        with st.expander("📝 手动输入路径（备用）"):
            manual_path = st.text_input(
                "输入文件完整路径",
                placeholder="/root/autodl-fs/uploads/train.zip",
                key="manual_import_path"
            )
            
            if manual_path and st.button("导入", key="manual_import_btn", use_container_width=True):
                manual_path_obj = Path(manual_path.strip())
                if manual_path_obj.exists():
                    if manual_path_obj.suffix.lower() == '.zip':
                        with st.spinner("解压中..."):
                            success, skip, errors = DataManager.extract_zip(
                                manual_path_obj, upload_dir, True
                            )
                        st.success(f"✅ 导入完成: {success} 张")
                    else:
                        st.error("请输入 ZIP 文件路径")
                else:
                    st.error("路径不存在")
    
    # 方式2: 从服务器路径导入
    elif upload_type == "📁 从服务器路径导入":
        st.info("💡 适用于已通过 SCP 等方式上传到服务器的文件")
        
        with st.expander("📖 SCP 上传说明", expanded=False):
            st.markdown("""
            **在本地终端执行：**
            ```bash
            scp -P [SSH端口] your_file.zip root@[服务器地址]:/root/autodl-fs/uploads/
            ```
            """)
        
        default_browse_path = "/root/autodl-fs/uploads"
        browse_path = st.text_input(
            "浏览目录",
            value=default_browse_path,
            key="browse_path_v2"
        )
        
        browse_path_obj = Path(browse_path)
        if browse_path_obj.exists() and browse_path_obj.is_dir():
            files = sorted(browse_path_obj.iterdir(), key=lambda x: (x.is_file(), x.name))
            
            if files:
                st.markdown("**目录内容:**")
                for f in files[:30]:
                    if f.is_file():
                        size_mb = f.stat().st_size / (1024 * 1024)
                        icon = "📦" if f.suffix.lower() == '.zip' else "🎬" if f.suffix.lower() in ['.mp4', '.avi', '.mov', '.mkv'] else "🖼️" if f.suffix.lower() in ['.jpg', '.png', '.jpeg'] else "📄"
                        st.text(f"{icon} {f.name} ({size_mb:.1f} MB)")
                    else:
                        st.text(f"📁 {f.name}/")
            else:
                st.info("📭 目录为空")
        
        st.markdown("---")
        
        server_path = st.text_input(
            "输入要导入的文件或目录路径",
            placeholder="/root/autodl-fs/uploads/train.zip",
            key="server_path_input"
        )
        
        check_dup_server = st.checkbox("检查重复文件", value=True, key="check_dup_server_v2")
        
        if server_path:
            server_path_obj = Path(server_path.strip())
            
            if not server_path_obj.exists():
                st.error(f"❌ 路径不存在: {server_path}")
            else:
                if server_path_obj.is_file():
                    size_mb = server_path_obj.stat().st_size / (1024 * 1024)
                    st.info(f"📄 文件: {server_path_obj.name} ({size_mb:.1f} MB)")
                else:
                    file_count = len(list(server_path_obj.glob("*")))
                    st.info(f"📁 目录: {server_path_obj.name} ({file_count} 个文件)")
                
                if st.button("🚀 开始导入", type="primary", key="import_server_path_btn", use_container_width=True):
                    if server_path_obj.is_file() and server_path_obj.suffix.lower() == '.zip':
                        with st.spinner(f"正在解压 {server_path_obj.name}..."):
                            success, skip, errors = DataManager.extract_zip(
                                server_path_obj, upload_dir, check_dup_server
                            )
                        
                        if success > 0:
                            st.success(f"✅ 成功导入: {success} 张图片")
                        if skip > 0:
                            st.info(f"⏭️ 跳过重复: {skip} 张")
                        if errors:
                            with st.expander(f"❌ 错误详情"):
                                for err in errors[:10]:
                                    st.text(err)
                    
                    elif server_path_obj.is_dir():
                        with st.spinner("正在复制图片..."):
                            success = 0
                            skip = 0
                            img_extensions = {'.jpg', '.jpeg', '.png', '.bmp', '.gif', '.webp', '.tiff', '.tif'}
                            
                            all_images = []
                            for ext in img_extensions:
                                all_images.extend(server_path_obj.rglob(f"*{ext}"))
                                all_images.extend(server_path_obj.rglob(f"*{ext.upper()}"))
                            
                            all_images = list(set(all_images))
                            
                            if not all_images:
                                st.warning("⚠️ 目录中没有找到图片文件")
                            else:
                                progress_bar = st.progress(0)
                                for i, img_file in enumerate(all_images):
                                    dest = upload_dir / img_file.name
                                    if dest.exists() and check_dup_server:
                                        skip += 1
                                        continue
                                    shutil.copy2(img_file, dest)
                                    success += 1
                                    progress_bar.progress((i + 1) / len(all_images))
                                
                                st.success(f"✅ 成功导入: {success} 张图片")
                                if skip > 0:
                                    st.info(f"⏭️ 跳过重复: {skip} 张")
                    else:
                        st.error("❌ 请提供 ZIP 压缩包或图片目录")
    
    # 方式3: 视频文件抽帧
    elif upload_type == "🎬 视频文件抽帧":
        st.info("📹 从已上传的视频文件中提取图片帧")
        
        video_dir = pm.get_org_upload_root()
        video_extensions = {'.mp4', '.avi', '.mov', '.mkv', '.wmv', '.flv', '.webm'}
        
        video_files = []
        if video_dir.exists():
            for f in video_dir.iterdir():
                if f.is_file() and f.suffix.lower() in video_extensions:
                    size_mb = f.stat().st_size / (1024 * 1024)
                    video_files.append({
                        'path': str(f),
                        'name': f.name,
                        'size': f"{size_mb:.1f} MB"
                    })
        
        if not video_files:
            st.warning("⚠️ 上传目录中没有视频文件。请先通过上传服务上传视频。")
            st.markdown(f"""
            <a href="{FILE_UPLOAD_SERVER_URL}" target="_blank" style="
                display: inline-block;
                background: #667eea;
                color: white;
                padding: 12px 24px;
                border-radius: 8px;
                text-decoration: none;
                font-weight: 700;
            ">
                📤 去上传视频
            </a>
            """, unsafe_allow_html=True)
        else:
            st.success(f"找到 {len(video_files)} 个视频文件")
            
            selected_video_idx = st.selectbox(
                "选择视频文件",
                options=range(len(video_files)),
                format_func=lambda i: f"🎬 {video_files[i]['name']} ({video_files[i]['size']})",
                key="select_video_file"
            )
            
            selected_video = video_files[selected_video_idx]
            st.info(f"已选择: **{selected_video['name']}**")
            
            col1, col2 = st.columns(2)
            with col1:
                fps_option = st.selectbox(
                    "提取帧率",
                    options=[0.5, 1, 2, 5, 10],
                    index=1,
                    format_func=lambda x: f"每秒 {x} 帧" if x >= 1 else f"每 {int(1/x)} 秒 1 帧",
                )
            with col2:
                output_format = st.selectbox(
                    "输出格式",
                    options=['jpg', 'png'],
                    index=0,
                )
            
            with st.expander("⚙️ 高级选项"):
                col1, col2 = st.columns(2)
                with col1:
                    max_frames = st.number_input("最大帧数 (0=不限)", min_value=0, max_value=10000, value=0)
                with col2:
                    image_quality = st.slider("图片质量 (JPG)", 50, 100, 95)
                
                col3, col4 = st.columns(2)
                with col3:
                    start_time = st.number_input("开始时间 (秒)", min_value=0.0, value=0.0, step=0.5)
                with col4:
                    end_time = st.number_input("结束时间 (秒, 0=到结尾)", min_value=0.0, value=0.0, step=0.5)
            
            if st.button("🎬 开始提取帧", type="primary", key="extract_video_frames_btn", use_container_width=True):
                try:
                    import cv2
                    import re
                    
                    video_path = selected_video['path']
                    cap = cv2.VideoCapture(video_path)
                    
                    if not cap.isOpened():
                        st.error("❌ 无法打开视频文件")
                    else:
                        video_fps = cap.get(cv2.CAP_PROP_FPS)
                        total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
                        duration = total_frames / video_fps if video_fps > 0 else 0
                        
                        st.info(f"视频时长: {duration:.1f} 秒, 原始帧率: {video_fps:.1f} FPS")
                        
                        time_interval = 1.0 / fps_option
                        effective_end = end_time if end_time > 0 else duration
                        
                        extract_times = []
                        t = start_time
                        while t < effective_end:
                            extract_times.append(t)
                            t += time_interval
                        if max_frames > 0:
                            extract_times = extract_times[:max_frames]
                        
                        video_name_clean = re.sub(r'[^\w\-]', '_', Path(video_path).stem)[:20]
                        final_prefix = f"{video_name_clean}_{datetime.now().strftime('%m%d_%H%M%S')}"
                        
                        progress_bar = st.progress(0)
                        status_text = st.empty()
                        
                        extracted_count = 0
                        for i, t in enumerate(extract_times):
                            cap.set(cv2.CAP_PROP_POS_MSEC, t * 1000)
                            ret, frame = cap.read()
                            if ret:
                                frame_filename = f"{final_prefix}_{extracted_count + 1:06d}.{output_format}"
                                frame_path = upload_dir / frame_filename
                                if output_format == 'jpg':
                                    cv2.imwrite(str(frame_path), frame, [cv2.IMWRITE_JPEG_QUALITY, image_quality])
                                else:
                                    cv2.imwrite(str(frame_path), frame)
                                extracted_count += 1
                            
                            progress_bar.progress((i + 1) / len(extract_times))
                            if (i + 1) % 10 == 0:
                                status_text.text(f"提取中... {i + 1}/{len(extract_times)}")
                        
                        cap.release()
                        st.success(f"✅ 提取完成！共 {extracted_count} 帧")
                        
                except ImportError:
                    st.error("❌ 需要安装 opencv-python: pip install opencv-python")
                except Exception as e:
                    st.error(f"❌ 提取失败: {e}")
    
    st.divider()
    
    # 已上传图片预览
    st.markdown("### 🖼️ 项目中的图片")
    
    tab2_images = DataManager.scan_images(upload_dir)
    
    if tab2_images:
        col_refresh, col_pagesize = st.columns([1, 1])
        with col_refresh:
            if st.button("🔄 刷新", key="refresh_image_list_btn"):
                pass
        with col_pagesize:
            page_size = st.selectbox("每页", [50, 100, 200], index=0, key="page_size_select", label_visibility="collapsed")
        
        total_pages = max(1, (len(tab2_images) - 1) // page_size + 1)
        
        if st.session_state.current_page >= total_pages:
            st.session_state.current_page = 0
        
        # 分页控制（美化版）
        st.markdown("""
        <div style="
            background: #f9fafb;
            padding: 1rem;
            border-radius: 12px;
            margin: 1rem 0;
        ">
        """, unsafe_allow_html=True)
        
        col1, col2, col3 = st.columns([1, 2, 1])
        with col1:
            if st.button("◀️ 上一页", disabled=(st.session_state.current_page <= 0), use_container_width=True):
                st.session_state.current_page -= 1
                st.rerun()
        with col2:
            st.markdown(f"""
            <div style="text-align: center; padding: 0.5rem; font-weight: 600; color: #1f2937;">
                第 {st.session_state.current_page + 1} / {total_pages} 页 (共 {len(tab2_images)} 张)
            </div>
            """, unsafe_allow_html=True)
        with col3:
            if st.button("下一页 ▶️", disabled=(st.session_state.current_page >= total_pages - 1), use_container_width=True):
                st.session_state.current_page += 1
                st.rerun()
        
        st.markdown("</div>", unsafe_allow_html=True)
        
        start_idx = st.session_state.current_page * page_size
        end_idx = min(start_idx + page_size, len(tab2_images))
        current_page_images = tab2_images[start_idx:end_idx]
        
        processed_images = set(pm.get_processed_images(current_project))
        
        cols_per_row = 5
        for i in range(0, len(current_page_images), cols_per_row):
            cols = st.columns(cols_per_row)
            for j, col in enumerate(cols):
                idx = i + j
                if idx < len(current_page_images):
                    img_path = current_page_images[idx]
                    with col:
                        st.markdown('<div class="image-preview-container">', unsafe_allow_html=True)
                        try:
                            img = Image.open(img_path)
                            img.thumbnail((200, 200))
                            st.image(img, use_column_width=True)
                            filename = Path(img_path).name
                            status = "✅" if img_path in processed_images else "⏳"
                            st.caption(f"{status} {filename[:15]}...")
                        except Exception as e:
                            st.error("无法加载")
                            st.caption(f"{Path(img_path).name[:20]}")
                        st.markdown('</div>', unsafe_allow_html=True)
    else:
        st.info("📭 暂无图片，请上传图片或视频")

# ==================== Tab 3: 批量处理 ====================
with tab3:
    st.markdown("## 🚀 批量处理")
    
    ls_id, ls_url = pm.get_label_studio_project(current_project)
    
    col1, col2 = st.columns([3, 1])
    with col1:
        if ls_id:
            st.markdown(f"""
            <div style="
                background: #ecfdf5;
                border-left: 4px solid #10b981;
                padding: 0.75rem;
                border-radius: 8px;
                color: #065f46;
                font-weight: 600;
            ">
                ✅ 已关联 Label Studio 项目 (ID: {ls_id})
            </div>
            """, unsafe_allow_html=True)
        else:
            st.info("📋 尚未关联 Label Studio 项目，处理完成后将自动创建")
    with col2:
        if user_info:
            render_label_studio_button(user_info, ls_id, context="tab3_top", show_title=False)
    
    st.divider()
    
    tab3_images = DataManager.scan_images(upload_dir)
    labels_def = project_info.get('labels', {})
    
    if not tab3_images:
        st.warning("⚠️ 请先上传图片")
    elif not labels_def:
        st.warning("⚠️ 请先定义类别")
    else:
        processed_images = pm.get_processed_images(current_project)
        unprocessed_images = DataManager.get_unprocessed_images(tab3_images, processed_images)
        
        # 美化的统计卡片
        col1, col2 = st.columns(2)
        with col1:
            st.markdown(f"""
            <div style="
                background: linear-gradient(135deg, #fef3c7 0%, #fde68a 100%);
                padding: 1.5rem;
                border-radius: 12px;
                text-align: center;
                box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
            ">
                <div style="color: #92400e; font-size: 0.875rem; font-weight: 600; text-transform: uppercase;">
                    ⏳ 未处理图片
                </div>
                <div style="color: #78350f; font-size: 2.5rem; font-weight: 800; margin-top: 0.5rem;">
                    {len(unprocessed_images)}
                </div>
            </div>
            """, unsafe_allow_html=True)
        with col2:
            st.markdown(f"""
            <div style="
                background: linear-gradient(135deg, #d1fae5 0%, #a7f3d0 100%);
                padding: 1.5rem;
                border-radius: 12px;
                text-align: center;
                box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
            ">
                <div style="color: #065f46; font-size: 0.875rem; font-weight: 600; text-transform: uppercase;">
                    ✅ 已处理图片
                </div>
                <div style="color: #064e3b; font-size: 2.5rem; font-weight: 800; margin-top: 0.5rem;">
                    {len(processed_images)}
                </div>
            </div>
            """, unsafe_allow_html=True)
        
        st.markdown("<br>", unsafe_allow_html=True)
        
        # 处理模式选择
        process_mode = st.radio(
            "处理模式",
            ["仅处理未处理的图片", "重新处理所有图片"],
            help="选择处理策略"
        )
        
        images_to_process = unprocessed_images if "未处理" in process_mode else tab3_images
        
        # 检查是否已有 VLM 结果
        vlm_cleaned_path = processed_dir / "vlm_cleaned.json"
        skip_vlm = False
        if vlm_cleaned_path.exists():
            st.markdown(f"""
            <div style="
                background: #eff6ff;
                border-left: 4px solid #3b82f6;
                padding: 1rem;
                border-radius: 8px;
                margin: 1rem 0;
            ">
                <div style="color: #1e40af; font-weight: 600;">
                    📂 检测到已存在的 VLM 结果文件
                </div>
                <div style="color: #1e3a8a; margin-top: 0.5rem; font-size: 0.875rem;">
                    上次修改: {datetime.fromtimestamp(vlm_cleaned_path.stat().st_mtime).strftime('%Y-%m-%d %H:%M:%S')}
                </div>
            </div>
            """, unsafe_allow_html=True)
            skip_vlm = st.checkbox("⏭️ 跳过 VLM 清洗 (直接使用现有结果进行 Label Studio 导入)", value=True)

        if not images_to_process and not skip_vlm:
            st.markdown("""
            <div style="
                background: #ecfdf5;
                border: 2px dashed #10b981;
                padding: 2rem;
                border-radius: 12px;
                text-align: center;
                margin: 2rem 0;
            ">
                <div style="font-size: 3rem;">✅</div>
                <div style="font-size: 1.25rem; font-weight: 700; color: #065f46; margin-top: 1rem;">
                    所有图片已处理完成
                </div>
            </div>
            """, unsafe_allow_html=True)
        else:
            if not skip_vlm:
                st.info(f"📊 将处理 {len(images_to_process)} 张图片")
            
            # Label Studio 配置
            enable_label_studio = st.checkbox("自动导入 Label Studio", value=True)
            
            ls_user_id, ls_token, ls_org_id = ensure_label_studio_account(
                username, user_manager, ls_user_manager
            )
            
            if enable_label_studio:
                if ls_token:
                    st.success(f"🔑 Label Studio Token 已就绪")
                else:
                    st.error("❌ 未找到 Label Studio Token，请重新登录或联系管理员")

            # 大按钮
            st.markdown("<br>", unsafe_allow_html=True)
            if st.button("🚀 开始处理", type="primary", use_container_width=True, key="start_batch_process"):
                print("\n" + "=" * 80)
                print("🚀🚀🚀 进入处理流程 🚀🚀🚀")
                print("=" * 80)
                sys.stdout.flush()
                
                result = {'success': False, 'output_file': str(vlm_cleaned_path)}
                vlm_output_file = str(vlm_cleaned_path)
                images_to_mark = []
                
                try:
                    print("📌 CHECKPOINT 1: 开始导入 InferencePipeline")
                    sys.stdout.flush()
                    
                    from backend.pipeline.inference_pipeline import InferencePipeline
                    
                    pipeline = InferencePipeline()
                    output_dir = str(processed_dir)
                    Path(output_dir).mkdir(parents=True, exist_ok=True)
                    
                    run_image_dir = str(upload_dir)
                    subset_tmp_dir = None
                    
                    if "未处理" in process_mode and not skip_vlm:
                        subset_tmp_dir = Path(tempfile.gettempdir()) / "web_annotation_subset" / organization_name / current_project / str(uuid.uuid4())
                        subset_tmp_dir.mkdir(parents=True, exist_ok=True)
                        for src in images_to_process:
                            try:
                                src_p = Path(src)
                                link_p = subset_tmp_dir / src_p.name
                                if not link_p.exists():
                                    os.symlink(str(src_p), str(link_p))
                            except:
                                pass
                        run_image_dir = str(subset_tmp_dir)
                        images_to_mark = list(images_to_process)
                    
                    print(f"📌 CHECKPOINT 4: run_image_dir = {run_image_dir}, skip_vlm = {skip_vlm}")
                    sys.stdout.flush()
                    
                    # VLM 处理
                    if not skip_vlm:
                        print("📌 CHECKPOINT 5: 进入 VLM 处理分支")
                        sys.stdout.flush()
                        
                        with open(Path(output_dir) / "definitions.json", "w", encoding="utf-8") as f:
                            json.dump(labels_def, f, ensure_ascii=False, indent=2)
                        
                        print("📌 CHECKPOINT 6: 开始调用 pipeline.process_images")
                        sys.stdout.flush()
                        
                        result = pipeline.process_images(
                            image_dir=run_image_dir,
                            target_labels=list(labels_def.keys()),
                            label_definitions=labels_def,
                            output_dir=output_dir,
                            enable_vlm_clean=True
                        )
                        
                        print(f"📌 CHECKPOINT 7: pipeline 返回: {result}")
                        sys.stdout.flush()
                        
                        vlm_output_file = result.get('output_file', str(vlm_cleaned_path))
                        
                        if subset_tmp_dir and vlm_output_file and Path(vlm_output_file).exists():
                            print("📌 CHECKPOINT 9: 开始修正路径")
                            sys.stdout.flush()
                            
                            try:
                                with open(vlm_output_file, 'r', encoding='utf-8') as f:
                                    vlm_data = json.load(f)
                                
                                fixed_count = 0
                                subset_tmp_str = str(subset_tmp_dir)
                                
                                for item in vlm_data:
                                    old_path = item.get('image_path', '')
                                    if subset_tmp_str in old_path or '/tmp/web_annotation_subset/' in old_path:
                                        filename = Path(old_path).name
                                        new_path = str(upload_dir / filename)
                                        item['image_path'] = new_path
                                        fixed_count += 1
                                
                                with open(vlm_output_file, 'w', encoding='utf-8') as f:
                                    json.dump(vlm_data, f, ensure_ascii=False, indent=2)
                                
                                print(f"📌 CHECKPOINT 10: 已修正 {fixed_count} 条路径")
                                sys.stdout.flush()
                            except Exception as e:
                                print(f"📌 修正路径出错: {e}")
                                sys.stdout.flush()
                        
                        # if subset_tmp_dir and subset_tmp_dir.exists():
                        #     shutil.rmtree(subset_tmp_dir, ignore_errors=True)
                        
                        if not result.get('success') and Path(vlm_output_file).exists():
                            result['success'] = True
                            result['output_file'] = vlm_output_file
                    else:
                        print("📌 CHECKPOINT 5-SKIP: 跳过 VLM")
                        sys.stdout.flush()
                        result = {'success': True, 'output_file': str(vlm_cleaned_path), 'final_kept': 'N/A'}
                        vlm_output_file = str(vlm_cleaned_path)

                    print(f"📌 CHECKPOINT 12: VLM 阶段完成, success={result.get('success')}")
                    sys.stdout.flush()
                    
                    # Label Studio 导入
                    if result.get('success'):
                        print("📌 CHECKPOINT 13: 进入 Label Studio 阶段")
                        sys.stdout.flush()
                        
                        if enable_label_studio and ls_token:
                            print("📌 CHECKPOINT 14: 开始 Label Studio 导入")
                            sys.stdout.flush()
                            
                            if vlm_output_file and Path(vlm_output_file).exists():
                                try:
                                    print("📌 CHECKPOINT 15: 导入模块")
                                    sys.stdout.flush()
                                    
                                    from label_studio_integration_enhanced import LabelStudioIntegrationAsync
                                    
                                    ls_integration = LabelStudioIntegrationAsync(
                                        url=LABEL_STUDIO_URL,
                                        api_key=ls_token
                                    )
                                    
                                    ls_project_title = f"{organization_name}_{current_project}_标注"
                                    
                                    print(f"📌 CHECKPOINT 16: 调用 setup_project_complete")
                                    sys.stdout.flush()
                                    
                                    setup_result = ls_integration.setup_project_complete(
                                        project_title=ls_project_title,
                                        labels=labels_def,
                                        upload_dir=str(upload_dir),
                                        vlm_cleaned_path=vlm_output_file
                                    )
                                    
                                    print(f"📌 CHECKPOINT 17: setup 返回: {setup_result}")
                                    sys.stdout.flush()
                                    
                                    if setup_result.get('success'):
                                        project_id = setup_result.get('project_id')
                                        project_url = setup_result.get('project_url')
                                        stats = setup_result.get('stats', {})
                                        
                                        if project_id:
                                            print(f"📌 CHECKPOINT 18: 保存项目关联 ID={project_id}")
                                            sys.stdout.flush()
                                            pm.set_label_studio_project(current_project, project_id, project_url)
                                        
                                        print("📌 CHECKPOINT 19: 显示成功信息")
                                        sys.stdout.flush()
                                        
                                        st.markdown("""
                                        <div style="
                                            background: linear-gradient(135deg, #10b981 0%, #059669 100%);
                                            color: white;
                                            padding: 2rem;
                                            border-radius: 12px;
                                            text-align: center;
                                            margin: 2rem 0;
                                            box-shadow: 0 10px 15px -3px rgba(16, 185, 129, 0.4);
                                        ">
                                            <div style="font-size: 3rem; margin-bottom: 1rem;">🎉</div>
                                            <div style="font-size: 1.5rem; font-weight: 700;">
                                                处理完成！
                                            </div>
                                            <div style="font-size: 1rem; margin-top: 0.5rem; opacity: 0.9;">
                                                Label Studio 项目已设置完成
                                            </div>
                                        </div>
                                        """, unsafe_allow_html=True)
                                        
                                        c1, c2, c3 = st.columns(3)
                                        c1.metric("总任务", stats.get('total_tasks', 0))
                                        c2.metric("成功匹配", stats.get('success', 0))
                                        c3.metric("跳过", stats.get('skipped', 0))
                                        
                                        if project_url:
                                            auto_login_url = generate_label_studio_login_url(user_info, project_id)
                                            st.markdown(f"""
                                            <div style="text-align: center; margin: 2rem 0;">
                                                <a href="{auto_login_url}" target="_blank" style="
                                                    display: inline-block;
                                                    background: white;
                                                    color: #10b981;
                                                    padding: 1rem 2rem;
                                                    text-decoration: none;
                                                    border-radius: 10px;
                                                    font-weight: 700;
                                                    font-size: 1.125rem;
                                                    box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
                                                    transition: all 0.3s ease;
                                                " onmouseover="this.style.transform='translateY(-2px)';"
                                                   onmouseout="this.style.transform='translateY(0)';">
                                                    🚀 立即前往 Label Studio 标注
                                                </a>
                                            </div>
                                            """, unsafe_allow_html=True)
                                        
                                        st.balloons()
                                        
                                    else:
                                        st.error(f"❌ Label Studio 设置失败: {setup_result.get('error')}")
                                
                                except Exception as e:
                                    print(f"📌 CHECKPOINT-LS-ERROR: {e}")
                                    import traceback
                                    traceback.print_exc()
                                    sys.stdout.flush()
                                    st.error(f"❌ Label Studio 异常: {e}")
                            else:
                                st.warning(f"⚠️ VLM 结果文件不存在")
                        else:
                            st.success(f"✅ VLM 处理完成! 保留: {result.get('final_kept', 'N/A')}")
                        
                        print(f"📌 CHECKPOINT 20: 开始标记 {len(images_to_mark)} 张图片")
                        sys.stdout.flush()
                        
                        if images_to_mark:
                            try:
                                if hasattr(pm, 'mark_images_processed_batch'):
                                    pm.mark_images_processed_batch(current_project, images_to_mark)
                                else:
                                    for i, img in enumerate(images_to_mark):
                                        pm.mark_image_processed(current_project, img)
                                print(f"📌 CHECKPOINT 21: 标记完成")
                                sys.stdout.flush()


                                if subset_tmp_dir and subset_tmp_dir.exists():
                                    try:
                                        shutil.rmtree(subset_tmp_dir, ignore_errors=True)
                                        print(f"📌 已清理临时目录: {subset_tmp_dir}")
                                    except Exception as e:
                                        print(f"📌 清理临时目录失败（不影响主流程）: {e}")
                                    sys.stdout.flush()
                            except Exception as e:
                                print(f"📌 标记失败（不影响主流程）: {e}")
                                sys.stdout.flush()
                    
                    else:
                        st.error(f"❌ VLM 处理失败")
                    
                    print("📌 CHECKPOINT 99: 流程结束")
                    sys.stdout.flush()

                except Exception as e:
                    print(f"📌 CHECKPOINT-FATAL: {e}")
                    import traceback
                    traceback.print_exc()
                    sys.stdout.flush()
                    st.error(f"❌ 严重错误: {e}")
                
                print("=" * 80)
                print("🏁 处理流程退出")
                print("=" * 80)
                sys.stdout.flush()


# ==================== Tab 4: 结果查看 ====================
with tab4:
    st.markdown("## 📊 结果查看")
    
    ls_id, ls_url = pm.get_label_studio_project(current_project)
    if ls_id and user_info:
        col1, col2 = st.columns([3, 1])
        with col2:
            render_label_studio_button(user_info, ls_id, context="tab4", show_title=False)
    
    st.divider()
    
    if not processed_dir.exists():
        st.markdown("""
        <div style="
            background: #fef3c7;
            border: 2px dashed #f59e0b;
            padding: 2rem;
            border-radius: 12px;
            text-align: center;
            margin: 2rem 0;
        ">
            <div style="font-size: 3rem;">📭</div>
            <div style="font-size: 1.25rem; font-weight: 700; color: #92400e; margin-top: 1rem;">
                暂无处理结果
            </div>
            <div style="color: #78350f; margin-top: 0.5rem;">
                请先在「批量处理」标签页完成数据处理
            </div>
        </div>
        """, unsafe_allow_html=True)
    else:
        json_files = list(processed_dir.glob("*.json"))
        
        if not json_files:
            st.warning("⚠️ 暂无结果文件")
        else:
            json_files.sort(key=lambda x: x.stat().st_mtime, reverse=True)
            
            st.markdown("### 📄 选择结果文件")
            selected_file = st.selectbox(
                "结果文件",
                [f.name for f in json_files],
                index=0,
                label_visibility="collapsed"
            )
            
            result_file = processed_dir / selected_file
            
            try:
                with open(result_file, 'r', encoding='utf-8') as f:
                    results = json.load(f)
                
                if isinstance(results, list) and results:
                    total = len(results)
                    kept = sum(1 for r in results if r.get('vlm_decision') == 'keep')
                    keep_rate = (kept/total*100) if total > 0 else 0
                    
                    # 美化的统计卡片
                    col1, col2, col3 = st.columns(3)
                    
                    with col1:
                        st.markdown(f"""
                        <div style="
                            background: linear-gradient(135deg, #dbeafe 0%, #bfdbfe 100%);
                            padding: 1.5rem;
                            border-radius: 12px;
                            text-align: center;
                            box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
                        ">
                            <div style="color: #1e40af; font-size: 0.875rem; font-weight: 600; text-transform: uppercase;">
                                总检测数
                            </div>
                            <div style="color: #1e3a8a; font-size: 2.5rem; font-weight: 800; margin-top: 0.5rem;">
                                {total}
                            </div>
                        </div>
                        """, unsafe_allow_html=True)
                    
                    with col2:
                        st.markdown(f"""
                        <div style="
                            background: linear-gradient(135deg, #d1fae5 0%, #a7f3d0 100%);
                            padding: 1.5rem;
                            border-radius: 12px;
                            text-align: center;
                            box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
                        ">
                            <div style="color: #065f46; font-size: 0.875rem; font-weight: 600; text-transform: uppercase;">
                                保留数
                            </div>
                            <div style="color: #064e3b; font-size: 2.5rem; font-weight: 800; margin-top: 0.5rem;">
                                {kept}
                            </div>
                        </div>
                        """, unsafe_allow_html=True)
                    
                    with col3:
                        st.markdown(f"""
                        <div style="
                            background: linear-gradient(135deg, #fce7f3 0%, #fbcfe8 100%);
                            padding: 1.5rem;
                            border-radius: 12px;
                            text-align: center;
                            box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
                        ">
                            <div style="color: #9f1239; font-size: 0.875rem; font-weight: 600; text-transform: uppercase;">
                                保留率
                            </div>
                            <div style="color: #831843; font-size: 2.5rem; font-weight: 800; margin-top: 0.5rem;">
                                {keep_rate:.1f}%
                            </div>
                        </div>
                        """, unsafe_allow_html=True)
                    
                    st.markdown("<br>", unsafe_allow_html=True)
                    
                    st.markdown("### 📋 检测详情")
                    df_data = []
                    for r in results:
                        df_data.append({
                            '图片': r.get('image_name', 'N/A'),
                            '类别': r.get('label', 'N/A'),
                            '分数': f"{r.get('score', 0):.3f}",
                            'VLM决策': r.get('vlm_decision', 'N/A'),
                            '理由': r.get('reason', r.get('vlm_reasoning', ''))[:50]
                        })
                    
                    if df_data:
                        st.dataframe(
                            pd.DataFrame(df_data), 
                            use_container_width=True,
                            hide_index=True,
                            height=400
                        )
                else:
                    st.info("文件为空或格式不正确")
                    
            except Exception as e:
                st.error(f"❌ 加载失败: {e}")


# ==================== Tab 5: 导出结果 ====================
with tab5:
    st.markdown("## 💾 导出结果")
    
    ls_id, ls_url = pm.get_label_studio_project(current_project)
    if ls_id and user_info:
        col1, col2 = st.columns([3, 1])
        with col2:
            render_label_studio_button(user_info, ls_id, context="tab5", show_title=False)
    
    st.divider()
    
    if not processed_dir.exists():
        st.markdown("""
        <div style="
            background: #fef3c7;
            border: 2px dashed #f59e0b;
            padding: 2rem;
            border-radius: 12px;
            text-align: center;
            margin: 2rem 0;
        ">
            <div style="font-size: 3rem;">📭</div>
            <div style="font-size: 1.25rem; font-weight: 700; color: #92400e; margin-top: 1rem;">
                暂无可导出的结果
            </div>
        </div>
        """, unsafe_allow_html=True)
    else:
        json_files = list(processed_dir.glob("*.json"))
        
        if not json_files:
            st.warning("⚠️ 暂无可导出的文件")
        else:
            st.markdown("### 📋 可导出的文件")
            
            for idx, json_file in enumerate(json_files):
                with st.expander(f"📄 {json_file.name}", expanded=(idx == 0)):
                    try:
                        with open(json_file, 'r', encoding='utf-8') as f:
                            data = json.load(f)
                        
                        col1, col2 = st.columns([2, 1])
                        
                        with col1:
                            if isinstance(data, list):
                                st.markdown(f"""
                                <div style="
                                    background: #f3f4f6;
                                    padding: 1rem;
                                    border-radius: 8px;
                                    margin-bottom: 1rem;
                                ">
                                    <div style="font-weight: 600; color: #1f2937;">
                                        📊 记录数: <span style="color: #667eea;">{len(data)}</span>
                                    </div>
                                </div>
                                """, unsafe_allow_html=True)
                        
                        with col2:
                            json_str = json.dumps(data, ensure_ascii=False, indent=2)
                            st.download_button(
                                label="📥 下载文件",
                                data=json_str,
                                file_name=json_file.name,
                                mime="application/json",
                                key=f"dl_{json_file.name}",
                                use_container_width=True
                            )
                        
                        # 预览前几行
                        if isinstance(data, list) and data:
                            st.markdown("**数据预览 (前3条):**")
                            preview_data = data[:3]
                            st.json(preview_data)
                            
                    except Exception as e:
                        st.error(f"无法读取: {e}")


# ==================== Tab 6: 模型训练 ====================
with tab6:
    st.markdown("## 🤖 模型训练")
    
    ls_id, ls_url = pm.get_label_studio_project(current_project)
    if user_info:
        col1, col2 = st.columns([3, 1])
        with col1:
            if ls_id:
                st.success(f"✅ 已关联 Label Studio 项目 (ID: {ls_id})")
            else:
                st.warning("⚠️ 尚未关联 Label Studio 项目，请先完成批量处理")
        with col2:
            if ls_id:
                render_label_studio_button(user_info, ls_id, context="tab6", show_title=False)
    
    st.divider()
    
    if not ls_id:
        st.markdown("""
        <div style="
            background: #fef3c7;
            border-left: 4px solid #f59e0b;
            padding: 1.5rem;
            border-radius: 12px;
            margin: 2rem 0;
        ">
            <div style="color: #92400e; font-weight: 600; font-size: 1.125rem; margin-bottom: 1rem;">
                ⚠️ 请先完成前置步骤
            </div>
            <div style="color: #78350f;">
                <div style="margin-bottom: 0.5rem;">💡 <strong>推荐流程:</strong></div>
                <ol style="margin-left: 1.5rem; line-height: 1.8;">
                    <li>上传数据</li>
                    <li>定义类别</li>
                    <li>批量处理</li>
                    <li>在 Label Studio 标注</li>
                    <li>返回训练</li>
                </ol>
            </div>
        </div>
        """, unsafe_allow_html=True)
        st.stop()
    
    # 获取 Label Studio Token
    ls_user_id, ls_token, ls_org_id = ensure_label_studio_account(
        username, user_manager, ls_user_manager
    )
    
    if not ls_token:
        st.error("❌ 无法获取 Label Studio Token")
        st.stop()
    
    # 子标签页
    train_tab1, train_tab2, train_tab3 = st.tabs([
        "🚀 开始训练",
        "📜 训练历史", 
        "🧪 模型测试"
    ])
    
    # ==================== 子Tab 1: 开始训练 ====================
    with train_tab1:
        st.markdown("### ⚙️ 训练配置")
        
        col1, col2 = st.columns(2)
        
        with col1:
            train_epochs = st.number_input(
                "训练轮数 (Epochs)",
                min_value=10,
                max_value=500,
                value=100,
                step=10,
                help="建议首次训练使用 100 轮"
            )
            
            train_batch = st.selectbox(
                "批次大小 (Batch Size)",
                options=[8, 16, 32, 64],
                index=1,
                help="根据 GPU 显存选择，显存小选 8 或 16"
            )
        
        with col2:
            train_imgsz = st.selectbox(
                "图片尺寸 (Image Size)",
                options=[416, 512, 640, 800, 1024],
                index=2,
                help="640 是标准尺寸，更大尺寸需要更多显存"
            )
            
            train_model = st.selectbox(
                "预训练模型",
                options=["yolov8n.pt", "yolov8s.pt", "yolov8m.pt", "yolov8l.pt", "yolov8x.pt"],
                index=0,
                help="n=nano(最快), s=small, m=medium, l=large, x=extra large(最强)"
            )
        
        st.divider()
        
        # 标注统计
        st.markdown("### 📊 标注统计")
        
        cache_key = f"annotations_{current_project}_{ls_id}"
        labels_cache_key = f"labels_count_{current_project}_{ls_id}"
        
        train_manager = TrainingManager(
            label_studio_url=LABEL_STUDIO_URL,
            api_key=ls_token,
            organization_name=organization_name
        )
        
        col1, col2 = st.columns([3, 1])
        with col2:
            if st.button("🔄 刷新统计", key="refresh_stats"):
                for key in [cache_key, labels_cache_key]:
                    if key in st.session_state:
                        del st.session_state[key]
                st.rerun()
        
        if cache_key not in st.session_state:
            with st.spinner("正在获取标注统计..."):
                st.session_state[cache_key] = train_manager.get_project_annotations(ls_id)
        
        annotated_tasks = st.session_state.get(cache_key)
        
        if annotated_tasks:
            total_annotations = sum(
                len(t['annotation'].get('result', []))
                for t in annotated_tasks
            )
            
            col1, col2, col3 = st.columns(3)
            with col1:
                st.metric("已标注图片", len(annotated_tasks))
            with col2:
                st.metric("标注框总数", total_annotations)
            with col3:
                avg_per_image = total_annotations / len(annotated_tasks) if annotated_tasks else 0
                st.metric("平均每图", f"{avg_per_image:.1f}")
            
            # 类别统计
            if labels_cache_key not in st.session_state:
                labels_count = {}
                for task_data in annotated_tasks:
                    for result in task_data['annotation'].get('result', []):
                        if result.get('type') == 'rectanglelabels':
                            labels = result.get('value', {}).get('rectanglelabels', [])
                            for label in labels:
                                labels_count[label] = labels_count.get(label, 0) + 1
                st.session_state[labels_cache_key] = labels_count
            
            labels_count = st.session_state[labels_cache_key]
            
            if labels_count:
                st.markdown("**各类别标注数量:**")
                label_df = pd.DataFrame([
                    {"类别": k, "数量": v}
                    for k, v in sorted(labels_count.items(), key=lambda x: -x[1])
                ])
                st.dataframe(
                    label_df, 
                    use_container_width=True, 
                    hide_index=True,
                    height=(len(label_df) + 1) * 35 + 3
                )
            
            st.divider()
            
            # 训练按钮
            st.markdown("### 🚀 开始训练")
            
            min_images = 5
            if len(annotated_tasks) < min_images:
                st.warning(f"⚠️ 建议至少标注 {min_images} 张图片再开始训练（当前: {len(annotated_tasks)}）")
            
            image_source_dir = str(pm.get_upload_dir(current_project))
            labels_def = project_info.get('labels', {})
            
            if st.button("🎯 开始训练", type="primary", disabled=len(annotated_tasks) < 3, use_container_width=True):
                if f"training_started_{current_project}" not in st.session_state:
                    st.session_state[f"training_started_{current_project}"] = True
                    
                    log_container = st.empty()
                    logs = []
                    
                    def progress_callback(message: str, status: str):
                        logs.append(f"[{status.upper()}] {message}")
                        log_container.code("\n".join(logs[-20:]))
                    
                    st.info("🔄 正在准备训练数据...")
                
                try:
                    train_manager_with_callback = TrainingManager(
                        label_studio_url=LABEL_STUDIO_URL,
                        api_key=ls_token,
                        organization_name=organization_name,
                        progress_callback=progress_callback
                    )
                    
                    result = train_manager_with_callback.full_training_pipeline(
                        project_id=ls_id,
                        project_name=current_project,
                        labels_def=labels_def,
                        image_source_dir=image_source_dir,
                        epochs=train_epochs,
                        batch_size=train_batch
                    )
                    
                    if result['success']:
                        st.markdown("""
                        <div style="
                            background: linear-gradient(135deg, #10b981 0%, #059669 100%);
                            color: white;
                            padding: 2rem;
                            border-radius: 12px;
                            text-align: center;
                            margin: 2rem 0;
                            box-shadow: 0 10px 15px -3px rgba(16, 185, 129, 0.4);
                        ">
                            <div style="font-size: 3rem; margin-bottom: 1rem;">🎉</div>
                            <div style="font-size: 1.5rem; font-weight: 700;">
                                训练已成功启动！
                            </div>
                        </div>
                        """, unsafe_allow_html=True)
                        
                        st.info(f"📁 运行目录: `{result.get('run_dir')}`")
                        st.warning("⚠️ 训练正在后台运行，请在「训练历史」标签页查看进度")
                        
                        pm.update_project(current_project, {
                            'last_training_run': result.get('run_dir'),
                            'last_training_time': datetime.now().isoformat()
                        }, updated_by=username)
                    else:
                        st.error("❌ 训练启动失败")
                        for err in result.get('errors', []):
                            st.error(f"   - {err}")
                
                except Exception as e:
                    st.error(f"❌ 训练异常: {e}")
                    import traceback
                    st.code(traceback.format_exc())
        else:
            st.warning("⚠️ 暂无已标注的数据。请先在 Label Studio 中完成标注。")
            st.info("💡 提示: 在 Label Studio 中选择检测框，调整后点击「Submit」提交标注")


    # ==================== 子Tab 2: 训练历史 ====================
    with train_tab2:
        st.markdown("### 📜 训练历史")
        
        if st.button("🔄 刷新列表", key="refresh_history"):
            pass
        
        train_manager_history = TrainingManager(
            label_studio_url=LABEL_STUDIO_URL,
            api_key=ls_token,
            organization_name=organization_name
        )
        
        training_runs = train_manager_history.list_training_runs()
        
        if training_runs:
            for idx, run in enumerate(training_runs[:10]):
                run_name = run.get('run_name', 'Unknown')
                status = run.get('status', 'unknown')
                run_dir = run.get('run_dir', '')
                
                status_display = {
                    'running': ('🏃', '运行中', '#f59e0b'),
                    'completed': ('✅', '已完成', '#10b981'),
                    'failed': ('❌', '失败', '#ef4444'),
                    'interrupted': ('⏸️', '已中断', '#6b7280')
                }.get(status, ('❓', '未知', '#6b7280'))
                
                status_emoji, status_text, status_color = status_display
                
                with st.expander(f"{status_emoji} {run_name} - {status_text}", expanded=(idx == 0)):
                    st.markdown(f"""
                    <div style="
                        background: {status_color}15;
                        border-left: 4px solid {status_color};
                        padding: 1rem;
                        border-radius: 8px;
                        margin-bottom: 1rem;
                    ">
                        <div style="color: {status_color}; font-weight: 600; margin-bottom: 0.5rem;">
                            {status_emoji} 状态: {status_text}
                        </div>
                    </div>
                    """, unsafe_allow_html=True)
                    
                    col1, col2 = st.columns(2)
                    
                    with col1:
                        st.markdown(f"**开始时间:** {run.get('started_at', 'N/A')[:19] if run.get('started_at') else 'N/A'}")
                        if run.get('completed_at'):
                            st.markdown(f"**完成时间:** {run.get('completed_at', 'N/A')[:19]}")
                    
                    with col2:
                        if 'test_results' in run:
                            results = run['test_results']
                            st.metric("mAP50", f"{results.get('mAP50', 0):.4f}")
                            st.metric("mAP50-95", f"{results.get('mAP50_95', 0):.4f}")
                    
                    if 'last_log_lines' in run and run['last_log_lines']:
                        st.markdown("**📄 最近日志:**")
                        st.code("".join(run['last_log_lines'][-15:]), language="text")
                    
                    if status == 'completed':
                        st.divider()
                        st.markdown("**📦 模型下载:**")
                        
                        best_model_path = run.get('best_model')
                        if not best_model_path:
                            possible_paths = [
                                Path(run_dir) / run_name / 'weights' / 'best.pt',
                                Path(run_dir) / 'weights' / 'best.pt',
                            ]
                            for p in possible_paths:
                                if p.exists():
                                    best_model_path = str(p)
                                    break
                        
                        col1, col2, col3 = st.columns(3)
                        
                        with col1:
                            if best_model_path and Path(best_model_path).exists():
                                with open(best_model_path, 'rb') as f:
                                    st.download_button(
                                        label="📥 下载 best.pt",
                                        data=f.read(),
                                        file_name=f"{run_name}_best.pt",
                                        mime="application/octet-stream",
                                        key=f"dl_best_{idx}",
                                        use_container_width=True
                                    )
                            else:
                                st.warning("best.pt 不存在")
                        
                        with col2:
                            last_model_path = best_model_path.replace('best.pt', 'last.pt') if best_model_path else None
                            if last_model_path and Path(last_model_path).exists():
                                with open(last_model_path, 'rb') as f:
                                    st.download_button(
                                        label="📥 下载 last.pt",
                                        data=f.read(),
                                        file_name=f"{run_name}_last.pt",
                                        mime="application/octet-stream",
                                        key=f"dl_last_{idx}",
                                        use_container_width=True
                                    )
                        
                        with col3:
                            if best_model_path and Path(best_model_path).exists():
                                if st.button(f"🧪 测试此模型", key=f"use_model_{idx}", use_container_width=True):
                                    st.session_state['selected_test_model'] = {
                                        'path': best_model_path,
                                        'name': run_name
                                    }
                                    st.success(f"✅ 已选择，请切换到「模型测试」标签页")
                    
                    elif status == 'failed':
                        if run.get('error'):
                            st.error(f"错误信息: {run.get('error')}")
        else:
            st.info("暂无训练记录")
            st.markdown("请在「开始训练」标签页启动训练任务")
    
    # ==================== 子Tab 3: 模型测试 ====================
    with train_tab3:
        st.markdown("### 🧪 模型测试")
        
        available_models = []
        train_manager_test = TrainingManager(
            label_studio_url=LABEL_STUDIO_URL,
            api_key=ls_token,
            organization_name=organization_name
        )
        
        for run in train_manager_test.list_training_runs():
            if run.get('status') == 'completed':
                best_model = run.get('best_model')
                run_dir = run.get('run_dir', '')
                run_name = run.get('run_name', '')
                
                if not best_model:
                    possible_paths = [
                        Path(run_dir) / run_name / 'weights' / 'best.pt',
                        Path(run_dir) / 'weights' / 'best.pt',
                    ]
                    for p in possible_paths:
                        if p.exists():
                            best_model = str(p)
                            break
                
                if best_model and Path(best_model).exists():
                    available_models.append({
                        'name': run_name,
                        'path': best_model,
                        'mAP50': run.get('test_results', {}).get('mAP50', 0)
                    })
        
        if not available_models:
            st.markdown("""
            <div style="
                background: #fef3c7;
                border: 2px dashed #f59e0b;
                padding: 2rem;
                border-radius: 12px;
                text-align: center;
                margin: 2rem 0;
            ">
                <div style="font-size: 3rem;">🤖</div>
                <div style="font-size: 1.25rem; font-weight: 700; color: #92400e; margin-top: 1rem;">
                    暂无可用的训练模型
                </div>
                <div style="color: #78350f; margin-top: 0.5rem;">
                    请先完成至少一次训练
                </div>
            </div>
            """, unsafe_allow_html=True)
        else:
            st.markdown("**选择模型:**")
            
            default_idx = 0
            if 'selected_test_model' in st.session_state:
                for i, m in enumerate(available_models):
                    if m['path'] == st.session_state.get('selected_test_model', {}).get('path'):
                        default_idx = i
                        break
            
            model_options = [f"{m['name']} (mAP50: {m['mAP50']:.4f})" for m in available_models]
            selected_model_idx = st.selectbox(
                "选择要测试的模型",
                range(len(model_options)),
                index=default_idx,
                format_func=lambda x: model_options[x],
                key="model_selector"
            )
            
            selected_model = available_models[selected_model_idx]
            st.success(f"✅ 已选择: {selected_model['name']}")
            st.caption(f"模型路径: {selected_model['path']}")
            
            st.divider()
            
            st.markdown("**上传测试图片:**")
            
            uploaded_image = st.file_uploader(
                "选择图片",
                type=['jpg', 'jpeg', 'png', 'bmp', 'webp'],
                key="test_image_uploader"
            )
            
            conf_threshold = st.slider(
                "置信度阈值",
                min_value=0.1,
                max_value=1.0,
                value=0.25,
                step=0.05,
                help="只显示置信度高于此阈值的检测结果"
            )
            
            if uploaded_image:
                original_image = Image.open(uploaded_image)
                
                col1, col2 = st.columns(2)
                
                with col1:
                    st.markdown("**原始图片:**")
                    st.image(original_image, use_column_width=True)
                
                detect_clicked = st.button("🔍 开始检测", type="primary", key="detect_btn", use_container_width=True)
                
                if detect_clicked:
                    with st.spinner("正在检测..."):
                        try:
                            import subprocess
                            
                            detect_id = str(uuid.uuid4())[:8]
                            
                            temp_dir = Path("/tmp/yolo_detect")
                            temp_dir.mkdir(parents=True, exist_ok=True)
                            
                            temp_image_path = temp_dir / f"input_{detect_id}.jpg"
                            result_json_path = temp_dir / f"result_{detect_id}.json"
                            
                            original_image.save(str(temp_image_path))
                            
                            detect_script = "/root/autodl-fs/web_biaozhupingtai/scripts/detect_yolo.py"
                            
                            cmd = f"""
source /root/miniconda3/etc/profile.d/conda.sh && \
conda activate xingmu_yolo && \
python {detect_script} \
    --model "{selected_model['path']}" \
    --image "{temp_image_path}" \
    --output "{result_json_path}" \
    --conf {conf_threshold}
"""
                            
                            process = subprocess.run(
                                ["bash", "-c", cmd],
                                capture_output=True,
                                text=True,
                                timeout=120
                            )
                            
                            if result_json_path.exists():
                                with open(result_json_path, 'r', encoding='utf-8') as f:
                                    result_data = json.load(f)
                                
                                if result_data.get('success'):
                                    detections = result_data.get('detections', [])
                                    
                                    from PIL import ImageDraw, ImageFont
                                    
                                    result_image = original_image.copy()
                                    draw = ImageDraw.Draw(result_image)
                                    
                                    colors = ["#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#FF00FF", "#00FFFF", "#FFA500", "#800080"]
                                    class_colors = {}
                                    
                                    for det in detections:
                                        x1, y1, x2, y2 = det['bbox']
                                        conf = det['confidence']
                                        cls_name = det['class_name']
                                        
                                        if cls_name not in class_colors:
                                            class_colors[cls_name] = colors[len(class_colors) % len(colors)]
                                        color = class_colors[cls_name]
                                        
                                        draw.rectangle([x1, y1, x2, y2], outline=color, width=3)
                                        
                                        label = f"{cls_name}: {conf:.2f}"
                                        try:
                                            font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 16)
                                        except:
                                            font = ImageFont.load_default()
                                        
                                        text_bbox = draw.textbbox((x1, y1), label, font=font)
                                        text_w = text_bbox[2] - text_bbox[0]
                                        text_h = text_bbox[3] - text_bbox[1]
                                        
                                        draw.rectangle([x1, y1 - text_h - 6, x1 + text_w + 6, y1], fill=color)
                                        draw.text((x1 + 3, y1 - text_h - 3), label, fill="white", font=font)
                                    
                                    with col2:
                                        st.markdown("**检测结果:**")
                                        st.image(result_image, use_column_width=True)
                                    
                                    st.session_state['detection_result'] = {
                                        'detections': detections,
                                        'result_image': result_image,
                                        'success': True
                                    }
                                    
                                else:
                                    st.error(f"检测失败: {result_data.get('error', '未知错误')}")
                                    st.session_state['detection_result'] = {'success': False}
                            else:
                                st.error(f"检测失败，结果文件不存在")
                                if process.stderr:
                                    st.code(process.stderr, language="text")
                                st.session_state['detection_result'] = {'success': False}
                            
                            try:
                                if temp_image_path.exists():
                                    temp_image_path.unlink()
                                if result_json_path.exists():
                                    result_json_path.unlink()
                            except:
                                pass
                                
                        except subprocess.TimeoutExpired:
                            st.error("❌ 检测超时，请稍后重试")
                        except Exception as e:
                            st.error(f"❌ 检测失败: {e}")
                            import traceback
                            st.code(traceback.format_exc())
                
                elif 'detection_result' in st.session_state and st.session_state['detection_result'].get('success'):
                    result_data = st.session_state['detection_result']
                    detections = result_data.get('detections', [])
                    
                    with col2:
                        st.markdown("**检测结果:**")
                        if 'result_image' in result_data:
                            st.image(result_data['result_image'], use_column_width=True)
                
                if 'detection_result' in st.session_state and st.session_state['detection_result'].get('success'):
                    detections = st.session_state['detection_result'].get('detections', [])
                    
                    st.divider()
                    st.markdown("### 📊 检测统计")
                    
                    if detections:
                        class_counts = {}
                        for det in detections:
                            cls_name = det['class_name']
                            class_counts[cls_name] = class_counts.get(cls_name, 0) + 1
                        
                        col1, col2, col3 = st.columns(3)
                        with col1:
                            st.metric("检测目标数", len(detections))
                        with col2:
                            st.metric("类别数", len(class_counts))
                        with col3:
                            avg_conf = sum(d['confidence'] for d in detections) / len(detections)
                            st.metric("平均置信度", f"{avg_conf:.2f}")
                        
                        st.markdown("**检测详情:**")
                        det_df = pd.DataFrame([
                            {
                                "类别": d['class_name'],
                                "置信度": f"{d['confidence']:.3f}",
                                "位置": f"({int(d['bbox'][0])}, {int(d['bbox'][1])}) - ({int(d['bbox'][2])}, {int(d['bbox'][3])})"
                            }
                            for d in detections
                        ])
                        st.dataframe(det_df, use_container_width=True, hide_index=True)
                    else:
                        st.info("未检测到任何目标，请尝试降低置信度阈值")


# ==================== 页脚 ====================
st.markdown("---")
st.markdown("""
<div style="text-align: center; color: #9ca3af; font-size: 0.875rem; padding: 2rem 0;">
    🎯 自动标注与清洗平台 v3.0 | Powered by Streamlit
</div>
""", unsafe_allow_html=True)