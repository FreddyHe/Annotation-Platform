"""
用户认证管理模块
处理平台用户的注册、登录、会话管理
集成 Label Studio 数据库权限自动修复
支持机构(Organization)管理
"""
import json
import hashlib
import secrets
import os
from pathlib import Path
from datetime import datetime, timedelta
from typing import Optional, Dict, Any, Tuple
import logging
import sqlite3

logger = logging.getLogger(__name__)


class UserManager:
    """用户管理器 - 管理自动标注平台的用户"""
    
    def __init__(self, data_dir: str = "/root/autodl-fs/web_biaozhupingtai/data"):
        self.data_dir = Path(data_dir)
        self.users_file = self.data_dir / "users.json"
        self.sessions_file = self.data_dir / "sessions.json"
        self._ensure_files()
    
    def _ensure_files(self):
        """确保必要的文件存在"""
        self.data_dir.mkdir(parents=True, exist_ok=True)
        
        if not self.users_file.exists():
            self._save_users({})
        
        if not self.sessions_file.exists():
            self._save_sessions({})
    
    def _load_users(self) -> Dict[str, Any]:
        try:
            with open(self.users_file, 'r', encoding='utf-8') as f:
                return json.load(f)
        except:
            return {}
    
    def _save_users(self, users: Dict[str, Any]):
        with open(self.users_file, 'w', encoding='utf-8') as f:
            json.dump(users, f, ensure_ascii=False, indent=2)
    
    def _load_sessions(self) -> Dict[str, Any]:
        try:
            with open(self.sessions_file, 'r', encoding='utf-8') as f:
                return json.load(f)
        except:
            return {}
    
    def _save_sessions(self, sessions: Dict[str, Any]):
        with open(self.sessions_file, 'w', encoding='utf-8') as f:
            json.dump(sessions, f, ensure_ascii=False, indent=2)
    
    def _hash_password(self, password: str, salt: str = None) -> tuple:
        if salt is None:
            salt = secrets.token_hex(16)
        hashed = hashlib.pbkdf2_hmac(
            'sha256',
            password.encode('utf-8'),
            salt.encode('utf-8'),
            100000
        ).hex()
        return f"{salt}${hashed}", salt
    
    def _verify_password(self, password: str, stored_hash: str) -> bool:
        try:
            salt, hashed = stored_hash.split('$')
            new_hash, _ = self._hash_password(password, salt)
            return new_hash == stored_hash
        except:
            return False
    
    def register_user(
        self, 
        username: str, 
        password: str, 
        email: str = None, 
        display_name: str = None,
        organization_name: str = None  # 新增：机构名称
    ) -> tuple:
        """
        注册用户
        
        Args:
            username: 用户名
            password: 密码
            email: 邮箱（可选）
            display_name: 显示名称（可选）
            organization_name: 机构名称（可选，用于创建 Label Studio 组织）
        
        Returns:
            (success: bool, message: str)
        """
        users = self._load_users()
        username = username.strip().lower()
        
        # 验证
        if not username: 
            return False, "用户名不能为空"
        if len(username) < 3: 
            return False, "用户名至少3个字符"
        if not username.isalnum(): 
            return False, "用户名只能包含字母和数字"
        if username in users: 
            return False, "用户名已存在"
        if len(password) < 6: 
            return False, "密码至少6个字符"
        
        # 处理机构名称
        if organization_name:
            organization_name = organization_name.strip()
            if len(organization_name) < 2:
                return False, "机构名称至少2个字符"
        else:
            # 默认使用用户名作为机构名称
            organization_name = f"{username}_workspace"
        
        password_hash, _ = self._hash_password(password)
        users[username] = {
            "username": username,
            "password_hash": password_hash,
            "email": email or f"{username}@auto-annotation.local",
            "display_name": display_name or username,
            "organization_name": organization_name,  # 保存机构名称
            "created_at": datetime.now().isoformat(),
            "last_login": None,
            "is_active": True,
            "label_studio_user_id": None,
            "label_studio_token": None,
            "label_studio_org_id": None,  # 新增：Label Studio 组织 ID
        }
        self._save_users(users)
        self._create_user_directories(username)
        logger.info(f"✅ 用户注册成功: {username}, 机构: {organization_name}")
        return True, "注册成功"
    
    def _create_user_directories(self, username: str):
        user_base = self.data_dir / "users" / username
        dirs_to_create = [
            user_base / "uploads",
            user_base / "processed",
            user_base / "annotations",
        ]
        for dir_path in dirs_to_create:
            dir_path.mkdir(parents=True, exist_ok=True)
        projects_file = user_base / "projects.json"
        if not projects_file.exists():
            with open(projects_file, 'w', encoding='utf-8') as f:
                json.dump({}, f)
    
    def login(self, username: str, password: str) -> tuple:
        users = self._load_users()
        username = username.strip().lower()
        if username not in users: 
            return False, "用户名或密码错误"
        user = users[username]
        if not user.get("is_active", True): 
            return False, "账号已被禁用"
        if not self._verify_password(password, user["password_hash"]): 
            return False, "用户名或密码错误"
        
        users[username]["last_login"] = datetime.now().isoformat()
        self._save_users(users)
        
        session_token = secrets.token_hex(32)
        sessions = self._load_sessions()
        sessions = {k: v for k, v in sessions.items() if v.get("username") != username}
        sessions[session_token] = {
            "username": username,
            "created_at": datetime.now().isoformat(),
            "expires_at": (datetime.now() + timedelta(days=7)).isoformat(),
        }
        self._save_sessions(sessions)
        return True, session_token
    
    def verify_session(self, session_token: str) -> Optional[str]:
        if not session_token: 
            return None
        sessions = self._load_sessions()
        if session_token not in sessions: 
            return None
        session = sessions[session_token]
        if datetime.now() > datetime.fromisoformat(session["expires_at"]):
            del sessions[session_token]
            self._save_sessions(sessions)
            return None
        return session["username"]
    
    def logout(self, session_token: str) -> bool:
        sessions = self._load_sessions()
        if session_token in sessions:
            del sessions[session_token]
            self._save_sessions(sessions)
            return True
        return False
    
    def get_user(self, username: str) -> Optional[Dict[str, Any]]:
        users = self._load_users()
        username = username.lower()
        if username not in users: 
            return None
        user = users[username].copy()
        user.pop("password_hash", None)
        return user
    
    def update_user(self, username: str, updates: Dict[str, Any]) -> bool:
        users = self._load_users()
        username = username.lower()
        if username not in users: 
            return False
        protected_fields = {"username", "password_hash", "created_at"}
        for key, value in updates.items():
            if key not in protected_fields:
                users[username][key] = value
        self._save_users(users)
        return True


