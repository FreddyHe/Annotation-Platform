# Yuhao Git 协作开发说明

这份文档给 B 使用。目标是让 B 在同一个 AutoDL/Docker 开发环境里独立开发，然后通过 PR 把代码交给 A 测试和合并。

当前主仓库：

```text
git@github.com:FreddyHe/Annotation-Platform.git
```

推荐目录：

```text
/root/autodl-fs/Annotation-Platform          A 的主项目目录，维护 main
/root/autodl-fs/Annotation-Platform-yuhao    B 的开发目录
/root/autodl-fs/Annotation-Platform-prtest   A 测试 PR 的目录
```

## 1. 为什么不能直接在 A 的目录里改

`/root/autodl-fs/Annotation-Platform` 是 A 的主项目目录。这个目录应该尽量保持和 `origin/main` 对齐。

如果 B 直接在这个目录里改代码，会有几个问题：

1. A 和 B 的改动混在一起，不知道是谁改了什么。
2. A 正在测试时，B 可能又改了文件，结果不稳定。
3. main 分支会变脏，后续不好回退。
4. 运行数据、数据库、上传文件可能互相污染。

所以 B 应该在自己的目录开发：

```text
/root/autodl-fs/Annotation-Platform-yuhao
```

## 2. 推荐方式：B fork 仓库再提 PR

推荐流程：

```text
B fork FreddyHe/Annotation-Platform
↓
B clone 自己的 fork 到 Annotation-Platform-yuhao
↓
B 新建 feat 分支开发
↓
B push 到自己的 fork
↓
B 在 GitHub 上向 FreddyHe/Annotation-Platform:main 提 PR
↓
A 在 Annotation-Platform-prtest 拉 PR 测试
↓
测试通过后 A 合并 PR
```

这样做的好处是：B 不需要直接写 main，A 可以先测试再合并。

## 3. B 第一次 clone 项目

先进入 AutoDL 数据目录：

```bash
cd /root/autodl-fs
```

如果 B 已经 fork 了仓库，例如 fork 到：

```text
git@github.com:Yuhao/Annotation-Platform.git
```

那么执行：

```bash
git clone git@github.com:Yuhao/Annotation-Platform.git Annotation-Platform-yuhao
cd Annotation-Platform-yuhao
```

然后把 A 的主仓库添加为 upstream：

```bash
git remote add upstream git@github.com:FreddyHe/Annotation-Platform.git
git remote -v
```

正常应该看到：

```text
origin    git@github.com:Yuhao/Annotation-Platform.git
upstream  git@github.com:FreddyHe/Annotation-Platform.git
```

## 4. 配置 B 自己的 Git 用户

如果 A 和 B 都在同一个容器里，很可能都是 Linux 的 `root` 用户。不要用全局配置。

B 应该在自己的项目目录里配置局部 Git 身份：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao

git config user.name "Yuhao"
git config user.email "yuhao@example.com"
```

检查配置：

```bash
git config --local --list
```

不要执行：

```bash
git config --global user.name ...
git config --global user.email ...
```

因为这会影响同一个 root 用户下的其他仓库。

## 5. B 每次开始新任务前同步 main

每次开始新任务前，先把自己的 main 同步到 A 的最新 main：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao

git checkout main
git fetch upstream
git reset --hard upstream/main
git push origin main --force-with-lease
```

然后新建任务分支：

```bash
git checkout -b feat/yuhao-your-task-name
```

分支命名建议：

```text
feat/yuhao-add-upload-progress
fix/yuhao-training-loss-log
docs/yuhao-update-readme
```

## 6. B 开发时怎么提交

先查看改动：

```bash
git status
git diff
```

只提交源码、文档、配置模板，不提交运行数据。

提交：

```bash
git add .
git status
git commit -m "feat: add upload progress display"
```

推送到 B 自己的 fork：

```bash
git push -u origin feat/yuhao-your-task-name
```

然后去 GitHub 网页提交 PR：

```text
Yuhao/Annotation-Platform:feat/yuhao-your-task-name
    ->
FreddyHe/Annotation-Platform:main
```

## 7. 不要提交这些文件

这些文件不应该进 Git：

```text
frontend-vue/node_modules/
frontend-vue/dist/
frontend-vue/.vite/
backend-springboot/target/
backend-springboot/data/
logs/
training_runs/
uploads/
debug-backups/
debug-image/
*.log
*.pt
*.pth
*.onnx
*.engine
*.ckpt
*.safetensors
*.db
*.sqlite3
.env
application-local.yml
application-yuhao.yml
application-prtest.yml
```

