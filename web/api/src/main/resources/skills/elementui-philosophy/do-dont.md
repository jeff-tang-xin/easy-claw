# Element UI 正反例对照（UI 审查清单）

> 用法：实现或评审 UI 时逐条对照。✅ 是期望做法，❌ 是常见反模式。
> 与 frontend-quality 的五维审查互补——本清单管「设计正确性」。

## 1. 按钮与操作

- ✅ 每个视图区域只有 1 个 plain=false 的 primary；次要操作用 default/plain
- ❌ 一排多个纯色 primary；「重置」用 primary
- ✅ 删除用 danger（或 danger plain）+ el-popconfirm / MessageBox 二次确认
- ❌ 删除直接生效；danger 色当普通按钮用
- ✅ 提交按钮 loading 防重复；异步按钮统一 loading 态
- ❌ 慢网络下连点创建多条
- ✅ 表格操作列 ≤ 3 个，更多收进 el-dropdown
- ❌ 操作列 7 个链接挤成两行

## 2. 表单

- ✅ label-width 全页统一、右对齐带冒号；rules 必填红星自动
- ❌ label 宽度随手写导致输入框起点参差；有的有冒号有的没有
- ✅ 校验 blur + change + 提交全量；错误文案说「怎么改」
- ❌ 只在提交时校验；文案只写「输入有误」
- ✅ 弹窗表单关闭时 resetFields 清校验残留
- ❌ 关闭再打开，上次的红色错误还在
- ✅ 数字用 el-input-number（有步进和边界）；金额保留两位
- ❌ 用 text input 让用户手输数字，格式五花八门

## 3. 表格

- ✅ 数值列右对齐、状态列语义色 el-tag（light 主题）、时间统一 `YYYY-MM-DD HH:mm:ss`
- ❌ 金额左对齐；状态纯文本无颜色
- ✅ 列多时 fixed 左右关键列；show-overflow-tooltip 处理长文本
- ❌ 长文本把行撑到三行高；横向滚动找不到操作列
- ✅ 空表格 el-empty（table 自带 empty-text）；加载中 v-loading
- ❌ 空数据与加载失败混为一谈
- ✅ stripe 斑马纹与 hover 底色二选一风格，全站统一
- ❌ 有的表有斑马纹有的没有，hover 色也不一致

## 4. 反馈与提示

- ✅ 分级：Message（轻，3s）/ Notification（重要，需关闭）/ MessageBox（需决策）/ el-alert（页面内常驻）
- ❌ 用 MessageBox 提示「保存成功」；把堆栈塞进 Message
- ✅ 错误提示给下一步（「重试」「检查网络」）
- ❌ 只弹「操作失败」
- ✅ 确认框写清后果与对象（「删除订单 #1234 后不可恢复」）
- ❌ 只写「确定删除吗？」

## 5. 色彩与视觉

- ✅ 语义色专用：success 绿/warning 黄/danger 红/info 灰，不挪作装饰
- ❌ danger 红做「热门」标签；success 绿做普通按钮
- ✅ 文字四级实色（#303133/#606266/#909399/#c0c4cc）按语义取用；正文 ≥ #606266
- ❌ 正文用 #909399（对比度 3.0:1 不达标）；占位色当正文色
- ✅ 边框按四级取用：控件 #dcdfe6、卡片 #e4e7ed、表格线 #ebeef5
- ❌ 全部用 #dcdfe6 一刀切，页面线条感生硬
- ✅ 圆角统一 4px（控件）；阴影只用 base/light 两档
- ❌ 圆角 4/8/12 混用；每张卡片重阴影

## 6. 布局与间距

- ✅ 栅格 el-row/el-col + gutter 20；内容区 padding 20px
- ❌ 手写 margin 模拟栅格；间距 13/17/21 随手写
- ✅ 同组间距 < 组间间距；表单项间距统一（el-form-item 自带）
- ❌ 相关字段离得比无关字段远
- ✅ 空态/加载态/错误态三态齐全
- ❌ 只做成功态

## 7. 动效与交互细节

- ✅ 过渡 0.3s；hover/active/focus/disabled 四态齐全；尊重 prefers-reduced-motion
- ❌ 无 hover 反馈的「按钮」；focus 无边框变化
- ✅ 键盘可达：Tab 遍历、dialog 开启焦点入内、ESC 关闭
- ❌ 自绘下拉只能鼠标操作
- ✅ 大数据表格分页（后端分页优先）
- ❌ 一次渲染 3000 行卡死（frontend-quality 性能红线）

## 8. 文案

- ✅ 按钮动词开头（「新增用户」「导出 Excel」）；Message 说结果（「新增成功」）
- ❌ 按钮写「确定」执行删除；提示只写「失败」
- ✅ 术语全站统一（「用户」不混「账号/会员」）；时间格式统一
- ❌ 同一概念三种叫法、时间格式三种写法
