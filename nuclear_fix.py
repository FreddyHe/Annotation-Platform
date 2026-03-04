
import sys
import os

# ==============================================================================
# 1. 【关键修复】构建并添加路径
# ==============================================================================
# 基础包路径
SITE_PACKAGES = "/root/miniconda3/envs/web_annotation/lib/python3.10/site-packages"
# Label Studio 内部根目录 (解决 "No module named core" 的关键)
LS_INNER_ROOT = os.path.join(SITE_PACKAGES, "label_studio")

# 将两个路径都加入到 Python 搜索列表的最前面
if LS_INNER_ROOT not in sys.path:
    sys.path.insert(0, LS_INNER_ROOT)
if SITE_PACKAGES not in sys.path:
    sys.path.insert(0, SITE_PACKAGES)

print(f"🔧 路径修复完成:")
print(f"   1. {SITE_PACKAGES}")
print(f"   2. {LS_INNER_ROOT}")

# ==============================================================================
# 2. 配置 Django 环境
# ==============================================================================
# 因为我们把 label_studio 目录加入了 path，现在可以直接用 core.settings...
os.environ['DJANGO_SETTINGS_MODULE'] = 'core.settings.label_studio'
os.environ['LABEL_STUDIO_DATA_DIR'] = '/root/label_studio_data_fast'

try:
    import django
    django.setup()
    print("✅ Django 环境加载成功！")
except Exception as e:
    print(f"❌ 环境加载依然失败: {e}")
    # 尝试备用配置名
    try:
        print("🔄 尝试使用完整路径配置名重试...")
        os.environ['DJANGO_SETTINGS_MODULE'] = 'label_studio.core.settings.label_studio'
        import django
        django.setup()
        print("✅ 重试成功！")
    except Exception as e2:
        print(f"❌ 彻底失败: {e2}")
        sys.exit(1)

# ==============================================================================
# 3. 修复数据库数据
# ==============================================================================
from django.contrib.auth import get_user_model
from organizations.models import Organization, OrganizationMember

def fix_db_final():
    User = get_user_model()
    target_email = "test6@auto-annotation.local"
    
    print(f"\n🔍 正在查找目标用户: {target_email}")
    
    try:
        user = User.objects.get(email=target_email)
        print(f"✅ 找到用户 ID: {user.id}")
        
        # 1. 确保有组织
        org = Organization.objects.first()
        if not org:
            print("⚠️ 没找到组织，创建默认组织...")
            org = Organization.objects.create(title='Default Organization', created_by=user)
        print(f"🏢 目标组织: {org.title} (ID: {org.id})")
        
        # 2. 确保是成员
        if not OrganizationMember.objects.filter(user=user, organization=org).exists():
            OrganizationMember.objects.create(user=user, organization=org)
            print("🛠️ 已添加为组织成员")
        else:
            print("ℹ️ 用户已是成员")
            
        # 3. 【核心】强制修复指针 (解决 500 错误)
        if user.active_organization_id != org.id:
            user.active_organization = org
            user.save()
            print("✅ 成功修复 'active_organization' 指针")
        else:
            print("ℹ️ 指针正常")
            
        print("\n🎉🎉🎉 修复大成功！请去浏览器登录！")
        
    except User.DoesNotExist:
        print(f"❌ 数据库里没有用户 {target_email}，可能之前创建失败了。")
    except Exception as e:
        print(f"❌ 脚本运行出错: {e}")

if __name__ == "__main__":
    fix_db_final()
