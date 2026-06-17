# SelectableText 组件实现 TODO

## 概述
创建一个基于 UITextView 的 RN （旧架构）原生组件 SelectableText，实现 iOS 原生级别的文本选取体验（高亮 + 手柄 + 系统菜单）。

---

## 步骤

### 1. 创建 RCTSelectableTextView（原生视图）
- 创建 `ios/SelectableApp/RCTSelectableTextView.h`
- 创建 `ios/SelectableApp/RCTSelectableTextView.mm`
- UITextView 子类，配置：editable=NO, selectable=YES, scrollEnabled=NO
- 配置布局一致性：textContainerInset=UIEdgeInsetsZero, lineFragmentPadding=0, usesFontLeading=NO
- 实现 `setTextStorage:contentFrame:descendantViews:` 方法（与 RCTTextView 同名，内部转给 UITextView 的 attributedText）
- 处理 descendantViews（内嵌非文本 View 的添加/移除）
- 禁用 UITextView 的内置输入手势（不需要键盘输入）

### 2. 创建 RCTSelectableTextShadowView（Shadow 视图）
- 创建 `ios/SelectableApp/RCTSelectableTextShadowView.h`
- 创建 `ios/SelectableApp/RCTSelectableTextShadowView.mm`
- 继承 RCTTextShadowView，复用全部测量逻辑
- 重写 `uiManagerWillPerformMounting`，将 NSTextStorage 传给 RCTSelectableTextView 而非 RCTTextView

### 3. 创建 RCTSelectableTextViewManager（ViewManager）
- 创建 `ios/SelectableApp/RCTSelectableTextViewManager.h`
- 创建 `ios/SelectableApp/RCTSelectableTextViewManager.mm`
- 继承 RCTBaseTextViewManager
- 导出模块名 SelectableText
- 实现 `view` 方法返回 RCTSelectableTextView
- 实现 `shadowView` 方法返回 RCTSelectableTextShadowView
- 导出 selectable 属性（RCT_EXPORT_VIEW_PROPERTY）

### 4. 注册原生模块到 Xcode 项目
- 将新建的 .h/.mm 文件添加到 SelectableApp.xcodeproj
- 确保 React/RCTText 等头文件搜索路径可用
- 在 Podfile 中确认头文件引用链正确

### 5. 创建 JS 组件 SelectableText
- 创建 `src/components/SelectableText.tsx`
- requireNativeComponent 引用 'SelectableText'
- 支持 children（嵌套 Text 子节点）
- 支持 selectable prop
- 支持基础文本样式 props（通过 RN 现有 Text 样式系统）
- 类型定义

### 6. 实现手势冲突处理
- 处理 UITextView 和 ScrollView 的手势冲突
- 重写 gestureRecognizerShouldBegin 精细控制手势
- 处理 canCancelContentTouches 配置
- 确保：长按选取不被 ScrollView 的滚动手势截断
- 确保：选取手柄拖动不被 ScrollView 的 pan 截断

### 7. 更新 MarkdownPage.tsx 使用 SelectableText
- 将段落级 Text 替换为 SelectableText
- 标题级仍使用 Text（不需要选取）
- 嵌套 Text 子节点保持不变（粗体/斜体）

### 8. 构建并测试
- pod install
- Xcode 构建
- 在设备/模拟器上运行验证
- 验证：长按 → 选中高亮出现
- 验证：选取手柄出现且可拖动
- 验证：系统菜单包含复制/全选/选择
- 验证：ScrollView 内长按选取正常
- 验证：嵌套 Text 样式（粗体/斜体）正确渲染
- 验证：父容器自动撑开

### 9. 清理旧 patch
- 删除 patches/react-native+0.79.2.patch（不再需要 patch RCTTextView）
- 移除 patch-package 相关配置（如果不再有其他 patch）

### 10. 修复发现的问题
- 根据测试结果修复布局差异、手势冲突、渲染不一致等问题

### 11. review 逻辑是否有问题
- review 代码实现，确保逻辑清晰、注释充分、命名合理
- 能够满足 rn Yoga 自动识别出组件尺寸，自动撑开
- 能否满足在选择模式下，能够正确触发选中文本高亮，两侧有控制手柄可以控制选取大小，系统菜单（复制/全选/选择）等功能

### 12. 其他优化
- 自定义菜单
- 点击后自动关闭选中