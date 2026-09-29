# Element UI / Element Plus Token 速查表

> Element UI 与 Element Plus 视觉规范一致；所有值可直接用于 CSS 变量定义。

## 1. 色彩

### 主色
| 场景 | 值 |
|------|-----|
| 主色（primary） | `#409eff` |
| hover | `#66b1ff` |
| active / 按下 | `#3a8ee6` |
| 淡底（plain 模式底色） | `#ecf5ff`，边框 `#b3d8ff`，文字 `#409eff` |

### 功能色（Element 称「辅助色」）
| 语义 | 主值 | 淡底 | 淡边框 | 深文字 |
|------|------|------|--------|--------|
| 成功 success | `#67c23a` | `#f0f9eb` | `#e1f3d8` | `#529b2e` |
| 警告 warning | `#e6a23c` | `#fdf6ec` | `#faecd8` | `#b88230` |
| 危险 danger | `#f56c6c` | `#fef0f0` | `#fde2e2` | `#c45656` |
| 信息 info | `#909399` | `#f4f4f5` | `#e9e9eb` | `#73767a` |

### 文字四级（实色体系，非透明度）
| 级别 | 值 | 用途 |
|------|-----|------|
| 主要文字 | `#303133` | 标题、强调 |
| 常规文字 | `#606266` | 正文、表单 label |
| 次要文字 | `#909399` | 辅助说明、次要信息 |
| 占位文字 | `#c0c4cc` | placeholder、禁用、分割线上的文字 |

> 对比度提示：`#909399` 白底 3.0:1、`#c0c4cc` 白底 1.9:1——两者仅限辅助/占位，
> 正文用 `#606266`（7.0:1）以上，满足 WCAG AA。

### 边框四级（由深到浅）
| 级别 | 值 | 用途 |
|------|-----|------|
| 一级 | `#dcdfe6` | 控件边框（输入框/按钮默认） |
| 二级 | `#e4e7ed` | hover 边框、卡片边框 |
| 三级 | `#ebeef5` | 表格内分割线、轻分隔 |
| 四级 | `#f2f6fc` | 最轻分隔（几乎不可见） |

### 填充色
| 场景 | 值 |
|------|-----|
| 页面底色 | `#f0f2f5` |
| hover 填充 | `#f5f7fa` |
| 禁用底 | `#f5f7fa` |
| 斑马纹 | `#fafafa` |

> 暗色模式（Element Plus）：页面底 `#0a0a0a`、容器 `#141414`、边框 `#363637`、
> 文字主 `#e5eaf3` / 常 `#cfd3dc` / 次 `#a3a6ad`。

## 2. 字体

| Token | 值 |
|-------|-----|
| fontSize | 14px |
| fontSizeExtraSmall / Small / Medium / Large | 12 / 13 / 14 / 18px |
| 标题 | 20px（页面级）/ 18 / 16 |
| lineHeight | 1.5 |
| fontFamily | `'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', '微软雅黑', Arial, sans-serif` |

## 3. 尺寸与间距

- 控件高度三档：**40（default）/ 32（small）/ 24（mini）**——比 AntD 整体大一档
- 输入框内边距：`0 12px`（default）
- 按钮内边距：`12px 19px`（default）
- 栅格：24 列体系（el-row / el-col），常用 gutter 20px
- 页面留白：内容区 `padding 20px`

## 4. 圆角

| Token | 值 | 用途 |
|-------|-----|------|
| border-radius-base | 4px | 控件（按钮/输入框/卡片） |
| border-radius-small | 2px | 小元素（标签） |
| border-radius-round | 20px | 圆形按钮/胶囊 |

> Element 圆角整体比 AntD 方正（4 vs 6），是两者气质差异的关键之一。

## 5. 阴影

```css
/* box-shadow-base：下拉/卡片 */
box-shadow: 0 2px 4px rgba(0, 0, 0, 0.12), 0 0 6px rgba(0, 0, 0, 0.04);

/* box-shadow-light：轻浮层（下拉菜单） */
box-shadow: 0 0 12px 0 rgba(0, 0, 0, 0.12);

/* box-shadow-lighter：最轻 */
box-shadow: 0 0 6px 0 rgba(0, 0, 0, 0.12);

/* box-shadow-dark：重浮层（弹窗） */
box-shadow: 0 2px 4px rgba(0, 0, 0, 0.12), 0 0 6px rgba(0, 0, 0, 0.12);
```

## 6. 动效

| 项 | 值 |
|-----|-----|
| 时长 | 0.3s |
| 曲线 | `ease`（默认）、`ease-in-out` |
| fade 体系 | fade-in / fade-in-linear |
| zoom 体系 | zoom-in-center / zoom-in-top / zoom-in-bottom |
| 弹窗 | 淡入 + 轻微上移；下拉从顶部展开（zoom-in-top） |

## 7. 焦点态

```css
/* Element 式 focus：边框主色（无外圈光环，比 AntD 更轻） */
outline: none;
border-color: #409eff;
```

## 8. 语义取色规则（色值 → 场景映射）

Element 的色板是「固定值 + 语义」制（不像 AntD 有 1–10 色阶算法），取色按场景对号：

| 场景 | 取哪个值 |
|------|----------|
| 主按钮/选中态/链接 | 主色 `#409eff` |
| 主按钮 hover / plain hover | `#66b1ff`（亮一档） |
| 主按钮按下 | `#3a8ee6`（深一档） |
| 标签淡底 | 功能色淡底（如成功 `#f0f9eb`） |
| 标签文字 | 功能色深字（如成功 `#529b2e`，比主值深两档保证对比度） |
| 控件边框 | `#dcdfe6`（一级） |
| 卡片/容器边框 | `#e4e7ed`（二级） |
| 表格线/轻分隔 | `#ebeef5`（三级） |
| 最轻分隔（几乎不可见） | `#f2f6fc`（四级） |
| hover 底 / 禁用底 | `#f5f7fa` |
| 页面底 | `#f0f2f5` |
| 斑马纹 | `#fafafa` |

> 深字比主值深两档是 Element 标签可读性的关键（如 success tag 文字 `#529b2e`
> 而非 `#67c23a`）——浅底浅字对比度不够。

## 9. 层级（zIndex 约定）

Element 用 popup-manager 从 2000 起自动分配，手写 z-index 避开该区间或遵循相对关系：

| 层 | 基准 | 成员 |
|----|------|------|
| 页面内容 | 0 | 常规文档流 |
| 局部悬浮 | 10+ | sticky 表头、卡片内工具条 |
| 下拉/弹出 | 2000+ | Select 下拉、DatePicker、Popover（popup-manager 自动） |
| 遮罩 | 2000+ | v-loading 遮罩、Dialog 遮罩 |
| 弹窗本体 | 遮罩 +1 | Dialog/Drawer |
| 全局提示 | 更高 | Message/Notification（要盖过 Dialog） |

> 原则：不要手写 9999；自定义浮层用 `--el-index-*` 变量（Plus）或与 popup 区间保持相对关系。

## 10. 响应式断点

| 断点 | 阈值 | 典型行为 |
|------|------|----------|
| xs | < 768px | 单列堆叠（el-col :xs=24） |
| sm | ≥ 768px | |
| md | ≥ 992px | 表单 label 上置 |
| lg | ≥ 1200px | 常规桌面 |
| xl | ≥ 1920px | 宽屏（内容 max-width 1200–1400） |

- 栅格：24 列（el-row :gutter + el-col :span/:xs/:sm/...）
- 中后台通常保证 lg 以上 + xs 不崩；el-form inline 在窄屏自动换行
