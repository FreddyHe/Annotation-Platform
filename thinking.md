可以，这个思路是对的。你要做的本质是：

```text
A 的主项目目录：
/root/autodl-fs/Annotation-Platform
        ↓
GitHub/Gitee/GitLab 主仓库 main 分支
        ↑
B 的开发目录：
/root/autodl-fs/Annotation-Platform-yuhao
        ↓
B 的 feature 分支 / fork
        ↓
提交 PR
        ↓
A 拉下来测试
        ↓
没问题再合并到 main
```

核心原则是：**代码目录隔离，Git 分支隔离，运行端口隔离，数据库/上传目录隔离。**

---

## 一、你的方案是否可行？

可行，而且推荐这样做。

但是要注意一点：**Docker 容器只是开发环境，Git 管的是代码。**

也就是说，B 不是“拉你的 Docker 容器”，而是在同一个容器环境里，或者另一个相同环境里，重新 clone 一份代码。

你现在有：

```bash
/root/autodl-fs/Annotation-Platform
```

可以让 B 使用：

```bash
/root/autodl-fs/Annotation-Platform-yuhao
```

这两个目录互不影响。B 改他的目录，你改你的目录，最后通过 Git 合并。

---

# 二、推荐工作流：主仓库 + B 的分支/PR

有两种模式。

## 方案 A：B 作为协作者，直接推分支到你的仓库

适合你信任 B，并且给了他仓库写权限。

```text
A/Annotation-Platform
    main
    feat/yuhao-xxx
```

B 不直接改 main，只推自己的分支，然后开 PR。

优点：简单。
缺点：B 有仓库写权限，管理稍弱。

---

## 方案 B：B fork 你的仓库，然后从 fork 提 PR

更标准，更安全。

```text
A/Annotation-Platform       主仓库
B/Annotation-Platform       B fork 出来的仓库
```

B 在自己的 fork 里开发，然后向你的主仓库提交 PR。

优点：权限隔离更清楚。
缺点：流程稍微多一步。

如果是正式多人协作，我更推荐 **方案 B：fork + PR**。

---

# 三、A 先把当前项目放到 Git 仓库

在你的主项目目录下操作：

```bash
cd /root/autodl-fs/Annotation-Platform
```

先看当前是不是已经有 Git：

```bash
git status
```

如果提示不是 Git 仓库，就初始化：

```bash
git init
```

然后先配置 `.gitignore`，避免把环境文件、构建产物、数据集、模型权重提交进去。

例如 Vue + Spring Boot 项目可以这样：

```bash
cat > .gitignore <<'EOF'
# OS / IDE
.DS_Store
.idea/
.vscode/
*.iml

# Logs
*.log
logs/

# Frontend
node_modules/
dist/
.vite/

# Backend
target/
*.class

# Env / secrets
.env
.env.*
!.env.example
application-local.yml
application-dev-local.yml
application-prod.yml

# Runtime data
uploads/
data/
tmp/
temp/

# Model / dataset artifacts
runs/
weights/
models/
datasets/
*.pt
*.pth
*.onnx
*.engine
*.ckpt

# Docker local override
docker-compose.override.yml
EOF
```

然后提交初始版本：

```bash
git add .
git commit -m "chore: initial project import"
```

去 GitHub/Gitee/GitLab 新建一个空仓库，例如：

```text
github.com/你的账号/Annotation-Platform
```

然后添加远程仓库：

```bash
git branch -M main
git remote add origin git@github.com:你的账号/Annotation-Platform.git
git push -u origin main
```

如果你用 HTTPS：

```bash
git remote add origin https://github.com/你的账号/Annotation-Platform.git
git push -u origin main
```

---

# 四、B 在另一个目录 clone 项目

## 如果 B 是协作者，直接 clone 你的主仓库

```bash
cd /root/autodl-fs
git clone git@github.com:你的账号/Annotation-Platform.git Annotation-Platform-yuhao
cd Annotation-Platform-yuhao
```

配置 B 自己的 Git 身份：

```bash
git config user.name "Yuhao"
git config user.email "yuhao@example.com"
```

注意这里建议用 **局部配置**，不要用 `--global`，因为你们可能在同一个容器、同一个 root 用户下操作。

