"""
登录页面组件
Streamlit 登录/注册界面
支持机构名称输入
"""
import streamlit as st
from typing import Optional, Callable
import logging

logger = logging.getLogger(__name__)


def render_login_page(user_manager) -> Optional[str]:
    """
    渲染登录页面
    
    Args:
        user_manager: UserManager 实例
    
    Returns:
        登录成功返回 username，否则返回 None
    """
    st.set_page_config(
        page_title="自动标注与清洗平台 - 登录",
        page_icon="🔐",
        layout="centered",
        initial_sidebar_state="collapsed"
    )
    
    # 隐藏侧边栏
    st.markdown("""
    <style>
        [data-testid="stSidebar"] {display: none;}
        .main > div {padding-top: 2rem;}
    </style>
    """, unsafe_allow_html=True)
    
    st.title("🎯 自动标注与清洗平台")
    st.markdown("---")
    
    # 登录/注册选项卡
    tab1, tab2 = st.tabs(["🔑 登录", "📝 注册"])
    
    with tab1:
        render_login_form(user_manager)
    
    with tab2:
        render_register_form(user_manager)
    
    return None


def render_login_form(user_manager):
    """渲染登录表单"""
    st.subheader("用户登录")
    
    with st.form("login_form"):
        username = st.text_input("用户名", key="login_username")
        password = st.text_input("密码", type="password", key="login_password")
        
        col1, col2 = st.columns([1, 2])
        with col1:
            submit = st.form_submit_button("登录", type="primary", use_container_width=True)
        
        if submit:
            if not username or not password:
                st.error("请输入用户名和密码")
            else:
                success, result = user_manager.login(username, password)
                if success:
                    # 保存会话 token
                    st.session_state['session_token'] = result
                    st.session_state['username'] = username.lower()
                    st.success("✅ 登录成功！")
                    st.rerun()
                else:
                    st.error(f"❌ {result}")


def render_register_form(user_manager):
    """渲染注册表单"""
    st.subheader("新用户注册")
    
    with st.form("register_form"):
        username = st.text_input(
            "用户名 *", 
            key="reg_username",
            help="3-20个字符，只能包含字母和数字"
        )
        
        email = st.text_input(
            "邮箱（可选）", 
            key="reg_email",
            help="用于关联 Label Studio 账号"
        )
        
        # 新增：机构名称输入
        organization_name = st.text_input(
            "机构/工作区名称 *",
            key="reg_organization",
            help="您的机构或团队名称，将在 Label Studio 中创建独立的工作区",
            placeholder="例如：AI研究院、数据标注组"
        )
        
        st.markdown("---")
        
        password = st.text_input(
            "密码 *", 
            type="password", 
            key="reg_password",
            help="至少6个字符"
        )
        
        password_confirm = st.text_input(
            "确认密码 *", 
            type="password", 
            key="reg_password_confirm"
        )
        
        # 提示信息
        st.info("""
        💡 **提示：**
        - 机构名称将用于创建您在 Label Studio 中的独立工作区
        - 您的项目和数据将与其他用户隔离
        - 如果不填写机构名称，将自动创建以用户名命名的工作区
        """)
        
        col1, col2 = st.columns([1, 2])
        with col1:
            submit = st.form_submit_button("注册", type="primary", use_container_width=True)
        
        if submit:
            # 验证
            if not username or not password:
                st.error("请填写用户名和密码")
            elif not organization_name:
                st.error("请填写机构/工作区名称")
            elif len(organization_name.strip()) < 2:
                st.error("机构名称至少2个字符")
            elif password != password_confirm:
                st.error("两次输入的密码不一致")
            else:
                success, message = user_manager.register_user(
                    username=username,
                    password=password,
                    email=email if email else None,
                    organization_name=organization_name.strip()
                )
                
                if success:
                    st.success(f"✅ {message}")
                    st.info(f"🏢 已为您创建工作区：**{organization_name.strip()}**")
                    st.info("请切换到「登录」标签页登录")
                else:
                    st.error(f"❌ {message}")


def check_authentication(user_manager) -> Optional[str]:
    """
    检查用户认证状态
    
    Returns:
        如果已登录返回 username，否则返回 None
    """
    session_token = st.session_state.get('session_token')
    
    if not session_token:
        return None
    
    username = user_manager.verify_session(session_token)
    
    if username:
        st.session_state['username'] = username
        return username
    else:
        # 会话过期
        st.session_state.pop('session_token', None)
        st.session_state.pop('username', None)
        return None


def render_user_menu(user_manager, username: str):
    """
    渲染用户菜单（在侧边栏显示）
    """
    user = user_manager.get_user(username)
    display_name = user.get('display_name', username) if user else username
    organization_name = user.get('organization_name', 'N/A') if user else 'N/A'
    
    st.sidebar.markdown("---")
    st.sidebar.markdown(f"👤 **{display_name}**")
    st.sidebar.markdown(f"🏢 {organization_name}")
    
    if st.sidebar.button("🚪 退出登录", key="logout_btn"):
        user_manager.logout(st.session_state.get('session_token'))
        st.session_state.pop('session_token', None)
        st.session_state.pop('username', None)
        st.rerun()


def require_login(user_manager):
    """
    装饰器/函数：要求登录
    如果未登录，显示登录页面并停止执行
    
    Returns:
        username 如果已登录
    """
    username = check_authentication(user_manager)
    
    if not username:
        render_login_page(user_manager)
        st.stop()
    
    return username