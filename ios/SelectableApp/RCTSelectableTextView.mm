#import "RCTSelectableTextView.h"

#import <React/RCTTextAttributes.h>
#import <React/UIView+React.h>

// 记录当前交互中的 SelectableText，用于切换文本块时清理上一个 UITextView 保留的 selectedRange。
static __weak RCTSelectableTextView *RCTActiveSelectableTextView = nil;

@interface RCTSelectableTextView () <UITextViewDelegate>

@end

@implementation RCTSelectableTextView {
  NSArray<UIView *> *_Nullable _descendantViews;
  BOOL _rnSelectable;
}

- (instancetype)initWithFrame:(CGRect)frame
{
  if (self = [super initWithFrame:frame]) {
    // 只读可选取配置
    self.editable = NO;
    [super setSelectable:YES];
    self.scrollEnabled = NO;
    self.delegate = self;
    self.dataDetectorTypes = UIDataDetectorTypeNone;

    // 布局一致性：消除 UITextView 默认的内边距
    self.textContainerInset = UIEdgeInsetsZero;
    self.textContainer.lineFragmentPadding = 0;

    // DEBUG: 临时浅灰色背景，验证视图是否被创建且有尺寸
    self.backgroundColor = [UIColor colorWithRed:0.9 green:0.9 blue:0.95 alpha:1.0];
    self.opaque = NO;

    // 禁用键盘输入相关特性
    self.inputView = [[UIView alloc] initWithFrame:CGRectZero];

    // accessibility
    self.isAccessibilityElement = YES;
    self.accessibilityTraits |= UIAccessibilityTraitStaticText;

    _rnSelectable = YES;
    // 默认保留系统复制/全选等菜单项，调用方可通过 showSystemMenuItems 关闭。
    _showSystemMenuItems = YES;
    // 默认菜单点击后保留选区，调用方可通过 clearSelectionOnMenuAction 开启自动清空。
    _clearSelectionOnMenuAction = NO;
  }
  return self;
}

#pragma mark - Selection State

// 当前 SelectableText 开始交互时，清理上一个实例残留的选区，避免再次点击旧位置恢复旧选区。
- (void)markAsActiveSelectableTextView
{
  RCTSelectableTextView *previousActiveTextView = RCTActiveSelectableTextView;

  // 只有切换到另一个实例时才清理，避免影响当前实例内的长按和手柄拖动。
  if (previousActiveTextView != nil && previousActiveTextView != self) {
    [previousActiveTextView clearTextSelection];
  }

  RCTActiveSelectableTextView = self;
}

// 清空 UITextView 内部保留的 selectedRange，防止失焦后再次触摸时恢复旧选区。
- (void)clearTextSelection
{
  // 只有存在实际选区时才清空，避免不必要地改变普通点击的插入点状态。
  if (self.selectedRange.location != NSNotFound && self.selectedRange.length > 0) {
    self.selectedRange = NSMakeRange(0, 0);
  }
}

- (void)touchesBegan:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event
{
  [self markAsActiveSelectableTextView];
  [super touchesBegan:touches withEvent:event];
}

- (BOOL)becomeFirstResponder
{
  [self markAsActiveSelectableTextView];
  return [super becomeFirstResponder];
}

#pragma mark - Custom Edit Menu

// 构建 iOS 16+ 文本选中菜单，根据 showSystemMenuItems 决定是否保留系统 suggestedActions。
- (UIMenu *)textView:(UITextView *)textView
    editMenuForTextInRange:(NSRange)range
          suggestedActions:(NSArray<UIMenuElement *> *)suggestedActions API_AVAILABLE(ios(16.0))
{
  NSMutableArray<UIMenuElement *> *children =
      self.showSystemMenuItems ? [suggestedActions mutableCopy] : [NSMutableArray new];

  // 如果系统菜单项被保留但 UIKit 未提供 suggestedActions，则从空数组开始构建菜单。
  if (children == nil) {
    children = [NSMutableArray new];
  }

  NSArray<UIMenuElement *> *customActions = [self customMenuActionsForSelectedRange:range];

  // 如果 JS 传入了可用菜单项，则把自定义项追加到当前菜单列表后面。
  if (customActions.count > 0) {
    [children addObjectsFromArray:customActions];
  }

  return [UIMenu menuWithChildren:children];
}

// 将 JS menuItems 转成 UIKit 的 UIAction，点击时回传当前选中文本和范围。
- (NSArray<UIMenuElement *> *)customMenuActionsForSelectedRange:(NSRange)range API_AVAILABLE(ios(16.0))
{
  NSMutableArray<UIMenuElement *> *actions = [NSMutableArray new];
  __weak RCTSelectableTextView *weakSelf = self;

  for (NSDictionary *item in self.menuItems) {
    NSString *itemId = [item[@"id"] isKindOfClass:[NSString class]] ? item[@"id"] : nil;
    NSString *title = [item[@"title"] isKindOfClass:[NSString class]] ? item[@"title"] : nil;

    // 跳过缺少 id 或 title 的菜单项，避免 UIKit 创建不可识别的 action。
    if (itemId.length == 0 || title.length == 0) {
      continue;
    }

    UIAction *action = [UIAction actionWithTitle:title
                                           image:nil
                                      identifier:itemId
                                         handler:^(__unused UIAction *selectedAction) {
                                           // 点击菜单时读取最新选区，保证拖动控制手柄后的范围能正确回传。
                                           [weakSelf handleCustomMenuItem:item selectedRange:range];
                                         }];
    [actions addObject:action];
  }

  return actions;
}

