# Element UI / Element Plus 组件规范

> 尺寸以 default 为准（small = 32、mini = 24）。

## 1. 按钮（el-button）

| 属性 | 规范 |
|------|------|
| 高度 | 40px（default）/ 32（small）/ 24（mini） |
| 内边距 | `12px 19px`（default） |
| 圆角 | 4px |
| 类型 | primary（纯色）/ default（白底 `#dcdfe6` 边）/ text（文字按钮）/ link |
| 语义色 | primary / success / warning / danger / info 五色系 |
| plain 模式 | 淡底 + 淡边框 + 主色字（如 primary plain：`#ecf5ff` 底 `#b3d8ff` 边 `#409eff` 字） |
| hover | 主色变亮（#66b1ff）；plain 变纯色 |
| active | 主色变深（#3a8ee6） |
| 禁用 | `#f5f7fa` 底 + `#c0c4cc` 字 + `#ebeef5` 边 |
| loading | 图标旋转 + 文字不变，禁点 |

**禁忌**：同一区域多个 primary；text 按钮用于主操作（辨识度不够）。

## 2. 输入框（el-input / el-select）

| 属性 | 规范 |
|------|------|
| 高度 | 40px，内边距 `0 12px` |
| 边框 | `#dcdfe6`；hover `#c0c4cc`；focus `#409eff` |
| 占位符 | `#c0c4cc` |
| 禁用 | `#f5f7fa` 底 + `#c0c4cc` 字 |
| 清空按钮 | hover 时出现 clear 图标 |
| 前后缀 | prefix/suffix 图标内嵌；slot 前置/后置块 |
| 错误态 | 边框 `#f56c6c`，配合表单校验红字 |

## 3. 表格（el-table）

- 表头：白底、`#909399` 字、字重 600、高 40px（与行同高）
- 行高：40px（default，可配 size）
- 分割线：`#ebeef5`（三级边框色）
- 行 hover：`#f5f7fa`
- 斑马纹：`#fafafa`（stripe 属性）
- 固定列：阴影提示边界
- 排序图标：点击表头切换升/降/默认

## 4. 对话框（el-dialog）与抽屉（el-drawer）

- 遮罩：`rgba(0,0,0,0.5)`
- 弹窗：圆角 4px（Plus 为 8px 视觉微调）、头部标题 18px、底部按钮右对齐
- 底部按钮惯例：取消（default）在左、确定（primary）在右
- 抽屉：默认从右滑出，宽 30%（最小 400px 左右）
- 关闭：× + ESC；表单类建议 `close-on-click-modal=false` 防误触

## 5. 消息反馈（Message / Notification / MessageBox）

| 场景 | 组件 | 位置与行为 |
|------|------|-----------|
| 轻提示 | Message | 顶部居中，3s 自动消失，图标 + 文案 |
| 通知 | Notification | 右上角，带标题 + 关闭按钮 |
| 确认框 | MessageBox（confirm） | 居中，需点按钮关闭 |
| 行内校验 | Form item error | 字段下方 `#f56c6c` 红字 |

- Message 类型色：success `#67c23a` / warning `#e6a23c` / error `#f56c6c` / info `#909399`
- 白底圆角 4px + `box-shadow-light`，顶部居中堆叠

## 6. 表单（el-form）

- 布局：horizontal（label 右对齐默认）/ vertical / inline
- label 宽度统一（label-width），右对齐带冒号
- 必填：红色 `*` 在 label 前
- 校验：async-validator；触发时机 blur + change
- 错误红字 12px，出现在字段正下方

## 7. 标签（el-tag）与徽章（el-badge）

- Tag 五色系 + 三种主题：dark（纯色底白字）/ light（淡底主色字）/ plain（淡底淡边主色字）
- 默认 light 主题：如 success tag = `#f0f9eb` 底 `#e1f3d8` 边 `#529b2e` 字
- Badge：红点（dot）或数字（上限 max=99 显示 99+）
- 圆角：tag 4px、badge 圆形

## 8. 空状态（el-empty）

- 内置插画（多种场景图）+ description 文案 + 可选底部按钮
- 中性灰插画，不用功能色

## 9. 布局骨架（el-container）

