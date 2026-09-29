# Element UI 典型页面模式

> 中后台管理 80% 的页面可归为以下模式。做新页面前先对号入座，不要发明新布局。

## 1. 应用骨架（el-container 经典三段）

```
el-container（横向）
├─ el-aside  侧栏：#304156 深色（或白底），宽 200px，折叠 64px 图标模式
│   └─ el-menu（router 模式 + collapse）
└─ el-container（纵向）
    ├─ el-header 顶栏：白底 60px，面包屑 + 右侧用户/全屏
    └─ el-main  内容：#f0f2f5 底，padding 20px
```

- 菜单激活：主色文字 + 主色右边条（或淡主色底，全站统一一种）
- 折叠按钮放侧栏底部或顶栏左侧；折叠状态持久化

## 2. 列表页（最高频模式）

```
el-card
├─ 搜索区：el-form（inline）+ 查询/重置按钮
├─ 工具栏：左侧新增/批量删除（primary + danger plain），右侧刷新/列设置
├─ el-table：操作列固定右侧（编辑/删除，≤ 3 个）
└─ el-pagination：右对齐，total + sizes + jumper
```

- 搜索条件 ≤ 6 个用 inline；更多条件收进「展开筛选」
- 批量删除：table 多选（selection 列）+ danger plain 按钮 + MessageBox 确认
- 删除单行：`el-popconfirm`（轻）或 MessageBox（重），成功后 Message + 刷新

## 3. 表单页

| 形态 | 适用 | 要点 |
|------|------|------|
| 标准表单 | 字段 ≤ 20 | el-form rules 校验 + label-width 统一 + 底部提交/取消 |
| 分步表单 | 流程 > 3 步 | el-steps + 分段校验 + 上一步/下一步 |
| 弹窗表单 | 轻量编辑 ≤ 8 字段 | el-dialog 内 el-form；关闭时 resetFields |
| 行内编辑 | 表格快改 | el-input 切换显示（scope row 状态控制） |

- label 右对齐带冒号；必填红星自动（rules required）
- 提交：validate 全量校验 → 按钮 loading → 成功 Message + 返回
- 取消按钮：有改动时 MessageBox 提示「放弃修改？」防误触

## 4. 详情页

```
el-card
├─ 头部：标题 + 状态 el-tag + 操作按钮组（编辑/删除/更多）
├─ el-descriptions（bordered，column 2–3）
├─ el-tabs 关联信息（订单明细/日志/附件）
└─ el-timeline 操作日志（可选）
```

- 关键编号放 descriptions 首行；金额/数量右对齐 tabular-nums
- 状态 tag 用语义色 light 主题；作废/禁用用 info 色

## 5. Dashboard / 数据看板

- 统计卡片行：el-row :gutter="20" + el-col :span="6" × 4，el-card 内数值大字
- Element Plus 有 el-statistic；Element UI 用大号数字 + 小标题自组
- 图表：ECharts 配 el-card（标题 + 时间筛选放 card header）
- 卡片间距 20px（gutter 惯例）；数值变化加过渡动画（可选）

## 6. 结果页与异常页

- Element Plus：el-result（success/warning/error/info 图标 + title + 描述 + 按钮）
- Element UI：自组（大图标 + 文案 + el-button 返回）
- 异常页 403/404/500：插画 + 文案 + 「返回首页」primary 按钮
- 重要流程提交成功 → 结果页；轻量操作成功 → Message 即可

## 7. 设置页

- 左侧 el-menu（垂直，宽 200px）+ 右侧 el-card 表单
- 或 el-tabs（tab-position="left"）切换设置分组
- 每组独立「保存」按钮（就近原则）或统一底部保存条，全站统一

## 8. 登录页

- 居中 el-card（宽 400）或左右分栏（左品牌插画/右表单）
- 字段：账号/密码/验证码 + 记住我（el-checkbox）+ 登录按钮（block primary）
- 错误：el-alert（可关闭，error 型）放表单上方；字段格式错误走 rules
- 第三方登录：el-divider「其他方式」+ 图标按钮组

## 9. 加载与空的层级策略

| 场景 | 方案 |
|------|------|
| 首屏/整页 | v-loading 指令（全屏遮罩 element-loading-fullscreen）或 el-skeleton（Plus） |
| 表格刷新 | el-table 自带 v-loading（只遮表格） |
| 按钮动作 | 按钮 loading 属性 |
| 无数据 | el-empty + 底部按钮（action 插槽） |
| 局部区块 | v-loading 指令绑区块元素 |

- v-loading 文案自定义（element-loading-text）；加载中禁止重复触发
