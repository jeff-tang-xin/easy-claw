# Ant Design 组件规范

> 尺寸以 default 尺寸为准（small = 高度 24、large = 40）。

## 1. 按钮（Button）

| 属性 | 规范 |
|------|------|
| 高度 | 32px（default）/ 24（small）/ 40（large） |
| 内边距 | `4px 15px`；图标与文字间距 8px |
| 圆角 | 6px |
| 类型 | primary（纯色主色，**每屏 ≤ 1 个**）/ default（白底灰边）/ text（无底无边）/ link（文字链接） |
| hover | primary 加深（#4096ff）；default 边框与文字变主色 |
| active | primary 再加深（#0958d9） |
| 危险按钮 | 红色系（#ff4d4f），用于删除等不可逆操作 |
| 禁用 | `rgba(0,0,0,0.04)` 底 + `rgba(0,0,0,0.25)` 字 + 无边框阴影 |
| loading | 点击后立即进入 loading，防重复提交 |

**禁忌**：主按钮不用渐变；同一视图不出现两个 primary；图标按钮必须有 tooltip。

## 2. 输入框（Input / Select）

| 属性 | 规范 |
|------|------|
| 高度 | 32px，内边距 `4px 11px` |
| 边框 | `#d9d9d9`，hover 变深（无 hover 也变主色——AntD hover 即主色淡边） |
| focus | 边框 `#1677ff` + 光环 `0 0 0 2px rgba(5,145,255,0.1)` |
| 错误态 | 边框 `#ff4d4f` + 光环 `0 0 0 2px rgba(255,77,79,0.1)`，下方红字说明 |
| 占位符 | `rgba(0,0,0,0.25)` |
| 前后缀 | addon（灰底块）或 prefix/suffix（内嵌图标） |

## 3. 表格（Table）

- 表头：`#fafafa` 底、`rgba(0,0,0,0.88)` 字、字重 600
- 行分割线：`#f0f0f0`（比边框淡一档）
- 行 hover：`#fafafa`
- 斑马纹：默认不用；需要时 `#fafafa`
- 固定表头/列：阴影提示滚动边界
- 空状态：统一 Empty 插画 + 文案

## 4. 弹窗（Modal）与抽屉（Drawer）

- 遮罩：`rgba(0,0,0,0.45)`，白 45% 透明度黑
- 弹窗圆角 8px、宽 520px（默认）、标题 16px/600
- 底部按钮：右对齐，主按钮在最右；取消在左
- 抽屉从右滑出，宽 378px（默认）
- 关闭：右上角 × + ESC + 点遮罩（表单类慎用点遮罩关闭，防误触丢数据）

## 5. 消息反馈（Message / Notification）

| 场景 | 组件 | 位置 |
|------|------|------|
| 轻提示（成功/失败） | Message | 顶部居中，3s 自动消失 |
| 重要通知（需关闭） | Notification | 右上角，手动关闭 |
| 行内校验 | Form error | 字段下方红字 |
| 全局阻塞 | Modal.confirm | 居中，需用户决策 |

- 成功 `#52c41a`、失败 `#ff4d4f`，图标 + 文案，白底圆角 8px + 轻阴影

## 6. 表单（Form）

- 布局：horizontal（label 左/右对齐）/ vertical / inline
- label 右对齐（horizontal 默认），冒号可选
- 必填：红色 `*` 在 label 前
- 校验时机：blur（失焦）+ change（提交前全量）
- 错误文案：说清「怎么改」，不只说「错了」

## 7. 标签（Tag）与徽章（Badge）

- Tag：淡底 + 淡边框 + 深字（如成功 `#f6ffed` 底 `#b7eb8f` 边 `#389e0d` 字）
- Badge：红点 6px（无数字）/ 数字徽章（上限 99+）
- 状态点：8px 圆点 + 文字

## 8. 空状态（Empty）

- 统一插画（或简化为图标）+ 一句话说明 + 可选操作按钮
- 颜色用中性灰，不用功能色

