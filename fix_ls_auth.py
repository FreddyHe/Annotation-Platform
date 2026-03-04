
import sys
import os

# ====================================================
# 1. 【关键】强制添加库路径，解决 "No module named core"
# ====================================================
#这是你conda环境的真实路径
site_packages = "/root/miniconda3/envs/web_annotation/lib/python3.10/site-packages"
if site_packages not in sys.path:
    sys.path.insert(0, site_packages)

print(f"🔧 已修正 Python 路径，正在加载 Django...")

# 2. 设置 Django 环境配置
# 注意：这里必须用全路径 'label_studio.core.settings.label_studio'
os.environ['DJANGO_SETTINGS_MODULE'] = 'label_studio.core.settings.label_studio'

try:
    import django
    django.setup()
    print("✅ Django 环境加载成功！")
except Exception as e:
    print(f"❌ 环境加载崩溃: {e}")
    sys.exit(1)

# 3. 开始修复数据
from django.contrib.auth import get_user_model
from label_studio.organizations.models import Organization, OrganizationMember

def fix_user_org():
    User = get_user_model()
    target_email = "test6@auto-annotation.local"
    
    print(f"🔍 正在检查用户: {target_email}")
    
    try:
        user = User.objects.get(email=target_email)
        print(f"✅ 找到用户 (ID: {user.id})")
        
        # 获取第一个组织作为默认组织
        org = Organization.objects.first()
        if not org:
            print("❌ 数据库里居然没有组织？尝试创建一个...")
            org = Organization.objects.create(title="Default Organization", created_by=user)
        
        print(f"🏢 目标组织: {org.title} (ID: {org.id})")
        
        # --- 核心修复逻辑 ---
        
        # 1. 确保在成员表中
        if not OrganizationMember.objects.filter(user=user, organization=org).exists():
            print("🛠️ 用户不在组织成员表中，正在添加...")
            OrganizationMember.objects.create(user=user, organization=org)
        else:
            print("ℹ️ 用户已经是成员了。")
            
        # 2. 确保激活组织字段正确
        if user.active_organization_id != org.id:
            print("🛠️ 修复用户的 'active_organization' 指针...")
            user.active_organization = org
            user.save()
        
        print("\n" + "="*50)
        print("🎉 修复完成！")
        print("👉 现在请回到浏览器，刷新页面或重新登录，500错误应该消失了。")
        print("="*50 + "\n")
        
    except User.DoesNotExist:
        print(f"❌ 找不到用户 {target_email}，请确认账号是否创建成功。")
    except Exception as e:
        print(f"❌ 发生未知错误: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    fix_user_org()