// 处理自定义菜单点击，把 action id、标题、选中文本和选区范围发送给 JS。
- (void)handleCustomMenuItem:(NSDictionary *)item selectedRange:(NSRange)fallbackRange
{
  // 如果 JS 没有监听 onMenuAction，则只关闭原生菜单动作，不执行额外业务。
  if (!self.onMenuAction) {
    return;
  }

  NSRange selectedRange = [self validSelectedRangeWithFallbackRange:fallbackRange];

  // 如果当前选区无效，则不向 JS 发送没有文本上下文的菜单事件。
  if (selectedRange.location == NSNotFound || selectedRange.length == 0) {
    return;
  }

  NSString *selectedText = [self.text substringWithRange:selectedRange];
  NSString *itemId = [item[@"id"] isKindOfClass:[NSString class]] ? item[@"id"] : @"";
  NSString *title = [item[@"title"] isKindOfClass:[NSString class]] ? item[@"title"] : @"";

  self.onMenuAction(@{
    @"id" : itemId,
    @"title" : title,
    @"selectedText" : selectedText,
    @"selectionStart" : @(selectedRange.location),
    @"selectionEnd" : @(NSMaxRange(selectedRange)),
  });

  // 菜单回调发给 JS 后再按需清空，确保业务侧能拿到点击时的最终选区。
  if (self.clearSelectionOnMenuAction) {
    [self clearTextSelection];
  }
}

// 取得点击菜单瞬间的有效选区，当前 selectedRange 不可用时使用菜单创建时的范围。
- (NSRange)validSelectedRangeWithFallbackRange:(NSRange)fallbackRange
{
  NSRange selectedRange = self.selectedRange;
  NSUInteger textLength = self.text.length;

  // 如果当前 selectedRange 已经越界，则使用 UIKit 生成菜单时提供的 range。
  if (selectedRange.location == NSNotFound || NSMaxRange(selectedRange) > textLength || selectedRange.length == 0) {
    selectedRange = fallbackRange;
  }

  // 如果 fallbackRange 也不可用，则返回 NSNotFound 表示没有可执行的选区。
  if (selectedRange.location == NSNotFound || NSMaxRange(selectedRange) > textLength || selectedRange.length == 0) {
    return NSMakeRange(NSNotFound, 0);
  }

  return selectedRange;
}

- (void)setSelectable:(BOOL)selectable
{
  if (_rnSelectable == selectable) {
    return;
  }
  _rnSelectable = selectable;
  [super setSelectable:selectable];
}

// 返回 RN 记录的 selectable 状态，和 UITextView 的 isSelectable getter 保持一致。
- (BOOL)isSelectable
{
  return _rnSelectable;
}

- (void)setTextStorage:(NSTextStorage *)textStorage
          contentFrame:(CGRect)contentFrame
       descendantViews:(NSArray<UIView *> *)descendantViews
{
  NSLog(@"[SelectableTextView] setTextStorage: text='%@', contentFrame=%@, descendants=%lu, self.frame=%@",
        textStorage.string, NSStringFromCGRect(contentFrame),
        (unsigned long)descendantViews.count, NSStringFromCGRect(self.frame));

  // 移除旧的 descendant views
  for (UIView *view in _descendantViews) {
    [view removeFromSuperview];
  }

  _descendantViews = descendantViews;

  // 用 attributedText 设置，UITextView 会自行构建内部 TextKit 管线
  self.attributedText = textStorage;

  // 添加 descendant views（内嵌的非文本子 View）
  for (UIView *view in descendantViews) {
    [self addSubview:view];
  }

  // 确保 layoutManager 的 usesFontLeading 与 ShadowView 测量一致
  self.layoutManager.usesFontLeading = NO;

  NSLog(@"[SelectableTextView] after set: attributedText='%@', self.attributedText.length=%lu",
        self.attributedText.string, (unsigned long)self.attributedText.length);
}

- (void)reactSetFrame:(CGRect)frame
{
  NSLog(@"[SelectableTextView] reactSetFrame: %@ → %@", NSStringFromCGRect(self.frame), NSStringFromCGRect(frame));
  [UIView performWithoutAnimation:^{
    [super reactSetFrame:frame];
  }];
}

#pragma mark - React 子视图管理

- (void)didUpdateReactSubviews
{
  // 不做处理，子视图由 setTextStorage 方法管理
}

#pragma mark - Accessibility

- (NSString *)accessibilityLabel
{
  NSString *superLabel = [super accessibilityLabel];
  if (superLabel.length > 0) {
    return superLabel;
  }
  return self.text;
}

@end