## 9. 布局骨架（Layout）

- 侧边栏：深色 `#001529`（或浅色白底），宽 208px，可折叠至 80px（只留图标）
- 顶栏：白底、高 64px、右侧放用户/通知
- 内容区：`#f5f5f5` 底、padding 24px
- 面包屑：内容区顶部，`rgba(0,0,0,0.45)` 分隔符

## 10. 分页（Pagination）

- 组成：总条数 + 页码 + 每页条数（Select）+ 快速跳转（showQuickJumper）
- 默认每页 10 条，可选 10/20/50/100
- 变化条数时回到第 1 页；只有 1 页时可隐藏（showLessItems）
- 表格内分页右对齐；卡片流分页居中

## 11. 标签页（Tabs）

- 类型：line（下划线，默认）/ card（卡片）/ editable-card（可增删）
- 激活态：文字主色 + 底部 2px 主色条（line 型）
- 超出宽度：横向滚动或 Dropdown 收纳；不用两行折行
- 位置：top（默认）/ left（设置页常用）

## 12. 步骤条（Steps）

- 状态：wait（灰）/ process（主色，当前）/ finish（主色勾）/ error（红叉）
- 横向（默认，配表单分步）/ 纵向（详情页流程展示）
- 点按已完成步骤可回跳（可点击时加 hover 态）；error 步骤允许重试

## 13. 描述列表（Descriptions）

- 详情页标配：label + value 成对，列数 1–4（响应式减列）
- bordered：有边框表格样式（数据密集）；无边框：轻量展示
- label 用次级文字色，value 用主文字色；长文本 span 合并单元格
- 状态类 value 配语义色 Tag

## 14. 结果页（Result）与统计（Statistic）

- Result：大图标（成功绿勾/失败红叉/警告黄/404 灰插画）+ title + subTitle + extra 按钮组
- Statistic：数值大字（24px+，tabular-nums）+ 标题小字 + 前后缀（¥/%）+ 趋势箭头（涨绿跌红或按业务反转并全站统一）
- 看板卡片：Statistic + Card 组合，等高对齐

## 15. 树与级联（Tree / TreeSelect / Cascader）

- Tree：展开箭头 + 缩进 24px/级 + 复选框（checkable）三态（全选/半选）
- TreeSelect：单选下拉形态 + 多选 Tag 形态；支持搜索（showSearch）
- Cascader：逐级下钻，hover 展开子级（changeOnSelect 控制是否选叶子）
- 数据 > 1000 节点必须开虚拟滚动；异步加载显示展开箭头 loading

## 16. 上传（Upload）

- 形态：按钮（文件列表在下）/ 拖拽区（虚线框，drag over 高亮主色）/ 头像（圆形裁剪）/ 照片墙（方形缩略图网格）
- 文件列表：文件名 + 大小 + 进度条（上传中）+ 删除；失败标红可重试
- 校验：类型/大小前置校验（beforeUpload），超限 Message 提示不发起请求
- 上传中禁止提交表单或自动阻塞提交

## 17. 日期选择（DatePicker）与穿梭框（Transfer）

- DatePicker：占位「请选择日期」；范围 RangePicker 分开占位；快捷项（今天/近 7 天）放预置面板
- 禁用日期置灰不可点；时区敏感场景统一存 UTC 显示本地
- Transfer：左右两栏（标题 + 搜索 + 列表 + 计数），中间移动按钮；大数据加分页或搜索

## 18. 折叠面板（Collapse）与卡片（Card）

- Collapse：手风琴（accordion）默认关；头部可带额外操作（阻止展开冒泡）
- Card：头（title + extra）/ 体 / 底（actions）三段；hover 可浮起（hoverable，仅可点击卡片）
- 内嵌小卡片：用 `size="small"` 或去边框改分割线，避免卡片套卡片边框过重
- 加载态：Card 自带 loading 骨架（loading 属性）