原因：

1. `node_modules` 可以通过 `npm install` 重新生成。
2. `target` 可以通过 Maven 重新生成。
3. 数据库、上传文件、训练输出是本地运行数据，不是代码。
4. 模型权重和训练产物太大，不适合放普通 Git。
5. `.env` 和本地 yml 可能包含密码、token、路径。

## 8. B 如何安装和启动前端

进入前端目录：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao/frontend-vue
```

安装依赖：

```bash
npm install
```

启动前端，使用和 A 不冲突的端口：

```bash
npm run dev -- --host 0.0.0.0 --port 5174
```

A 主项目建议用：

```text
5173
```

B 开发建议用：

```text
5174
```

PR 测试建议用：

```text
5175
```

## 9. B 如何启动后端

进入后端目录：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao/backend-springboot
```

建议 B 使用自己的端口，例如 `8081`：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
```

如果项目使用 wrapper，则可以用：

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
```

如果没有 `mvn` 或依赖环境不完整，先问 A，不要随便改仓库里的配置文件。

## 10. B 不要和 A 共用数据库/上传目录

当前项目的 `application.yml` 里有本地 H2 数据库路径和上传路径。多人同时开发时，最好分开：

```text
A:
server.port=8080
数据库目录：backend-springboot/data/

B:
server.port=8081
数据库目录：backend-springboot/data-yuhao/

PR 测试:
server.port=8082
数据库目录：backend-springboot/data-prtest/
```

更规范的做法是每个人建自己的本地配置文件，例如：

```text
application-yuhao.yml
```

但这个文件不要提交到 Git。

## 11. B 修改代码前先确认任务边界

每个 PR 尽量只做一件事。

好的 PR：

```text
修复训练 loss 日志显示
```

不好的 PR：

```text
同时改训练、边端模拟、数据库、前端样式、LS 导入
```

PR 越小，A 越容易测试，也越容易合并。

## 12. B 开发中同步 A 的最新 main

如果 B 开发了一段时间，A 的 main 又更新了，B 可以在自己的分支上同步：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao

git fetch upstream
git rebase upstream/main
```

如果没有冲突，直接继续开发。

如果有冲突，Git 会提示哪些文件冲突。解决冲突后：

```bash
git add .
git rebase --continue
```

最后推送：

```bash
git push --force-with-lease
```

注意：不要用 `git push --force`，优先用 `--force-with-lease`。

## 13. A 如何测试 B 的 PR

A 使用单独测试目录：

```bash
cd /root/autodl-fs
git clone git@github.com:FreddyHe/Annotation-Platform.git Annotation-Platform-prtest
cd Annotation-Platform-prtest
```

如果 B 的分支叫：

```text
feat/yuhao-your-task-name
```

A 可以拉 B 的分支：

```bash
git fetch git@github.com:Yuhao/Annotation-Platform.git feat/yuhao-your-task-name:pr/yuhao-your-task-name
git checkout pr/yuhao-your-task-name
```

然后启动 PR 测试端口：

```bash
cd frontend-vue
npm install
npm run dev -- --host 0.0.0.0 --port 5175
```

后端：

```bash
cd ../backend-springboot
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8082"
```

测试没问题后，A 在 GitHub 网页上合并 PR。

建议合并方式：

```text
Squash and merge
```

## 14. A 合并后，B 如何更新

A 合并 PR 后，B 更新自己的 main：

```bash
cd /root/autodl-fs/Annotation-Platform-yuhao

git checkout main
git fetch upstream
git reset --hard upstream/main
git push origin main --force-with-lease
```

然后 B 再开下一个新分支：

```bash
git checkout -b feat/yuhao-next-task
```

## 15. 最重要的规则

1. 不要直接 push main。
2. 每个任务一个分支。
3. B 每次新任务前先同步 A 的 main。
4. 不要提交 node_modules、dist、target、数据库、模型、上传文件。
5. 本地端口、数据库、上传目录要和 A 分开。
6. PR 尽量小，方便 A 测试。
7. 合并前必须在 `Annotation-Platform-prtest` 测试。
8. 配置里如果有密码、token、本地路径，优先放本地配置，不要提交。