然后 B 新建自己的开发分支：

```bash
git checkout -b feat/yuhao-module-refactor
```

B 开发完成后：

```bash
git status
git add .
git commit -m "feat: improve xxx module"
git push -u origin feat/yuhao-module-refactor
```

然后去网页上从：

```text
feat/yuhao-module-refactor
```

向：

```text
main
```

提交 PR。

---

## 如果 B 是 fork 模式

B 先在网页上 fork 你的仓库。

然后在服务器里 clone 他自己的 fork：

```bash
cd /root/autodl-fs
git clone git@github.com:Yuhao/Annotation-Platform.git Annotation-Platform-yuhao
cd Annotation-Platform-yuhao
```

添加你的主仓库作为 upstream：

```bash
git remote add upstream git@github.com:你的账号/Annotation-Platform.git
```

查看远程仓库：

```bash
git remote -v
```

应该看到类似：

```text
origin    git@github.com:Yuhao/Annotation-Platform.git
upstream  git@github.com:你的账号/Annotation-Platform.git
```

B 新建分支：

```bash
git checkout -b feat/yuhao-module-refactor
```

开发完成后：

```bash
git add .
git commit -m "feat: improve xxx module"
git push -u origin feat/yuhao-module-refactor
```

然后 B 在网页上提交 PR：

```text
Yuhao/Annotation-Platform:feat/yuhao-module-refactor
    →
你的账号/Annotation-Platform:main
```

---

# 五、A 怎么拉 B 的 PR 到本地测试？

你有两种测试方式。

---

## 方式 1：直接在主项目目录里切换到 PR 分支测试

适合你能接受当前目录临时切分支。

```bash
cd /root/autodl-fs/Annotation-Platform
git status
```

确保当前没有未提交修改。如果有，先提交或暂存：

```bash
git stash
```

如果 B 是同仓库分支：

```bash
git fetch origin feat/yuhao-module-refactor
git checkout -b test/yuhao-module-refactor origin/feat/yuhao-module-refactor
```

如果 B 是 fork 仓库分支：

```bash
git fetch git@github.com:Yuhao/Annotation-Platform.git feat/yuhao-module-refactor:pr/yuhao-module-refactor
git checkout pr/yuhao-module-refactor
```

然后测试：

```bash
# 前端
cd frontend
npm install
npm run dev

# 后端
cd ../backend
./mvnw spring-boot:run
```

测试没问题后，可以在网页上点 Merge PR。

合并后，你本地 main 更新：

```bash
cd /root/autodl-fs/Annotation-Platform
git checkout main
git pull --ff-only origin main
```

---

## 方式 2：单独建一个 PR 测试目录

这个更安全，我更推荐。

```bash
cd /root/autodl-fs
git clone git@github.com:你的账号/Annotation-Platform.git Annotation-Platform-prtest
cd Annotation-Platform-prtest
```

如果 B 是 fork：

```bash
git fetch git@github.com:Yuhao/Annotation-Platform.git feat/yuhao-module-refactor:pr/yuhao-module-refactor
git checkout pr/yuhao-module-refactor
```

然后在这个目录里跑测试。

好处是不会污染你的主目录：

```text
/root/autodl-fs/Annotation-Platform          你的主项目
/root/autodl-fs/Annotation-Platform-yuhao    B 的开发项目
/root/autodl-fs/Annotation-Platform-prtest   你专门测试 PR 的项目
```

---

# 六、重点：两个目录不能同时占用同一组端口

如果你的前端默认是：

```text
5173
```

后端默认是：

```text
8080
```

那么 A 和 B 不能同时都启动：

```text
A 前端 5173
B 前端 5173
A 后端 8080
B 后端 8080
```

会冲突。

你应该这样分配：

```text
A:
前端 5173
后端 8080

B:
前端 5174
后端 8081
```

---

## 前端 Vue 改端口

B 启动时可以这样：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao/frontend
npm run dev -- --host 0.0.0.0 --port 5174
```

如果是 Vite 项目，也可以在 `vite.config.ts` 里配置，不过我更建议用命令行参数或 `.env.local`，避免提交个人端口配置。

---

## 后端 Spring Boot 改端口

B 可以这样启动：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao/backend
./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
```

