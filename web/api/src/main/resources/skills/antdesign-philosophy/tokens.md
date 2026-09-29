# Ant Design Token 速查表

> 以 v5 默认主题为准；与 v4 不同处单独标注。所有值可直接用于 CSS 变量定义。

## 1. 色彩

### 主色（colorPrimary）
| Token | 值 | 用途 |
|-------|-----|------|
| colorPrimary | `#1677ff`（v4: `#1890ff`） | 主按钮、选中态、链接 |
| colorPrimaryHover | `#4096ff` | 主按钮 hover |
| colorPrimaryActive | `#0958d9` | 主按钮按下 |
| colorPrimaryBg | `#e6f4ff` | 主色淡底（选中行、标签底） |
| colorPrimaryBgHover | `#bae0ff` | 淡底 hover |
| colorPrimaryBorder | `#91caff` | 主色边框（淡） |

### 功能色
| 语义 | 主值 | 淡底 | 边框 |
|------|------|------|------|
| 成功 colorSuccess | `#52c41a` | `#f6ffed` | `#b7eb8f` |
| 警告 colorWarning | `#faad14` | `#fffbe6` | `#ffe58f` |
| 错误 colorError | `#ff4d4f` | `#fff2f0` | `#ffccc7` |
| 信息 colorInfo | `#1677ff` | `#e6f4ff` | `#91caff` |

### 中性色
| Token | 值 | 用途 |
|-------|-----|------|
| colorBgContainer | `#ffffff` | 组件容器底（卡片/输入框） |
| colorBgLayout | `#f5f5f5` | 页面布局底 |
| colorBgElevated | `#ffffff` | 浮层底（下拉/弹窗） |
| colorFillSecondary | `#f5f5f5` | 次级填充（hover 底） |
| colorFillTertiary | `#fafafa` | 三级填充（斑马纹/禁用底） |
| colorBorder | `#d9d9d9` | 控件边框（输入框/按钮） |
| colorBorderSecondary | `#f0f0f0` | 分割线（表格线/卡片内分隔） |
| colorSplit | `rgba(5,5,5,0.06)` | 分割线（语义别名） |

### 文字四级
| Token | 值 | 用途 |
|-------|-----|------|
| colorText | `rgba(0,0,0,0.88)` | 主文字（标题/正文） |
| colorTextSecondary | `rgba(0,0,0,0.65)` | 次级文字 |
| colorTextTertiary | `rgba(0,0,0,0.45)` | 辅助说明/占位 |
| colorTextQuaternary | `rgba(0,0,0,0.25)` | 禁用/装饰 |

> 暗色模式：文字改用白色透明度体系（0.85/0.65/0.45/0.25），容器底 `#141414`。

## 2. 字体

| Token | 值 |
|-------|-----|
| fontSize | 14px |
| fontSizeSM / LG / XL | 12 / 16 / 20px |
| fontSizeHeading1–3 | 38 / 30 / 24px |
| lineHeight | 1.5714285714285714 |
| fontFamily | `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif` |
| code 字体 | `'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace` |

## 3. 间距与尺寸

- **8px 栅格**：所有间距取 8 的倍数（4 仅用于图标与文字的微调）
- 控件高度：`controlHeight` 32px（default）/ 24（small）/ 40（large）
- 组件内边距：按钮 `padding 4px 15px`（default）、输入框 `4px 11px`
- 布局留白：页面内容区 `padding 24px`；卡片 body `24px`、卡片间 `16px`

## 4. 圆角

| Token | 值 | 用途 |
|-------|-----|------|
| borderRadius | 6px | 控件（按钮/输入框/下拉） |
| borderRadiusLG | 8px | 容器（卡片/弹窗） |
| borderRadiusSM | 4px | 小元素（标签/徽章内） |

## 5. 阴影

