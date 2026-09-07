/* Runtime bilingual adapter for the vendored annotation engine.
 *
 * Label Studio Community Edition currently renders product copy directly from
 * its React bundle. Keeping this adapter outside that bundle gives us an
 * instant language switch and a maintainable upgrade boundary. It only changes
 * recognized UI copy; task data, project names, labels, code and user input are
 * deliberately left untouched.
 */
(function annotationPlatformLabelStudioI18n() {
  "use strict";

  const STORAGE_KEY = "annotation-platform.label-studio.language";
  const ZH = "zh-CN";
  const EN = "en";
  const BRAND_ZH = "星目智能标注";
  const BRAND_EN = "Xingmu Annotation";

  const zh = Object.freeze({
    // Global navigation and account
    "Label Studio": BRAND_ZH,
    "Projects": "项目",
    "Project": "项目",
    "Organization": "组织",
    "People": "成员",
    "Members": "成员",
    "Account": "账户",
    "Account & Settings": "账户与设置",
    "Personal Info": "个人信息",
    "Billing": "账单",
    "API": "API",
    "API Token": "API 令牌",
    "Dark Mode": "深色模式",
    "Light Mode": "浅色模式",
    "Light": "浅色",
    "Dark": "深色",
    "Auto": "自动",
    "Beta": "测试版",
    "Appearance": "外观",
    "Sign Out": "退出登录",
    "Logout": "退出登录",
    "Log In": "登录",
    "Log in": "登录",
    "Sign In": "登录",
    "Sign Up": "注册",
    "Sign up": "注册",
    "Create Account": "创建账户",
    "Email": "邮箱",
    "Email Address": "邮箱地址",
    "Password": "密码",
    "First Name": "名字",
    "Last Name": "姓氏",
    "Username": "用户名",
    "Save": "保存",
    "Save changes": "保存更改",
    "Cancel": "取消",
    "Close": "关闭",
    "Done": "完成",
    "Apply": "应用",
    "Confirm": "确认",
    "Continue": "继续",
    "Back": "返回",
    "Next": "下一步",
    "Previous": "上一步",
    "Finish": "完成",
    "Edit": "编辑",
    "Delete": "删除",
    "Remove": "移除",
    "Duplicate": "复制",
    "Copy": "复制",
    "Download": "下载",
    "Upload": "上传",
    "Refresh": "刷新",
    "Refresh data": "刷新数据",
    "Search": "搜索",
    "Clear": "清除",
    "Reset": "重置",
    "Select": "选择",
    "Select all": "全选",
    "Select all rows": "全选所有行",
    "Unselect all": "取消全选",
    "All": "全部",
    "None": "无",
    "Yes": "是",
    "No": "否",
    "OK": "确定",
    "Actions": "操作",
    "More": "更多",
    "Help": "帮助",
    "Documentation": "文档",
    "Community": "社区",
    "Support": "支持",
    "Keyboard shortcuts": "键盘快捷键",
    "Hotkeys": "快捷键",
    "Loading...": "加载中…",
    "Saving...": "保存中…",
    "Processing...": "处理中…",
    "No results": "暂无结果",
    "No data": "暂无数据",
    "Something went wrong": "出现错误",
    "Try again": "重试",
    "Keep me logged in this browser": "在此浏览器中保持登录",
    "Don't have an account?": "还没有账户？",
    "Already have an account?": "已有账户？",
    "Did you know?": "你知道吗？",
    "Learn more": "了解更多",
    "Brought to you by": "技术支持",
    "A full-fledged open source solution for data labeling": "面向智能视觉任务的一体化数据标注工作台",
    "You can sync data from all popular cloud storage providers to collect new items for labeling as they are uploaded, and return the annotation results to train and continuously improve models.": "可从主流云存储同步待标注数据，并将标注结果回流用于模型训练与持续迭代。",

    // Project list and project creation
    "All Projects": "全部项目",
    "Create Project": "创建项目",
    "Create": "新建",
    "Create new project": "新建项目",
    "New Project": "新建项目",
    "Create a new project": "创建新项目",
    "Project Name": "项目名称",
    "Project name": "项目名称",
    "Project Description": "项目说明",
    "Description": "说明",
    "Color": "颜色",
    "Created": "创建时间",
    "Created by": "创建人",
    "Updated": "更新时间",
    "Last updated": "最近更新",
    "Tasks": "任务",
    "Tasks:": "任务：",
    "Tasks Actions": "任务操作",
    "Task": "任务",
    "Annotations": "标注",
    "Submitted annotations:": "已提交标注：",
    "Annotation": "标注",
    "Predictions": "预测",
    "Predictions:": "预测：",
    "Prediction": "预测",
    "Completed": "已完成",
    "In progress": "进行中",
    "Draft": "草稿",
    "Skipped": "已跳过",
    "Accepted": "已接受",
    "Rejected": "已拒绝",
    "No projects yet": "暂无项目",
    "Get started by creating a new project": "创建一个新项目开始使用",
    "Create your first project": "创建第一个项目",
    "Project Settings": "项目设置",
    "General": "常规",
    "General Settings": "常规设置",
    "Danger Zone": "危险操作区",
    "Delete Project": "删除项目",
    "Delete project": "删除项目",
    "Project ID": "项目 ID",
    "Project options": "项目选项",
    "Workspace": "工作区",
    "Enterprise": "企业版",
    "Select an option": "请选择",
    "Simplify project management by organizing projects into workspaces.": "通过工作区组织项目，简化项目管理。",

    // Data Manager
    "Data Manager": "数据管理",
    "Data": "数据",
    "image": "图像",
    "Annotated by": "标注人员",
    "Filtered tasks": "筛选后的任务",
    "Total tasks in the project": "项目任务总数",
    "Import": "导入",
    "Import Data": "导入数据",
    "Export": "导出",
    "Export Data": "导出数据",
    "Sync": "同步",
    "Add source storage": "添加源存储",
    "Add target storage": "添加目标存储",
    "Source Cloud Storage": "源云存储",
    "Target Cloud Storage": "目标云存储",
    "Cloud Storage": "云存储",
    "Storage": "存储",
    "Local Files": "本地文件",
    "Files": "文件",
    "Upload Files": "上传文件",
    "Drop files here": "将文件拖放到此处",
    "or click to browse": "或点击浏览文件",
    "Add URL": "添加 URL",
    "Paste URL": "粘贴 URL",
    "Treat CSV/TSV as": "CSV/TSV 处理方式",
    "List of tasks": "任务列表",
    "Time Series": "时间序列",
    "Columns": "列",
    "Fields": "字段",
    "Filters": "筛选器",
    "Filter": "筛选",
    "Add Filter": "添加筛选条件",
    "Order": "排序",
    "Sort": "排序",
    "Ascending": "升序",
    "Descending": "降序",
    "View": "视图",
    "Views": "视图",
    "Create View": "创建视图",
    "Rename View": "重命名视图",
    "Delete View": "删除视图",
    "Default": "默认",
    "Selected": "已选择",
    "Unselected": "未选择",
    "Show all": "显示全部",
    "Hide all": "全部隐藏",
    "Delete Tasks": "删除任务",
    "Delete Annotations": "删除标注",
    "Delete Predictions": "删除预测",
    "Create Annotations From Predictions": "根据预测创建标注",
    "Retrieve Predictions": "获取预测",
    "Propagate Annotations": "传播标注",
    "Assign Annotators": "分配标注人员",
    "Add to View": "添加到视图",
    "Label Tasks": "标注任务",
    "Label All Tasks": "标注全部任务",
    "Label selected tasks": "标注所选任务",
    "Review selected tasks": "审核所选任务",
    "Open in new tab": "在新标签页中打开",
    "Order by": "排序依据",
    "Sort descending": "降序排列",
    "Sort ascending": "升序排列",
    "Toggle open": "展开菜单",
    "Comfortable density": "宽松密度",
    "Compact density": "紧凑密度",
    "Switch to list view": "切换为列表视图",
    "Switch to grid view": "切换为网格视图",
    "import button": "导入按钮",
    "export button": "导出按钮",
    "Total": "总计",
    "Page": "页",
    "Rows per page": "每页行数",
    "items per page": "条/页",
    "No tasks found": "未找到任务",
    "You don't have any tasks yet": "当前还没有任务",
    "Runtime error: Authentication credentials were not provided.": "运行错误：未提供身份验证凭据。",
    "Notifications (F8)": "通知（F8）",
    "Import data to get your project started": "导入数据以开始项目",
    "Connect your cloud storage or upload files from your computer": "连接云存储，或从本机上传文件",
    "Connect Cloud Storage": "连接云存储",
    "Google Cloud Storage": "Google 云存储",
    "Azure Blob Storage": "Azure Blob 存储",
    "Redis Storage": "Redis 存储",
    "See docs on importing data": "查看数据导入文档",
    "See docs on importing data (opens in a new tab)": "查看数据导入文档（在新标签页打开）",
    "(opens in a new tab)": "（在新标签页打开）",
    "See all templates": "查看全部模板",
    "Label Studio has dozens of pre-built templates for all data types you can use to configure your labeling UI, from image classification to sentiment analysis to supervised LLM fine-tuning.": "平台提供覆盖多种数据类型的预置模板，可用于配置从图像分类、情感分析到大模型监督微调的标注界面。",
    "Label Studio now has a Starter Cloud offering optimized for small teams and projects.": "平台提供面向小型团队和项目优化的轻量使用方案。",
    "There's an Enterprise version of Label Studio packed with more features and automation to label data faster while ensuring the highest quality.": "平台企业版提供更丰富的功能与自动化能力，在提升标注效率的同时保障数据质量。",

    // Labeling setup and integrations
    "Labeling Setup": "标注配置",
    "Labeling": "标注工作区",
    "Labeling setup": "标注配置",
    "Labeling Interface": "标注界面",
    "Browse Templates": "浏览模板",
    "Templates": "模板",
    "Template": "模板",
    "Custom Template": "自定义模板",
    "Code": "代码",
    "Visual": "可视化",
    "Preview": "预览",
    "Configure": "配置",
    "Configuration": "配置",
    "Validate": "校验",
    "Machine Learning": "机器学习",
    "Model": "模型",
    "Models": "模型",
    "Add Model": "添加模型",
    "Connect Model": "连接模型",
    "Model Version": "模型版本",
    "Backend": "后端",
    "Webhooks": "Webhook",
    "Add Webhook": "添加 Webhook",
    "Annotation Instructions": "标注说明",
    "Instructions": "说明",
    "Quality": "质量",
    "Quality Settings": "质量设置",
    "Overlap of annotations": "标注重叠数",
    "Task Sampling": "任务抽样",
    "Sequential sampling": "顺序抽样",
    "Random sampling": "随机抽样",
    "Tasks are ordered by Task ID": "任务按任务 ID 排序",
    "Tasks are chosen with uniform random": "任务以均匀随机方式抽取",
    "Uncertainty sampling": "不确定性抽样",
    "Tasks are chosen according to model uncertainty score (active learning mode).": "根据模型不确定性得分抽取任务（主动学习模式）。",
    "Show predictions to annotators": "向标注人员显示预测结果",
    "Show annotations to reviewers": "向审核人员显示标注结果",
    "Save general settings": "保存常规设置",
    "Evaluate GenAI models": "评估生成式 AI 模型",
    "Combine automation plus human supervision to evaluate and ensure LLM quality in the Enterprise platform.": "结合自动化与人工监督，在企业平台中评估并保障大模型质量。",

    // Annotation / review workspace
    "Submit": "提交",
    "Submit current annotation": "提交当前标注",
    "Update": "更新",
    "Skip": "跳过",
    "Accept": "接受",
    "Reject": "拒绝",
    "Fix": "修正",
    "Review": "审核",
    "Label": "标签",
    "Labels": "标签",
    "Region": "区域",
    "Regions": "区域",
    "Relation": "关系",
    "Relations": "关系",
    "Results": "结果",
    "History": "历史记录",
    "Details": "详情",
    "Outliner": "对象列表",
    "Undo": "撤销",
    "Redo": "重做",
    "Zoom In": "放大",
    "Zoom Out": "缩小",
    "Reset Zoom": "重置缩放",
    "Fit to screen": "适应屏幕",
    "Fullscreen": "全屏",
    "Exit fullscreen": "退出全屏",
    "Move": "移动",
    "Pan": "平移",
    "Rotate": "旋转",
    "Brightness": "亮度",
    "Contrast": "对比度",
    "Play": "播放",
    "Pause": "暂停",
    "Volume": "音量",
    "Speed": "速度",
    "Current Task": "当前任务",
    "Next Task": "下一个任务",
    "Previous Task": "上一个任务",
    "Next task": "下一个任务",
    "Previous task": "上一个任务",
    "Go to task": "前往任务",
    "Submit annotation": "提交标注",
    "Update annotation": "更新标注",
    "Skip task": "跳过任务",
    "Delete annotation": "删除标注",
    "Create annotation": "创建标注",
    "Create an annotation": "创建标注",
    "Compare all annotations": "对比全部标注",
    "Ground Truth": "标准答案",
    "Mark as Ground Truth": "设为标准答案",
    "Agreement": "一致率",
    "Score": "得分",
    "Confidence": "置信度",
    "Comments": "评论",
    "Comment": "评论",
    "Add Comment": "添加评论",
    "Resolve": "解决",
    "Unresolve": "取消解决",
    "Show predictions": "显示预测",
    "Hide predictions": "隐藏预测",
    "Show annotations": "显示标注",
    "Hide annotations": "隐藏标注",
    "Show regions": "显示区域",
    "Hide regions": "隐藏区域",
    "No regions": "暂无区域",
    "No annotations": "暂无标注",
    "No predictions": "暂无预测",
    "Info": "信息",
    "View region details": "查看区域详情",
    "Select a region to view its properties, metadata and available actions": "选择一个区域以查看其属性、元数据和可用操作",
    "Manual": "手动",
    "By Time": "按时间",
    "Hide all regions": "隐藏全部区域",
    "for screen reader": "供屏幕阅读器使用",
    "move-tool": "移动工具",
    "pan": "平移",
    "zoom-in": "放大",
    "zoom-out": "缩小",
    "Zoom presets (click to see options)": "缩放预设（点击查看选项）",
    "Zoom to fit": "缩放以适应窗口",
    "Zoom to actual size": "缩放到实际尺寸",
    "Arrow Marker": "箭头标记",
    "Unsaved changes": "有未保存的更改",
    "Discard changes": "放弃更改",
    "Keep editing": "继续编辑",
    "Annotation saved": "标注已保存",
    "Task completed": "任务已完成",

    // Organization, users and permissions
    "Invite Members": "邀请成员",
    "Invite People": "邀请成员",
    "Add People": "添加成员",
    "Role": "角色",
    "Owner": "所有者",
    "Administrator": "管理员",
    "Annotator": "标注员",
    "Reviewer": "审核员",
    "Manager": "管理人员",
    "Active": "启用",
    "Inactive": "停用",
    "Last activity": "最近活动",
    "Joined": "加入时间",
    "Organization ID": "组织 ID",
    "Access Token": "访问令牌",
    "Copy token": "复制令牌",
    "Reset token": "重置令牌",

    // Storage and export details
    "Type": "类型",
    "Status": "状态",
    "Connected": "已连接",
    "Disconnected": "已断开",
    "Pending": "等待中",
    "Error": "错误",
    "Last Sync": "上次同步",
    "Sync Storage": "同步存储",
    "Export format": "导出格式",
    "Download export": "下载导出文件",
    "Recent exports": "最近导出",
    "Generate export": "生成导出文件",
    "JSON": "JSON",
    "CSV": "CSV",
    "JSON_MIN": "精简 JSON",

    // Settings and feature language
    "Settings": "设置",
    "Language": "语言",
    "English": "English",
    "Simplified Chinese": "简体中文",
    "Interface language": "界面语言",
    "Enable": "启用",
    "Disable": "禁用",
    "Enabled": "已启用",
    "Disabled": "已禁用",
    "Name": "名称",
    "URL": "URL",
    "Date": "日期",
    "Time": "时间",
    "ID": "ID"
  });

  const textState = new WeakMap();
  const attributeState = new WeakMap();
  const pendingNodes = new Set();
  const titleState = {
    original: document.title || BRAND_EN,
    rendered: document.title || BRAND_EN
  };
  let language = normalizeLanguage(localStorage.getItem(STORAGE_KEY));
  let observer = null;
  let pendingFrame = 0;

  function normalizeLanguage(value) {
    return value === EN ? EN : ZH;
  }

  function preserveWhitespace(source, replacement) {
    const leading = source.match(/^\s*/)?.[0] || "";
    const trailing = source.match(/\s*$/)?.[0] || "";
    return leading + replacement + trailing;
  }

  function dynamicChinese(value) {
    let match;
    if ((match = value.match(/^(\d+)\s+tasks?$/i))) return `${match[1]} 个任务`;
    if ((match = value.match(/^(\d+)\s+annotations?$/i))) return `${match[1]} 条标注`;
    if ((match = value.match(/^(\d+)\s+predictions?$/i))) return `${match[1]} 条预测`;
    if ((match = value.match(/^(\d+)\s+regions?$/i))) return `${match[1]} 个区域`;
    if ((match = value.match(/^Tasks:\s*(\d+)\s*\/\s*(\d+)$/i))) return `任务：${match[1]} / ${match[2]}`;
    if ((match = value.match(/^Submitted annotations:\s*(\d+)$/i))) return `已提交标注：${match[1]}`;
    if ((match = value.match(/^Predictions:\s*(\d+)$/i))) return `预测：${match[1]}`;
    if ((match = value.match(/^Task\s+(\d+)$/i))) return `任务 ${match[1]}`;
    if ((match = value.match(/^Select Task\s+(\d+)$/i))) return `选择任务 ${match[1]}`;
    if ((match = value.match(/^Page\s+(\d+)$/i))) return `第 ${match[1]} 页`;
    if ((match = value.match(/^(\d+)\s+of\s+(\d+)$/i))) return `第 ${match[1]} / ${match[2]} 个`;
    if ((match = value.match(/^Showing\s+(\d+)\s+to\s+(\d+)\s+of\s+(\d+)$/i))) {
      return `显示第 ${match[1]}–${match[2]} 条，共 ${match[3]} 条`;
    }
    if ((match = value.match(/^Created\s+(.+)$/i))) return `创建于 ${match[1]}`;
    if ((match = value.match(/^Updated\s+(.+)$/i))) return `更新于 ${match[1]}`;
    if ((match = value.match(/^Last updated\s+(.+)$/i))) return `最近更新：${match[1]}`;
    if ((match = value.match(/^(\d+)\s+seconds? ago$/i))) return `${match[1]} 秒前`;
    if (value === "seconds ago") return "刚刚";
    if ((match = value.match(/^(\d+)\s+minutes? ago$/i))) return `${match[1]} 分钟前`;
    if ((match = value.match(/^(\d+)\s+hours? ago$/i))) return `${match[1]} 小时前`;
    if ((match = value.match(/^(\d+)\s+days? ago$/i))) return `${match[1]} 天前`;
    if ((match = value.match(/^about\s+(\d+)\s+months? ago$/i))) return `约 ${match[1]} 个月前`;
    if ((match = value.match(/^Prediction score\s*=\s*([\d.]+)$/i))) return `预测得分 = ${match[1]}`;
    if ((match = value.match(/^Selected\s+(\d+)\s+tasks?$/i))) return `已选择 ${match[1]} 个任务`;
    if ((match = value.match(/^Delete\s+(\d+)\s+tasks?$/i))) return `删除 ${match[1]} 个任务`;
    return null;
  }

  function translateString(source) {
    const trimmed = source.trim();
    if (!trimmed) return source;
    const translated = zh[trimmed] || dynamicChinese(trimmed);
    return translated ? preserveWhitespace(source, translated) : source;
  }

  function replaceLegacyBrand(source, replacement) {
    return source.replace(/Label Studio/g, replacement).replace(/智能标注工作台/g, replacement);
  }

  function renderString(source) {
    if (language === ZH) return replaceLegacyBrand(translateString(source), BRAND_ZH);
    return replaceLegacyBrand(source, BRAND_EN);
  }

  function translateTitle(source) {
    return source
      .split(" | ")
      .map((part) => zh[part] || part)
      .join(" | ");
  }

  function updateDocumentTitle() {
    const current = document.title || BRAND_EN;
    if (current !== titleState.rendered) titleState.original = current;
    const localized = language === ZH ? translateTitle(titleState.original) : titleState.original;
    const next = replaceLegacyBrand(localized, language === ZH ? BRAND_ZH : BRAND_EN);
    titleState.rendered = next;
    if (document.title !== next) document.title = next;
  }

  function shouldSkipText(node) {
    const parent = node.parentElement;
    if (!parent) return true;
    return Boolean(
      parent.closest(
        "script, style, code, pre, textarea, [contenteditable='true'], .CodeMirror, .cm-editor, " +
          "#xm-ls-language, [data-xm-no-translate]"
      )
    );
  }

  function applyTextNode(node) {
    if (shouldSkipText(node)) return;
    const current = node.data;
    let state = textState.get(node);
    if (!state) {
      state = { original: current, rendered: current };
      textState.set(node, state);
    } else if (current !== state.rendered) {
      state.original = current;
    }
    const next = renderString(state.original);
    state.rendered = next;
    if (node.data !== next) node.data = next;
  }

  function applyAttributes(element) {
    if (!(element instanceof Element) || element.closest("#xm-ls-language")) return;
    const names = ["placeholder", "title", "aria-label", "aria-description", "alt"];
    let states = attributeState.get(element);
    if (!states) {
      states = new Map();
      attributeState.set(element, states);
    }
    for (const name of names) {
      if (!element.hasAttribute(name)) continue;
      const current = element.getAttribute(name) || "";
      let state = states.get(name);
      if (!state) {
        state = { original: current, rendered: current };
        states.set(name, state);
      } else if (current !== state.rendered) {
        state.original = current;
      }
      const next = renderString(state.original);
      state.rendered = next;
      if (current !== next) element.setAttribute(name, next);
    }
  }

  function applyTree(root) {
    if (!root) return;
    if (root.nodeType === Node.TEXT_NODE) {
      applyTextNode(root);
      return;
    }
    if (root.nodeType !== Node.ELEMENT_NODE && root.nodeType !== Node.DOCUMENT_NODE) return;

    if (root.nodeType === Node.ELEMENT_NODE) applyAttributes(root);
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
    let node = walker.nextNode();
    while (node) {
      if (node.nodeType === Node.TEXT_NODE) applyTextNode(node);
      else applyAttributes(node);
      node = walker.nextNode();
    }
    applyCompositePhrases(root);
  }

  function applyCompositePhrases(root) {
    if (!root?.querySelectorAll) return;
    const replacements = language === ZH
      ? {
          "标签 全部 任务s": "标注全部任务",
          "标签 selected 任务s": "标注所选任务"
        }
      : {
          "标注全部任务": "Label All Tasks",
          "标注所选任务": "Label selected tasks"
        };
    for (const element of root.querySelectorAll("button span, [role='button'] span")) {
      if (element.children.length) continue;
      const current = element.textContent.trim();
      const replacement = replacements[current];
      if (replacement) element.textContent = replacement;
    }
  }

  function updateControl() {
    const control = document.getElementById("xm-ls-language");
    if (!control) return;
    const label = control.querySelector(".xm-ls-language__label");
    const select = control.querySelector("select");
    label.textContent = language === ZH ? "界面语言" : "Language";
    select.value = language;
    select.setAttribute("aria-label", language === ZH ? "选择界面语言" : "Select interface language");
    select.title = language === ZH ? "切换界面语言" : "Switch interface language";
  }

  function brandMarkup(nameClass) {
    return [
      '<span class="xm-brand-mark" aria-hidden="true"><i></i><i></i><i></i></span>',
      `<span class="${nameClass}"></span>`
    ].join("");
  }

  function updateBrand() {
    const brandName = language === ZH ? BRAND_ZH : BRAND_EN;
    for (const element of document.querySelectorAll(".xm-ls-brand__name, .xm-ls-login-brand__name")) {
      element.textContent = brandName;
    }
    for (const element of document.querySelectorAll("#xm-ls-brand, #xm-ls-login-brand")) {
      element.setAttribute("aria-label", brandName);
      element.setAttribute("title", brandName);
    }
  }

  function installBrand() {
    const header = document.querySelector(".lsf-menu-header");
    if (header && !document.getElementById("xm-ls-brand")) {
      const originalLogo = header.querySelector(".lsf-menu-header__logo");
      if (originalLogo) originalLogo.setAttribute("data-xm-legacy-brand", "true");
      const brand = document.createElement("a");
      brand.id = "xm-ls-brand";
      brand.href = "/projects";
      brand.setAttribute("data-xm-no-translate", "true");
      brand.innerHTML = brandMarkup("xm-ls-brand__name");
      let insertionPoint = originalLogo;
      while (insertionPoint?.parentElement && insertionPoint.parentElement !== header) {
        insertionPoint = insertionPoint.parentElement;
      }
      header.insertBefore(brand, insertionPoint?.parentElement === header ? insertionPoint : header.firstChild);
    }

    const login = document.querySelector(".login_page_new_ui .left");
    if (login && !document.getElementById("xm-ls-login-brand")) {
      const brand = document.createElement("div");
      brand.id = "xm-ls-login-brand";
      brand.setAttribute("data-xm-no-translate", "true");
      brand.innerHTML = brandMarkup("xm-ls-login-brand__name");
      login.insertBefore(brand, login.firstChild);
    }
    updateBrand();
  }

  function placeControl() {
    const control = document.getElementById("xm-ls-language");
    if (!control) return;
    const header = document.querySelector(".lsf-menu-header");
    if (header) {
      control.classList.remove("xm-ls-language--floating");
      const user = header.querySelector(".lsf-menu-header__user");
      if (control.parentElement !== header || control.nextElementSibling !== user) {
        header.insertBefore(control, user || null);
      }
    } else {
      control.classList.add("xm-ls-language--floating");
      if (control.parentElement !== document.body) document.body.appendChild(control);
    }
  }

  function setLanguage(nextLanguage, persist) {
    language = normalizeLanguage(nextLanguage);
    if (persist) localStorage.setItem(STORAGE_KEY, language);
    document.documentElement.lang = language;
    document.documentElement.dataset.xmLanguage = language;
    updateDocumentTitle();
    applyTree(document.body);
    updateControl();
    updateBrand();
    document.dispatchEvent(new CustomEvent("annotation-platform:language-change", { detail: { language } }));
  }

  function installControl() {
    if (!document.body || document.getElementById("xm-ls-language")) return;
    const control = document.createElement("div");
    control.id = "xm-ls-language";
    control.setAttribute("data-xm-no-translate", "true");
    control.innerHTML = [
      '<span class="xm-ls-language__icon" aria-hidden="true">文</span>',
      '<span class="xm-ls-language__label"></span>',
      '<select>',
      '<option value="zh-CN">简体中文</option>',
      '<option value="en">English</option>',
      "</select>"
    ].join("");
    control.querySelector("select").addEventListener("change", (event) => {
      setLanguage(event.target.value, true);
    });
    document.body.appendChild(control);
    updateControl();
    placeControl();
  }

  function scheduleApply(nodes) {
    for (const node of nodes) pendingNodes.add(node);
    if (pendingFrame) return;
    pendingFrame = requestAnimationFrame(() => {
      pendingFrame = 0;
      const nodesToApply = Array.from(pendingNodes);
      pendingNodes.clear();
      for (const node of nodesToApply) applyTree(node);
      installControl();
      installBrand();
      placeControl();
      updateDocumentTitle();
    });
  }

  function startObserver() {
    if (observer || !document.body) return;
    observer = new MutationObserver((mutations) => {
      const nodes = new Set();
      for (const mutation of mutations) {
        if (mutation.type === "characterData") nodes.add(mutation.target);
        if (mutation.type === "attributes") nodes.add(mutation.target);
        for (const node of mutation.addedNodes) nodes.add(node);
      }
      scheduleApply(nodes);
    });
    observer.observe(document.body, {
      childList: true,
      subtree: true,
      characterData: true,
      attributes: true,
      attributeFilter: ["placeholder", "title", "aria-label", "aria-description", "alt"]
    });
  }

  function boot() {
    installControl();
    installBrand();
    placeControl();
    setLanguage(language, false);
    startObserver();
  }

  window.AnnotationPlatformLabelStudio = Object.freeze({
    getLanguage: () => language,
    setLanguage: (nextLanguage) => setLanguage(nextLanguage, true),
    refresh: () => applyTree(document.body)
  });

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot, { once: true });
  else boot();
})();