或者建一个本地 profile，比如：

```bash
application-yuhao.yml
```

内容示例：

```yaml
server:
  port: 8081

spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/annotation_platform_yuhao
    username: root
    password: your_password
```

然后启动：

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=yuhao
```

注意：`application-yuhao.yml` 如果包含密码，不要提交。

可以把它加入 `.gitignore`。

---

# 七、重点：A 和 B 不要共用同一个数据库

这点非常重要。

如果 A 和 B 都连：

```text
annotation_platform
```

B 改表结构、清数据、跑测试，可能会影响你的主项目。

建议：

```text
A 使用：
annotation_platform

B 使用：
annotation_platform_yuhao

PR 测试使用：
annotation_platform_prtest
```

例如 MySQL：

```sql
CREATE DATABASE annotation_platform_yuhao DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE annotation_platform_prtest DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

如果你是 SQLite，更要注意不要共用同一个 `.db` 文件。

---

# 八、如果项目用 docker-compose 启动，要给 B 单独的 compose 项目名

如果你的项目本身是用 `docker compose` 启动的，那么 A 和 B 也会有容器名、网络名、数据库名冲突。

A 可以这样：

```bash
cd /root/autodl-fs/Annotation-Platform
COMPOSE_PROJECT_NAME=annotation_a docker compose up -d
```

B 可以这样：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao
COMPOSE_PROJECT_NAME=annotation_yuhao docker compose up -d
```

如果端口也冲突，需要给 B 做一个本地覆盖文件，比如：

```bash
docker-compose.yuhao.yml
```

示意：

```yaml
services:
  frontend:
    ports:
      - "5174:5173"

  backend:
    ports:
      - "8081:8080"

  mysql:
    ports:
      - "3307:3306"
```

B 启动：

```bash
COMPOSE_PROJECT_NAME=annotation_yuhao docker compose -f docker-compose.yml -f docker-compose.yuhao.yml up -d
```

这个文件如果只是 B 的本地配置，可以不提交。

---

# 九、A 合并 PR 的标准流程

B 提交 PR 后，A 应该这样做：

## 1. 看 PR 改了哪些文件

网页上查看 Files changed。

重点看：

```text
前端页面
后端 Controller / Service / Entity
数据库表结构
配置文件
接口路径
package.json / pom.xml
.env / yml
```

尤其警惕：

```text
.env
application-prod.yml
数据库密码
node_modules
target
dist
模型权重
数据集
上传图片
```

这些不应该进仓库。

---

## 2. 拉到本地测试

比如：

```bash
cd /root/autodl-fs/Annotation-Platform-prtest
git fetch git@github.com:Yuhao/Annotation-Platform.git feat/yuhao-module-refactor:pr/yuhao-module-refactor
git checkout pr/yuhao-module-refactor
```

跑前端：

```bash
cd frontend
npm install
npm run dev -- --host 0.0.0.0 --port 5175
```

跑后端：

```bash
cd ../backend
./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8082"
```

---

## 3. 测试没问题后合并

网页上建议选择：

```text
Squash and merge
```

这样可以把 B 的多个零散 commit 压成一个干净的 commit。

合并后你的主目录更新：

```bash
cd /root/autodl-fs/Annotation-Platform
git checkout main
git pull --ff-only origin main
```

---

# 十、B 每次开始新任务前，都应该同步你的 main

B 不要长期基于很旧的代码开发。

如果是 fork 模式，B 每次开始前：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao

git checkout main
git fetch upstream
git reset --hard upstream/main
git push origin main --force-with-lease
```

然后新建任务分支：

```bash
git checkout -b feat/new-task
```

如果他已经在开发分支上，开发到一半需要同步你的 main：

```bash
git fetch upstream
git rebase upstream/main
```

如果有冲突，解决后：

```bash
git add .
git rebase --continue
```

然后推送：

```bash
git push --force-with-lease
```

注意：`--force-with-lease` 比 `--force` 安全。

---

# 十一、同一个容器里用两个 Git 账号的注意事项

你说“让 B 用他的 Git 账号拉下来”，这里要小心。

如果你们都在同一个 Linux 用户下，比如都是 `root`，那么：

