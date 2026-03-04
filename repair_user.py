from django.contrib.auth import get_user_model
from django.apps import apps

def run_fix():
    print("🚀 开始修复流程...")
    
    # 1. 动态获取模型 (避开 import 路径冲突)
    User = get_user_model()
    Organization = apps.get_model('organizations', 'Organization')
    OrganizationMember = apps.get_model('organizations', 'OrganizationMember')
    
    target_email = 'test6@auto-annotation.local'
    
    try:
        # 2. 查找用户
        user = User.objects.get(email=target_email)
        print(f"✅ 找到用户: {user.email} (ID: {user.id})")
        
        # 3. 查找或创建组织
        org = Organization.objects.first()
        if not org:
            print("⚠️ 未找到组织，正在创建默认组织...")
            org = Organization.objects.create(title='Default Organization', created_by=user)
        print(f"🏢 使用组织: {org.title} (ID: {org.id})")
        
        # 4. 建立成员关系
        # 使用 get_or_create 避免重复添加报错
        member, created = OrganizationMember.objects.get_or_create(user=user, organization=org)
        if created:
            print("🛠️ 已创建组织成员关联")
        else:
            print("ℹ️ 用户已经是组织成员")
            
        # 5. 强制修复当前激活的组织指针
        user.active_organization = org
        user.save()
        print("✅ 用户 active_organization 指针已修复")
        
        print("\n🎉🎉🎉 全部完成！请去浏览器登录！")
        
    except User.DoesNotExist:
        print(f"❌ 错误: 数据库中找不到用户 {target_email}")
    except Exception as e:
        print(f"❌ 发生异常: {e}")

# 执行逻辑
run_fix()