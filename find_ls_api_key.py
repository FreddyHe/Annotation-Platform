#!/usr/bin/env python3
"""
查找 Label Studio 用户的 API Token

使用方法:
    python find_ls_api_key.py
"""

import sqlite3
import os

# Label Studio 数据库路径
DB_PATH = "/root/label_studio_data_fast/label_studio.sqlite3"

def find_api_key():
    print("=" * 60)
    print("🔍 查找 Label Studio API Token")
    print("=" * 60)
    
    if not os.path.exists(DB_PATH):
        print(f"❌ 数据库不存在: {DB_PATH}")
        return
    
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()
    
    # 查找用户
    print("\n📋 所有用户:")
    print("-" * 60)
    
    try:
        cursor.execute("""
            SELECT id, email, username, first_name, last_name 
            FROM htx_user 
            ORDER BY id
        """)
        users = cursor.fetchall()
        
        for user in users:
            user_id, email, username, first_name, last_name = user
            print(f"  ID: {user_id}, Email: {email}, Username: {username}, Name: {first_name} {last_name}")
    except Exception as e:
        print(f"❌ 查询用户失败: {e}")
    
    # 查找 test26 用户的 Token
    print("\n" + "=" * 60)
    print("🔑 查找 test26 用户的 API Token")
    print("=" * 60)
    
    try:
        # 先找到用户 ID
        cursor.execute("""
            SELECT id, email FROM htx_user 
            WHERE email LIKE '%test26%' OR username LIKE '%test26%'
        """)
        target_users = cursor.fetchall()
        
        if not target_users:
            print("❌ 未找到 test26 用户")
            print("\n尝试查找所有包含 'test' 的用户:")
            cursor.execute("""
                SELECT id, email, username FROM htx_user 
                WHERE email LIKE '%test%' OR username LIKE '%test%'
            """)
            for row in cursor.fetchall():
                print(f"  ID: {row[0]}, Email: {row[1]}, Username: {row[2]}")
        else:
            for user_id, email in target_users:
                print(f"\n找到用户: ID={user_id}, Email={email}")
                
                # 查找 Token（authtoken_token 表）
                cursor.execute("""
                    SELECT key, created FROM authtoken_token 
                    WHERE user_id = ?
                """, (user_id,))
                tokens = cursor.fetchall()
                
                if tokens:
                    for token, created in tokens:
                        print(f"\n✅ API Token 找到!")
                        print(f"   Token: {token}")
                        print(f"   创建时间: {created}")
                        print(f"\n📝 使用方法:")
                        print(f"   export LABEL_STUDIO_API_KEY={token}")
                else:
                    print(f"   ⚠️ 该用户没有 API Token")
                    print(f"   需要登录 Label Studio 后在 Account & Settings 中生成")
    
    except Exception as e:
        print(f"❌ 查询 Token 失败: {e}")
        import traceback
        traceback.print_exc()
    
    # 也查一下是否有管理员 Token
    print("\n" + "=" * 60)
    print("🔑 所有可用的 API Token")
    print("=" * 60)
    
    try:
        cursor.execute("""
            SELECT t.key, t.created, u.email, u.username
            FROM authtoken_token t
            JOIN htx_user u ON t.user_id = u.id
            ORDER BY t.created DESC
        """)
        all_tokens = cursor.fetchall()
        
        if all_tokens:
            for token, created, email, username in all_tokens:
                print(f"\n  用户: {email} ({username})")
                print(f"  Token: {token}")
                print(f"  创建: {created}")
        else:
            print("  ❌ 数据库中没有任何 API Token")
            print("  需要用户登录 Label Studio 后手动生成")
    
    except Exception as e:
        print(f"❌ 查询失败: {e}")
    
    conn.close()
    
    print("\n" + "=" * 60)
    print("💡 如果没有找到 Token，请:")
    print("   1. 登录 Label Studio (http://localhost:8080)")
    print("   2. 点击右上角头像 -> Account & Settings")
    print("   3. 找到 Access Token 并复制")
    print("=" * 60)


if __name__ == "__main__":
    find_api_key()