```bash
git config --global user.name
git config --global user.email
```

会互相影响。

所以建议每个仓库单独配置：

A 的目录：

```bash
cd /root/autodl-fs/Annotation-Platform
git config user.name "A"
git config user.email "a@example.com"
```

B 的目录：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao
git config user.name "Yuhao"
git config user.email "yuhao@example.com"
```

不要加 `--global`。

---

## SSH Key 也要注意

如果 A 和 B 都用 GitHub SSH，而且都在同一个 root 用户下，可能会混用同一个 key。

更稳的方式是配置 SSH alias。

例如：

```bash
vim ~/.ssh/config
```

写入：

```sshconfig
Host github-a
  HostName github.com
  User git
  IdentityFile ~/.ssh/id_ed25519_a

Host github-yuhao
  HostName github.com
  User git
  IdentityFile ~/.ssh/id_ed25519_yuhao
```

A 的远程地址用：

```bash
git remote set-url origin git@github-a:你的账号/Annotation-Platform.git
```

B 的远程地址用：

```bash
git remote set-url origin git@github-yuhao:Yuhao/Annotation-Platform.git
```

不过更实际的建议是：**不要让 B 的私人 SSH Key 长期放在你的服务器 root 账号里。**

如果 B 是人类协作者，更推荐：

```text
B 在自己的机器/容器上开发
↓
push 到他自己的 fork
↓
你在 AutoDL 上拉 PR 测试
```

这样权限更干净。

---

# 十二、你这个项目的推荐目录结构

我建议你最后形成这种结构：

```bash
/root/autodl-fs/
├── Annotation-Platform/              # A 的主项目，跟 origin/main 对齐
├── Annotation-Platform-yuhao/        # B 的开发目录
├── Annotation-Platform-prtest/       # A 专门测试 PR 的目录
└── Annotation-Platform-backup/       # 可选，旧版本备份
```

服务端口建议：

```text
A 主项目：
frontend: 5173
backend: 8080
database: annotation_platform

B 开发项目：
frontend: 5174
backend: 8081
database: annotation_platform_yuhao

PR 测试项目：
frontend: 5175
backend: 8082
database: annotation_platform_prtest
```

---

# 十三、给 B 的标准开发命令模板

你可以直接发给 B：

```bash
cd /root/autodl-fs

git clone git@github.com:Yuhao/Annotation-Platform.git Annotation-Platform-yuhao
cd Annotation-Platform-yuhao

git remote add upstream git@github.com:你的账号/Annotation-Platform.git

git config user.name "Yuhao"
git config user.email "yuhao@example.com"

git checkout main
git fetch upstream
git reset --hard upstream/main

git checkout -b feat/your-task-name
```

开发完成后：

```bash
git status
git add .
git commit -m "feat: your task description"
git push -u origin feat/your-task-name
```

然后去网页提交 PR。

---

# 十四、你自己的测试命令模板

```bash
cd /root/autodl-fs

rm -rf Annotation-Platform-prtest
git clone git@github.com:你的账号/Annotation-Platform.git Annotation-Platform-prtest
cd Annotation-Platform-prtest