```css
/* boxShadow（弹窗/抽屉等重浮层） */
box-shadow: 0 6px 16px 0 rgba(0,0,0,0.08),
            0 3px 6px -4px rgba(0,0,0,0.12),
            0 9px 28px 8px rgba(0,0,0,0.05);

/* boxShadowSecondary（下拉菜单/卡片 hover） */
box-shadow: 0 6px 16px 0 rgba(0,0,0,0.08),
            0 3px 6px -4px rgba(0,0,0,0.12),
            0 9px 28px 8px rgba(0,0,0,0.05);

/* 卡片默认：极轻甚至无阴影，靠 1px 边框 #f0f0f0 分层 */
```

> 原则：**能用边框分层就不用阴影**；阴影只给「浮起」的元素（下拉/弹窗/拖拽中）。

## 6. 动效

| Token | 值 |
|-------|-----|
| motionDurationFast | 0.1s |
| motionDurationMid | 0.2s |
| motionDurationSlow | 0.3s |
| motionEaseInOut | `cubic-bezier(0.645, 0.045, 0.355, 1)` |
| motionEaseOut | `cubic-bezier(0.215, 0.61, 0.355, 1)` |
| motionEaseIn | `cubic-bezier(0.55, 0.055, 0.675, 0.19)` |

- 常规过渡 0.3s；hover 反馈 0.1–0.2s
- 入场动效：淡入 + 轻位移/缩放（`transform: scale(0.96) → 1`），出场更快
- 尊重 `prefers-reduced-motion`

## 7. 焦点态（键盘可达）

```css
/* AntD 式 focus 光环：边框变主色 + 外圈淡主色 */
outline: none;
border-color: #1677ff;
box-shadow: 0 0 0 2px rgba(5, 145, 255, 0.1);
```

## 8. 色阶体系（色板生成逻辑）

主色经算法派生 1–10 号色阶，**编号即语义**（写样式时按语义取号，不取任意中间值）：

| 号 | 语义 | 典型用途 |
|----|------|----------|
| 1 | 最淡底 | 选中行背景、标签底（`#e6f4ff`） |
| 2 | 淡底 hover | 淡底元素的 hover |
| 3 | 淡边框 | Tag/Checkbox 边框（`#91caff`） |
| 4 | 边框 hover | 淡边框 hover |
| 5 | **hover 色** | 主按钮 hover（`#4096ff`） |
| 6 | **主色** | 主按钮、选中态、链接（`#1677ff`） |
| 7 | **active 色** | 主按钮按下（`#0958d9`） |
| 8–10 | 深色文本 | 深色语义文字（如成功深字 `#389e0d`） |

> 功能色（成功/警告/错误）同样各有 1–10 号色阶，语义规则相同。
> 暗色模式 = 同一语义换一组色阶值，组件代码不变（Token 化的意义）。

## 9. 层级（zIndex 约定）

浮层按「越临时越高」排布，避免手写魔法数字：

| 层 | 基准 | 成员 |
|----|------|------|
| 页面内容 | 0 | 常规文档流 |
| 局部悬浮 | 10+ | Affix、粘性表头、卡片内悬浮工具条 |
| 遮罩 | 1000 | Modal/Drawer 遮罩 |
| 遮罩上内容 | 1000+ | Modal/Drawer 本体 |
| 全局提示 | 1010+ | Message、Notification（要盖过 Modal） |
| 即时浮层 | 1030+ | Popover、Dropdown、Select 下拉 |
| 最高提示 | 1060+ | Tooltip（永远在最上） |

> 原则：Modal 里的下拉要能弹出 Modal 之上 → 下拉层 > 遮罩层；
> Tooltip > 一切。项目里建议定义 `--z-*` 变量统一管理。

## 10. 响应式断点

| 断点 | 阈值 | 栅格 |
|------|------|------|
| xs | < 576px | 单列堆叠 |
| sm | ≥ 576px | |
| md | ≥ 768px | 表单 label 上置 |
| lg | ≥ 992px | 常规桌面（侧栏展开） |
| xl | ≥ 1200px | 内容 max-width 1200 |
| xxl | ≥ 1600px | 宽屏多列 |

- 栅格：24 列体系（Row/Col + gutter 16）；`xs=24` 单列是移动端兜底
- 中后台产品通常只需保证 lg 以上 + xs 不崩（内部系统可不做移动端）