class LabelStudioUserManager:
    """Label Studio 用户管理器 - 直接操作数据库"""
    
    def __init__(self, db_path: str = "/root/label_studio_data_fast/label_studio.sqlite3"):
        self.db_path = db_path
    
    def _get_connection(self):
        """获取数据库连接，设置超时避免锁定"""
        conn = sqlite3.connect(self.db_path, timeout=30)
        conn.execute("PRAGMA busy_timeout = 30000")
        return conn
    
    def _hash_password_django(self, password: str) -> str:
        import hashlib
        import base64
        iterations = 870000
        salt = secrets.token_urlsafe(16)[:22]
        dk = hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), salt.encode('utf-8'), iterations)
        hash_b64 = base64.b64encode(dk).decode('utf-8')
        return f"pbkdf2_sha256${iterations}${salt}${hash_b64}"
    
    def _get_or_create_organization(self, cursor, org_name: str, created_by_id: int = None) -> int:
        """
        获取或创建组织
        
        Args:
            cursor: 数据库游标
            org_name: 组织名称
            created_by_id: 创建者用户 ID（可选）
        
        Returns:
            组织 ID
        """
        # 1. 先检查是否已存在同名组织
        cursor.execute("SELECT id FROM organization WHERE title = ?", (org_name,))
        row = cursor.fetchone()
        if row:
            logger.info(f"📋 找到现有组织 '{org_name}', ID: {row[0]}")
            return row[0]
        
        # 2. 创建新组织
        now = datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
        
        # 检查表结构，获取可用列
        cursor.execute("PRAGMA table_info(organization)")
        columns = [info[1] for info in cursor.fetchall()]
        
        # 构建 INSERT 语句（根据可用列）
        insert_columns = ['title', 'created_at', 'updated_at']
        insert_values = [org_name, now, now]
        
        if 'created_by_id' in columns and created_by_id:
            insert_columns.append('created_by_id')
            insert_values.append(created_by_id)
        
        if 'token_authentication_enabled' in columns:
            insert_columns.append('token_authentication_enabled')
            insert_values.append(1)
        
        placeholders = ', '.join(['?' for _ in insert_values])
        columns_str = ', '.join(insert_columns)
        
        cursor.execute(f"""
            INSERT INTO organization ({columns_str})
            VALUES ({placeholders})
        """, insert_values)
        
        org_id = cursor.lastrowid
        logger.info(f"✅ 创建新组织 '{org_name}', ID: {org_id}")
        return org_id
    
    def _add_user_to_organization(self, cursor, org_id: int, user_id: int) -> bool:
        """
        将用户添加到组织
        
        Args:
            cursor: 数据库游标
            org_id: 组织 ID
            user_id: 用户 ID
        
        Returns:
            是否成功
        """
        try:
            # 检查是否已存在
            cursor.execute("""
                SELECT id FROM organizations_organizationmember 
                WHERE organization_id = ? AND user_id = ?
            """, (org_id, user_id))
            
            if cursor.fetchone():
                logger.info(f"📋 用户 {user_id} 已在组织 {org_id} 中")
                return True
            
            # 获取表结构
            cursor.execute("PRAGMA table_info(organizations_organizationmember)")
            columns = [info[1] for info in cursor.fetchall()]
            
            now = datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
            
            # 构建 INSERT 语句
            insert_columns = ['organization_id', 'user_id']
            insert_values = [org_id, user_id]
            
            if 'created_at' in columns:
                insert_columns.append('created_at')
                insert_values.append(now)
            
            if 'updated_at' in columns:
                insert_columns.append('updated_at')
                insert_values.append(now)
            
            if 'deleted_at' in columns:
                insert_columns.append('deleted_at')
                insert_values.append(None)
            
            placeholders = ', '.join(['?' for _ in insert_values])
            columns_str = ', '.join(insert_columns)
            
            cursor.execute(f"""
                INSERT INTO organizations_organizationmember ({columns_str})
                VALUES ({placeholders})
            """, insert_values)
            
            logger.info(f"✅ 已将用户 {user_id} 添加到组织 {org_id}")
            return True
            
        except Exception as e:
            logger.error(f"❌ 添加用户到组织失败: {e}")
            return False
    
    def _ensure_legacy_auth_enabled(self, cursor, org_id: int):
        """
        核心修复：强制开启组织的 Legacy Token 认证。
        解决 'legacy token authentication has been disabled' 报错。
        """
        try:
            cursor.execute("PRAGMA table_info(organization)")
            columns = [info[1] for info in cursor.fetchall()]
            
            if 'token_authentication_enabled' in columns:
                cursor.execute(
                    "UPDATE organization SET token_authentication_enabled = 1 WHERE id = ?", 
                    (org_id,)
                )
                if cursor.rowcount > 0:
                    logger.info(f"🛠️ 已强制为组织 {org_id} 开启 Legacy Token 认证")
        except Exception as e:
            logger.warning(f"⚠️ 尝试开启 Token 认证失败: {e}")

    def get_or_create_user_with_token(
        self,
        username: str,
        email: str,
        password: str,
        organization_name: str = None  # 新增：机构名称
    ) -> Tuple[Optional[int], Optional[str], Optional[int]]:
        """
        获取或创建用户，并确保有 API Token 和 组织关联
        
        Args:
            username: 用户名
            email: 邮箱
            password: 密码
            organization_name: 机构名称（如果为空，使用默认组织）
        
        Returns:
            (user_id, token, org_id) 三元组
        """
        conn = None
        try:
            conn = self._get_connection()
            cursor = conn.cursor()
            
            user_id = None
            org_id = None
            
            # 1. 检查用户是否存在
            cursor.execute("SELECT id FROM htx_user WHERE email = ?", (email,))
            row = cursor.fetchone()
            
            if row:
                user_id = row[0]
                logger.info(f"📋 找到现有用户 ID: {user_id}")
                
                # 获取用户当前的组织
                cursor.execute("SELECT active_organization_id FROM htx_user WHERE id = ?", (user_id,))
                org_row = cursor.fetchone()
                if org_row and org_row[0]:
                    org_id = org_row[0]
            else:
                # 2. 创建新用户
                password_hash = self._hash_password_django(password)
                now = datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
                
                # 先创建用户（不设置 active_organization_id）
                cursor.execute("""
                    INSERT INTO htx_user (
                        password, last_login, is_superuser, username, email,
                        first_name, last_name, is_staff, is_active, date_joined,
                        last_activity, activity_at, phone, avatar,
                        active_organization_id, allow_newsletters
                    ) VALUES (?, NULL, 0, ?, ?, '', '', 0, 1, ?, ?, ?, '', '', NULL, 0)
                """, (password_hash, username, email, now, now, now))
                
                user_id = cursor.lastrowid
                logger.info(f"✅ 创建新用户 ID: {user_id}")
            
            # 3. 获取或创建组织
            if organization_name:
                org_id = self._get_or_create_organization(cursor, organization_name, user_id)
            elif not org_id:
                # 使用默认组织
                cursor.execute("SELECT id FROM organization ORDER BY id LIMIT 1")
                default_org = cursor.fetchone()
                if default_org:
                    org_id = default_org[0]
                else:
                    # 创建默认组织
                    org_id = self._get_or_create_organization(cursor, f"{username}_workspace", user_id)
            
            # 4. 确保 Token 认证已开启
            self._ensure_legacy_auth_enabled(cursor, org_id)
            
            # 5. 将用户添加到组织
            self._add_user_to_organization(cursor, org_id, user_id)
            
            # 6. 更新用户的 active_organization_id
            cursor.execute("""
                UPDATE htx_user SET active_organization_id = ? WHERE id = ?
            """, (org_id, user_id))
            
            # 7. 获取或创建 Token
            cursor.execute("SELECT key FROM authtoken_token WHERE user_id = ?", (user_id,))
            token_row = cursor.fetchone()
            
            token = None
            if token_row:
                token = token_row[0]
                logger.info(f"📋 用户 {user_id} 已有 API Token")
            else:
                token = secrets.token_hex(20)
                now = datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
                cursor.execute("""
                    INSERT INTO authtoken_token (key, created, user_id)
                    VALUES (?, ?, ?)
                """, (token, now, user_id))
                logger.info(f"✅ 创建新 Token")
            
            conn.commit()
            logger.info(f"✅ 用户设置完成: user_id={user_id}, org_id={org_id}")
            return user_id, token, org_id
            
        except Exception as e:
            if conn: 
                conn.rollback()
            logger.error(f"❌ 用户操作失败: {e}")
            import traceback
            logger.error(traceback.format_exc())
            return None, None, None
        finally:
            if conn: 
                conn.close()
    
    def get_user_organization(self, user_id: int) -> Optional[Dict[str, Any]]:
        """获取用户的组织信息"""
        conn = None
        try:
            conn = self._get_connection()
            cursor = conn.cursor()
            
            cursor.execute("""
                SELECT o.id, o.title 
                FROM organization o
                JOIN htx_user u ON u.active_organization_id = o.id
                WHERE u.id = ?
            """, (user_id,))
            
            row = cursor.fetchone()
            if row:
                return {"id": row[0], "title": row[1]}
            return None
            
        except Exception as e:
            logger.error(f"获取用户组织失败: {e}")
            return None
        finally:
            if conn:
                conn.close()