git fetch git@github.com:Yuhao/Annotation-Platform.git feat/your-task-name:pr/your-task-name
git checkout pr/your-task-name
```

然后分别启动前后端测试。

前端：

```bash
cd frontend
npm install
npm run dev -- --host 0.0.0.0 --port 5175
```

后端：

```bash
cd ../backend
./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8082"
```

测试通过后，在网页合并 PR。

合并后：

```bash
cd /root/autodl-fs/Annotation-Platform
git checkout main
git pull --ff-only origin main
```

---

# 十五、最重要的协作规则

你们应该约定这几条：

1. **任何人都不要直接 push main。**
2. **每个任务一个分支。**
3. **B 每次开发前先同步 main。**
4. **PR 尽量小，不要一次改一大坨。**
5. **数据库结构变化必须写迁移脚本或说明。**
6. **不要提交 `.env`、密码、数据集、模型权重、上传文件。**
7. **A/B 同时运行项目时，端口、数据库、上传目录必须分开。**
8. **合并前必须在独立目录测试。**

你这个场景最推荐的最终模式是：

```text
B fork 仓库
↓
B 在 /root/autodl-fs/Annotation-Platform-yuhao 开发
↓
B 推送 feat 分支
↓
B 提 PR
↓
A 在 /root/autodl-fs/Annotation-Platform-prtest 拉 PR 测试
↓
测试通过后网页 Squash and merge
↓
A 在 /root/autodl-fs/Annotation-Platform 执行 git pull
```

这样既能利用你当前 Docker/AutoDL 里的依赖环境，又不会让两个人互相污染代码、端口、数据库和 Git 历史。

---

# 十六、结合当前仓库的实际修正

当前主项目已经是 Git 仓库：

```text
主项目目录：/root/autodl-fs/Annotation-Platform
当前分支：main
远端仓库：git@github.com:FreddyHe/Annotation-Platform.git
```

你的整体思路是正确的，但我建议做两个具体修正。

第一，B 不应该直接长期在你的主目录里开发。B 可以在同一台 AutoDL/同一个 Docker 环境里开发，但代码目录必须独立：

```text
/root/autodl-fs/Annotation-Platform          A 的主目录，只维护 main
/root/autodl-fs/Annotation-Platform-yuhao    B 的开发目录，只做 feature 分支
/root/autodl-fs/Annotation-Platform-prtest   A 的 PR 测试目录，只拉 PR 测试
```

第二，不建议把环境、依赖、数据库、模型、上传数据提交到 Git。Git 应该只管理：

```text
前端源码
后端源码
算法服务源码
脚本
迁移 SQL
文档
package.json / package-lock.json
pom.xml
requirements.txt
必要的示例配置
```

不应该进入 Git 的内容包括：

```text
frontend-vue/node_modules/
frontend-vue/dist/
backend-springboot/target/
backend-springboot/data/
logs/
training_runs/
uploads/
debug-backups/
debug-image/
*.pt / *.pth / *.onnx / *.safetensors
.env
本地数据库文件
```

这次检查发现 `frontend-vue/node_modules` 之前已经被 Git 跟踪了，数量超过一万一千个文件。即使 `.gitignore` 里写了 `node_modules/`，对已经被跟踪的文件也不会自动生效。因此需要执行：

```bash
git rm -r --cached frontend-vue/node_modules frontend-vue/.vite frontend-vue/dist
```

注意：这里的 `--cached` 只是不再让 Git 管这些文件，不会删除本地文件。

同理，日志、debug 备份、构建产物也应该从 Git 索引移除：

```bash
git rm --cached backend-springboot/backend.log frontend-vue/frontend.log project_dump.txt
git rm -r --cached debug-backups debug-image
```

后续 B clone 项目后，需要自己安装依赖：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao/frontend-vue
npm install
```

这才是正常的前端协作方式。

## 当前推荐采用的协作策略

我建议采用“B fork + PR”的方式：

```text
A 主仓库：FreddyHe/Annotation-Platform
B fork：Yuhao/Annotation-Platform
B 开发分支：feat/xxx
PR 方向：Yuhao/Annotation-Platform:feat/xxx -> FreddyHe/Annotation-Platform:main
```

原因：

1. B 不需要 main 写权限，权限更安全。
2. B 的提交不会直接污染你的 main。
3. 你可以在 `Annotation-Platform-prtest` 中单独拉 PR 测试。
4. 测试通过后再网页 Squash and merge，主线历史更干净。

如果 B 只是短期协作，也可以给他主仓库协作者权限，让他直接推 `feat/yuhao-xxx` 分支到你的仓库。但即使这样，也不要让 B 直接 push main。

## 当前 push 前必须注意

当前 `application.yml` 里有开发用的 Label Studio token 和 JWT secret。它们看起来是本地开发配置，但从长期协作角度看，更好的方式是：

```text
application.yml            保留默认无敏感值配置
application-local.yml      本地真实 token、路径、端口，不提交
application-yuhao.yml      B 的本地配置，不提交
application-prtest.yml     PR 测试配置，不提交
```

这一步可以后续再做。当前最紧急的是先把 `node_modules`、日志、debug 备份、dist 从 Git 里清出去，避免继续推大文件和运行产物。
