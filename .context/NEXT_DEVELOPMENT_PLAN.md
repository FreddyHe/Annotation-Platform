# 下一轮开发计划

## 当前入口

- `incremental_multiclass_closed_loop_proposal.md`

## 主题

下一轮优先围绕“增量闭环与新增类别机制”继续开发，把项目从静态数据集视角推进到带版本的数据闭环项目。

核心方向：

- 数据与标注 tab 联动初始数据、可信回流、LOW_B 待审、已审增量数据和类别版本。
- 结果查看 tab 升级为统一结果浏览器，支持按训练记录、部署、轮次、来源、类别、审核状态筛选。
- 新增 `LabelSchemaVersion`、`DatasetVersion` 等版本化概念，明确新增类别后的标注、训练和部署语义。
- 新增类别时创建类别扩展标注任务，避免历史图片在未补标时被错误当成新类别负样本。
- 模型训练记录、边端部署、回流数据点都绑定类别版本和数据集快照。

开始下一轮开发前，先阅读根目录的 `incremental_multiclass_closed_loop_proposal.md`。
