# 性能优化清单

## 渲染性能
- 避免不必要的 re-render：React.memo / useMemo / useCallback 用在确实需要的地方
- 列表渲染用虚拟滚动（react-window / react-virtualized）当数据 > 50 条
- 长计算放 useMemo 或 Web Worker

## Bundle 优化
- 代码分割：路由级 React.lazy + Suspense
- 移除未使用的依赖（depcheck）
- 图片用 WebP/AVIF，大图懒加载
- 首屏 CSS < 50KB（critical CSS 内联）

## 网络
- API 请求合并（GraphQL / BFF）
- 合理缓存（stale-while-revalidate）
- SSE/WebSocket 用于实时推送，不要轮询

## 常见坑
| 问题 | 解决方案 |
|------|----------|
| useEffect 无限循环 | 检查依赖数组，避免在 effect 内改变依赖 |
| setState 批量更新失效 | 用函数式 setState: setCount(c => c+1) |
| context 导致全局 re-render | 拆分 context，或用 useReducer + selector 模式 |
| 大列表卡顿 | 虚拟滚动 + 分页 |