- 结构：`el-container` > `el-aside`（侧栏）+ `el-header`（顶栏）+ `el-main`（内容）
- 侧栏：深色 `#304156`（经典配色）或白底，宽 200px，可折叠至 64px（图标模式）
- 顶栏：白底、高 60px、底部 `#e6e6e6` 分割线
- 内容区：`#f0f2f5` 底、padding 20px
- 菜单（el-menu）：激活项主色文字 + 主色右边条（或淡主色底）

## 10. 分页（el-pagination）

- 组成：total（总条数）+ prev/pager/next + sizes（每页条数）+ jumper（快速跳转）
- `background` 属性：页码带底色（推荐，可点性更明显）
- 默认每页 10 条，可选 10/20/50/100；改条数回第 1 页
- 布局顺序用 layout 属性统一（如 `"total, sizes, prev, pager, next, jumper"`），全站一致
- 只有一页时 `hide-on-single-page` 隐藏

## 11. 标签页（el-tabs）

- 类型：默认（下划线）/ card（卡片）/ border-card（带边框卡片）
- 激活态：文字主色 + 底部主色条（默认型）
- 位置：top（默认）/ left（设置页常用）
- 可关闭（closable）时关闭前提示未保存变更；超出宽度横向滚动

## 12. 步骤条（el-steps）

- 状态：wait（灰）/ process（主色，当前）/ finish（主色勾）/ error（红叉）
- 横向（默认）/ 简洁模式（simple，浅底小尺寸）
- 配分步表单：每步独立校验，error 步骤标红可重试
- 描述文字用 description 插槽；步骤 ≤ 5 个横向展示

## 13. 描述列表（el-descriptions）

- 详情页标配：label + value 成对，column 2–4（响应式）
- bordered：表格样式（数据密集）；默认：轻量无边框
- label 用 `label-class-name` 或默认灰；状态 value 配语义色 el-tag
- 长文本用 span 合并列；extra 插槽放操作按钮

## 14. 结果页（el-result）与统计（el-statistic）

- el-result（Plus）：icon（success/warning/error/info）+ title + sub-title + extra 按钮
- Element UI 无内置：大图标 + 文案 + el-button 自组，风格对齐
- el-statistic（Plus）：数值 + 标题 + 前后缀；数值 tabular-nums
- 看板卡片：el-card + 数值大字（24px+）等高对齐，gutter 20

## 15. 树与级联（el-tree / el-tree-select / el-cascader）

- el-tree：展开箭头 + 缩进 18px/级 + checkbox（show-checkbox，勾选三态）
- 节点 hover 高亮 `#f5f7fa`；当前选中主色底淡 `#ecf5ff` 主色字
- el-tree-select：下拉形态选树节点；大列表开 filterable 搜索
- el-cascader：逐级下钻，`props.checkStrictly` 控制可选任意级；清空按钮 hover 显示
- 数据 > 1000 节点必须懒加载（lazy）或过滤，禁一次性全量渲染

## 16. 上传（el-upload）

- 形态：按钮（默认，文件列表在下）/ 拖拽（drag，虚线框）/ 头像（list-type + 裁剪）/ 照片墙（picture-card 方格）
- 文件列表：文件名 + 图标 + 进度条（上传中）+ 成功勾/失败叉（可重试）
- 校验：beforeUpload 里做类型/大小校验，返回 false 阻止上传并 Message 提示
- `limit` 限制数量，超限 on-exceed 提示；上传中禁提交表单

## 17. 日期与时间（el-date-picker / el-time-picker）

- type：date / daterange / datetime / datetimerange / month / year
- 占位符 start-placeholder/end-placeholder 分开写；格式 value-format 统一（如 `YYYY-MM-DD`）
- 快捷选项：picker-options.shortcuts（今天/近 7 天/近 30 天）
- 禁用日期：disabledDate（如只能选今天之后）；清空按钮 hover 显示

## 18. 折叠面板（el-collapse）与卡片（el-card）

- el-collapse：手风琴（accordion）默认可多开；标题带图标（expand-icon-position）
- el-card：header（title + 插槽按钮）/ body（padding 20）/ shadow 属性（always/hover/never）
- 列表页卡片 shadow="never"（靠边框分层）；悬浮卡片才用 hover/always
- 卡片套卡片：内层改用分割线或 `shadow="never"` + 淡底，避免边框过重
