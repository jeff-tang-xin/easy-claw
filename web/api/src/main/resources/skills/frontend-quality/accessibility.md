# 可访问性（A11y）WCAG 2.1 AA

## 语义化 HTML
- 用 `<button>` 而不是 `<div onClick>`
- 用 `<nav>` `<main>` `<article>` `<section>` 地标
- 标题层级正确：h1 → h2 → h3，不跳级

## 键盘导航
- 所有交互元素可 Tab 到达
- Tab 顺序符合视觉顺序
- 焦点可见（outline 不设 none）
- Escape 关闭弹窗/菜单

## 屏幕阅读器
- 图片有 alt（装饰性 alt=""）
- 表单有 label 关联
- 动态内容变化用 aria-live
- 模态框有 aria-modal 和 aria-labelledby

## 颜色对比度
- 正文 ≥ 4.5:1
- 大号文字 ≥ 3:1
- 不要只靠颜色区分状态（加图标/文字）

## 测试清单
- [ ] 只用 Tab 键能完成所有操作
- [ ] 缩放至 200% 布局不破坏
- [ ] 高对比度模式可读
- [ ] 屏幕阅读器（NVDA / VoiceOver）测试核心流程
