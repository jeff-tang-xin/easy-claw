# 组件设计原则

## 单一职责
每个组件只做一件事。如果一个组件既要渲染又要管理业务状态还要处理路由，就该拆分。

## 组合优于继承
React 没有继承的概念，用组合。通过 children、render props、HOC 复用逻辑。

## Props 设计
- 扁平化：避免 `props.data.user.profile`
- 默认值：用 destructuring default
- 类型安全：TypeScript interface 定义 props

## 受控 vs 非受控
- 表单组件优先受控（value + onChange）
- 只有纯展示组件才非受控
- 混合使用时明确隔离：内部 state 只管 UI 状态（collapsed, focused）

## 反模式
| 反模式 | 修正 |
|--------|------|
| 在组件内直接 fetch | 提到上层或用 React Query |
| useEffect 依赖写 [] 但用了外部变量 | 补齐依赖，或用 useRef |
| 用 index 当 key | 用稳定 id |
| 大组件文件（>300 行） | 拆分 |
