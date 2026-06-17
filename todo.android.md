# SelectableText Android 组件实现 TODO

## 概述
创建一个基于 Android TextView / ReactTextView 的 RN（旧架构）原生组件 SelectableText，实现 Android 原生级别的文本选取体验（高亮 + 手柄 + ActionMode 菜单），并尽量复用 RN Text 的 Spannable 构建和 Yoga 测量链路。

## 步骤

### 1. 创建 SelectableTextView（原生视图）
- 创建 `android/app/src/main/java/com/selectableapp/selectabletext/SelectableTextView.kt`
- 优先继承 RN 的 `ReactTextView`，复用 RN TextView 的文本渲染、Spannable 展示、点击定位等能力
- 配置：`setTextIsSelectable(true)`
- 保持只读文本行为，不引入 EditText 输入能力
- 处理 Android 文本选择高亮、选择手柄、系统 ActionMode 菜单
- 禁止 SelectableText 内嵌 View，避免 inline view/span 被当作不可拆分对象处理

### 2. 创建 SelectableTextShadowNode（Shadow 节点）
- 创建 `android/app/src/main/java/com/selectableapp/selectabletext/SelectableTextShadowNode.kt`
- 优先继承 RN 的 `ReactTextShadowNode`，复用 RN Text 的 children 解析、Spannable 构建、文字测量逻辑
- 确保嵌套 Text 样式（粗体/斜体/颜色/字号）仍由 RN Text 系统生成 Spannable
- 确保 Yoga 能自动识别文本尺寸，父容器能自动撑开
- 避免自己重新实现 markdown/rich text 到 Spannable 的完整转换逻辑，除非 RN Text 链路无法满足

### 3. 创建 SelectableTextViewManager（ViewManager）
- 创建 `android/app/src/main/java/com/selectableapp/selectabletext/SelectableTextViewManager.kt`
- 优先继承或参考 RN 的 `ReactTextViewManager`
- 导出模块名 `SelectableText`
- 实现 `createViewInstance` 返回 `SelectableTextView`
- 实现 `createShadowNodeInstance` 返回 `SelectableTextShadowNode`
- 导出 `selectable` prop
- 导出 `menuItems` prop
- 导出 `showSystemMenuItems` prop
- 导出 `clearSelectionOnMenuAction` prop
- 导出 `onMenuAction` 事件

### 4. 注册 Android 原生模块
- 创建 `android/app/src/main/java/com/selectableapp/selectabletext/SelectableTextPackage.kt`
- 在 `MainApplication.kt` 的 `getPackages()` 中手动添加 `SelectableTextPackage()`
- 确保 Kotlin 包名和 RN component name 与 JS 侧一致
- 确认旧架构下 ViewManager 能被 `requireNativeComponent('SelectableText')` 找到

### 5. 更新 JS 组件 SelectableText
- 更新 `src/components/SelectableText.tsx`
- Android 也通过 `requireNativeComponent('SelectableText')` 使用原生组件，不再 fallback 到普通 `Text`
- 保持 iOS / Android 共用 JS API：
  - `selectable`
  - `menuItems`
  - `showSystemMenuItems`
  - `clearSelectionOnMenuAction`
  - `onMenuAction`
- 继续使用 `TextAncestor.Provider`，确保子 `<Text>` 尽量进入 RN Text 的虚拟文本链路
- 保留 View 子节点检查：SelectableText 内只允许文本子树

### 6. 实现自定义选中文本菜单
- 在 `SelectableTextView` 中使用 `setCustomSelectionActionModeCallback`
- 根据 `showSystemMenuItems` 决定是否保留系统菜单项（复制/全选/分享等）
- 根据 `menuItems` 动态添加自定义 ActionMode 菜单项
- 点击自定义菜单项时读取当前选区：
  - `Selection.getSelectionStart(text)`
  - `Selection.getSelectionEnd(text)`
  - `text.subSequence(start, end)`
- 通过 `onMenuAction` 回调 JS：
  - `id`
  - `title`
  - `selectedText`
  - `selectionStart`
  - `selectionEnd`
- 如果 `clearSelectionOnMenuAction=true`，在回调 JS 后清空当前选区

### 7. 实现多 SelectableText 之间的选区切换
- 在 Android 原生层维护当前 active 的 `SelectableTextView` weak reference
- 新的 `SelectableTextView` 开始触摸或进入选择交互时，清空上一个实例残留选区
- 确保：从第一个 SelectableText 选中文本，点击第二个，再回到第一个时，不恢复旧选区
- 确保：同一个 SelectableText 内拖动选择手柄不被误判为切换实例

### 8. 实现 ScrollView 手势冲突处理
- 验证 Android TextView 选择手势和 RN ScrollView 的冲突情况
- 确保普通上下滑动仍能触发页面滚动
- 确保长按选中文本不被 ScrollView 截断
- 确保选择手柄拖动不被父 ScrollView 抢走
- 如有必要，在 `SelectableTextView` 的 touch 处理里按状态调用 `requestDisallowInterceptTouchEvent`

### 9. 更新 MarkdownPage.tsx 使用 Android SelectableText
- 保持段落级 Text 使用 SelectableText
- 标题级仍使用 Text（不需要选取）
- 嵌套 Text 子节点保持不变（粗体/斜体）
- 确认 Android 和 iOS 使用同一套 props
- 确认 `ScrollView` 内短文本宽度由内容撑开，长文本最多占满可用宽度并换行

### 10. 构建并测试
- 执行 `./gradlew :app:compileDebugKotlin`
- 执行 `./gradlew :app:assembleDebug`
- 在 Android 模拟器/真机上运行验证
- 验证：长按 → 选中高亮出现
- 验证：选取手柄出现且可拖动
- 验证：系统菜单可按 `showSystemMenuItems` 显示/隐藏
- 验证：自定义菜单项显示正确
- 验证：点击自定义菜单能回调 JS，并返回正确 selectedText 和 selection range
- 验证：`clearSelectionOnMenuAction` 能控制菜单点击后是否清空选区
- 验证：多个 SelectableText 切换时不会恢复旧选区
- 验证：ScrollView 内长按选取和上下滑动都正常
- 验证：嵌套 Text 样式（粗体/斜体）正确渲染
- 验证：父容器自动撑开

### 11. Review 逻辑是否有问题
- Review Android 代码实现，确保逻辑清晰、注释充分、命名合理
- 确认是否真正复用 RN Text 的 Spannable 和测量链路
- 确认是否存在 RN 版本升级风险，尤其是继承/调用 RN 内部 Text 类的部分
- 确认 Android 与 iOS 的 JS API 行为一致
- 确认选择模式下能正确触发文本高亮、控制手柄、ActionMode 菜单、自定义菜单
- 确认 SelectableText 内禁止 View 的限制在 Android 也有效

### 12. 其他优化
- 支持更多自定义菜单配置（是否禁用、排序、分组）
- 支持点击菜单后自动关闭选中
- 支持长按后先展示自定义菜单，再由菜单触发选中
- 支持 Android 不同系统版本的菜单行为差异处理

--- 

### 13. 间接选中
- 长按出现自定义菜单，点击菜单项后再选中文本
- 评估 Android TextView 是否允许先展示菜单再进入 selection ActionMode
- 如果系统能力不稳定，优先保持原生长按选中流程，再追加自定义菜单项
