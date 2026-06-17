#import "RCTSelectableTextView.h"

#import <React/RCTTextAttributes.h>
#import <React/UIView+React.h>

// 记录当前交互中的 SelectableText，用于切换文本块时清理上一个 UITextView 保留的 selectedRange。
static __weak RCTSelectableTextView *RCTActiveSelectableTextView = nil;

@interface RCTSelectableTextView () <UITextViewDelegate, UIEditMenuInteractionDelegate>

@end

@implementation RCTSelectableTextView {
  NSArray<UIView *> *_Nullable _descendantViews;
  BOOL _rnSelectable;
  UILongPressGestureRecognizer *_paragraphLongPressGestureRecognizer;
  UIEditMenuInteraction *_selectionEditMenuInteraction API_AVAILABLE(ios(16.0));
  // 递增的选区折叠检查标记，用于让新的选区变化取消旧的延迟恢复任务。
  NSUInteger _collapsedSelectionResetToken;
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

    // RN 菜单模式使用自定义长按，只负责命中文本并把段落信息回传给 JS。
    _paragraphLongPressGestureRecognizer = [[UILongPressGestureRecognizer alloc] initWithTarget:self
                                                                                         action:@selector(handleParagraphLongPress:)];
    _paragraphLongPressGestureRecognizer.cancelsTouchesInView = NO;
    [self addGestureRecognizer:_paragraphLongPressGestureRecognizer];

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
    // 默认使用 UITextView 原生选择模式，业务菜单模式需要显式开启。
    _selectionMode = @"default";
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

  // 业务菜单模式清空后回到不可直接选中的初始态，下一次仍需长按菜单触发。
  if ([self isMenuThenParagraphMode]) {
    [super setSelectable:NO];
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

// UIKit 自行把选区折叠为空时，把业务菜单模式恢复成不可直接双击选中的初始态。
- (void)textViewDidChangeSelection:(UITextView *)textView
{
  // 只在 RN 菜单后选段落模式处理，避免影响默认原生选择模式。
  if (![self isMenuThenParagraphMode]) {
    return;
  }

  // 有实际选区时取消之前的折叠恢复任务，避免拖动控制手柄时被旧任务打断。
  if (self.selectedRange.location != NSNotFound && self.selectedRange.length > 0) {
    _collapsedSelectionResetToken++;
    return;
  }

  [self scheduleCollapsedSelectionResetForMenuThenParagraphMode];
}

// 判断当前是否使用 RN 菜单后再选段落的业务模式。
- (BOOL)isMenuThenParagraphMode
{
  return [self.selectionMode isEqualToString:@"menuThenParagraph"];
}

// 延迟确认选区仍为空后再关闭 native selectable，避免和 UIKit 手柄拖动中的中间状态冲突。
- (void)scheduleCollapsedSelectionResetForMenuThenParagraphMode
{
  _collapsedSelectionResetToken++;
  NSUInteger resetToken = _collapsedSelectionResetToken;
  __weak RCTSelectableTextView *weakSelf = self;

  dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.2 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
    RCTSelectableTextView *strongSelf = weakSelf;

    // 视图已释放时不再执行延迟恢复。
    if (strongSelf == nil) {
      return;
    }

    // 模式已经切换时不再处理，避免影响 default 选择行为。
    if (![strongSelf isMenuThenParagraphMode]) {
      return;
    }

    // 期间发生过新的选区变化时放弃旧任务，避免覆盖用户正在调整的选区。
    if (resetToken != strongSelf->_collapsedSelectionResetToken) {
      return;
    }

    // 延迟后如果已经重新出现实际选区，说明用户仍在调整手柄，不关闭选择能力。
    if (strongSelf.selectedRange.location != NSNotFound && strongSelf.selectedRange.length > 0) {
      return;
    }

    [strongSelf clearTextSelection];
  });
}

// 根据 selectable 和 selectionMode 同步 UITextView 的真实可选状态。
- (void)updateNativeSelectableState
{
  [super setSelectable:[self isMenuThenParagraphMode] ? NO : _rnSelectable];
}

// 业务菜单模式下，长按只计算段落和菜单锚点，不让 UITextView 直接进入选区。
- (void)handleParagraphLongPress:(UILongPressGestureRecognizer *)gesture
{
  // 只处理 menuThenParagraph 模式的长按开始，避免干扰默认原生选择模式。
  if (![self isMenuThenParagraphMode] || gesture.state != UIGestureRecognizerStateBegan) {
    return;
  }

  [self markAsActiveSelectableTextView];
  [self clearTextSelection];

  CGPoint location = [gesture locationInView:self];
  NSRange paragraphRange = [self paragraphRangeAtPoint:location];

  // 没有命中文本时不弹业务菜单，避免空白区域误触发。
  if (paragraphRange.location == NSNotFound || paragraphRange.length == 0 || !self.onTextLongPress) {
    return;
  }

  NSString *paragraphText = [self.text substringWithRange:paragraphRange];
  CGPoint pagePoint = [self convertPoint:location toView:nil];

  self.onTextLongPress(@{
    @"paragraphText" : paragraphText,
    @"selectionStart" : @(paragraphRange.location),
    @"selectionEnd" : @(NSMaxRange(paragraphRange)),
    @"locationX" : @(location.x),
    @"locationY" : @(location.y),
    @"pageX" : @(pagePoint.x),
    @"pageY" : @(pagePoint.y),
  });
}

// 将长按坐标映射到字符 index，并按换行符切出所在段落 range。
- (NSRange)paragraphRangeAtPoint:(CGPoint)point
{
  NSString *text = self.text;

  // 文本为空时没有可选段落。
  if (text.length == 0) {
    return NSMakeRange(NSNotFound, 0);
  }

  NSLayoutManager *layoutManager = self.layoutManager;
  NSTextContainer *textContainer = self.textContainer;
  [layoutManager ensureLayoutForTextContainer:textContainer];

  CGPoint textContainerPoint = CGPointMake(point.x - self.textContainerInset.left + self.contentOffset.x,
                                           point.y - self.textContainerInset.top + self.contentOffset.y);
  CGFloat fraction = 0;
  NSUInteger charIndex = [layoutManager characterIndexForPoint:textContainerPoint
                                               inTextContainer:textContainer
                      fractionOfDistanceBetweenInsertionPoints:&fraction];

  // 点击在文本末尾之后时，按最后一个字符所在段落处理。
  if (charIndex >= text.length) {
    charIndex = text.length - 1;
  }

  NSCharacterSet *newlineSet = [NSCharacterSet newlineCharacterSet];
  NSInteger start = (NSInteger)charIndex;
  NSInteger end = (NSInteger)charIndex;

  // 向前找到当前段落的换行边界。
  while (start > 0) {
    unichar character = [text characterAtIndex:(NSUInteger)(start - 1)];
    if ([newlineSet characterIsMember:character]) {
      break;
    }
    start--;
  }

  // 向后找到当前段落的换行边界。
  while ((NSUInteger)end < text.length) {
    unichar character = [text characterAtIndex:(NSUInteger)end];
    if ([newlineSet characterIsMember:character]) {
      break;
    }
    end++;
  }

  // 长按命中空行时不返回段落，避免选中零长度内容。
  if (end <= start) {
    return NSMakeRange(NSNotFound, 0);
  }

  return NSMakeRange((NSUInteger)start, (NSUInteger)(end - start));
}

// JS 点击“选取文本”后调用，临时开启 UITextView 选择能力并选中指定段落。
- (void)selectTextRangeWithStart:(NSInteger)start end:(NSInteger)end
{
  NSRange range = [self clampedRangeWithStart:start end:end];

  // range 无效时不改变当前选区。
  if (range.location == NSNotFound || range.length == 0) {
    return;
  }

  [self markAsActiveSelectableTextView];
  [super setSelectable:YES];
  [self becomeFirstResponder];
  self.selectedRange = range;
  [self scrollRangeToVisible:range];
  [self showEditMenuForSelectedRange];
}

// 程序化设置 selectedRange 后，主动显示系统选中文本菜单。
- (void)showEditMenuForSelectedRange
{
  UITextRange *selectedTextRange = self.selectedTextRange;

  // 没有有效 selectedTextRange 时无法计算菜单锚点。
  if (selectedTextRange == nil || selectedTextRange.empty) {
    return;
  }

  CGRect targetRect = [self firstRectForRange:selectedTextRange];

  // firstRectForRange 可能返回无效 rect，无法定位菜单时不展示。
  if (CGRectIsNull(targetRect) || CGRectIsEmpty(targetRect) || CGRectIsInfinite(targetRect)) {
    return;
  }

  if (@available(iOS 16.0, *)) {
    if (_selectionEditMenuInteraction == nil) {
      _selectionEditMenuInteraction = [[UIEditMenuInteraction alloc] initWithDelegate:self];
      [self addInteraction:_selectionEditMenuInteraction];
    }
    UIEditMenuConfiguration *configuration =
        [UIEditMenuConfiguration configurationWithIdentifier:nil sourcePoint:CGPointMake(CGRectGetMidX(targetRect), CGRectGetMinY(targetRect))];
    [_selectionEditMenuInteraction presentEditMenuWithConfiguration:configuration];
    return;
  }

  UIMenuController *menuController = [UIMenuController sharedMenuController];
  [menuController showMenuFromView:self rect:targetRect];
}

// JS 点击“复制”后调用，复制指定段落到系统剪贴板。
- (void)copyTextRangeWithStart:(NSInteger)start end:(NSInteger)end
{
  NSRange range = [self clampedRangeWithStart:start end:end];

  // range 无效时不写剪贴板。
  if (range.location == NSNotFound || range.length == 0) {
    return;
  }

  [UIPasteboard generalPasteboard].string = [self.text substringWithRange:range];
}

// 将 JS 传入的 start/end 裁剪到当前文本范围内，避免越界访问。
- (NSRange)clampedRangeWithStart:(NSInteger)start end:(NSInteger)end
{
  NSUInteger textLength = self.text.length;

  // 起止位置非法或反向时返回无效 range。
  if (start < 0 || end <= start || textLength == 0) {
    return NSMakeRange(NSNotFound, 0);
  }

  NSUInteger clampedStart = MIN((NSUInteger)start, textLength);
  NSUInteger clampedEnd = MIN((NSUInteger)end, textLength);

  // 裁剪后没有实际长度时返回无效 range。
  if (clampedEnd <= clampedStart) {
    return NSMakeRange(NSNotFound, 0);
  }

  return NSMakeRange(clampedStart, clampedEnd - clampedStart);
}

#pragma mark - Custom Edit Menu

// 构建 iOS 16+ 文本选中菜单，根据 showSystemMenuItems 决定是否保留系统 suggestedActions。
- (UIMenu *)textView:(UITextView *)textView
    editMenuForTextInRange:(NSRange)range
          suggestedActions:(NSArray<UIMenuElement *> *)suggestedActions API_AVAILABLE(ios(16.0))
{
  return [self menuWithSuggestedActions:suggestedActions selectedRange:range];
}

// 程序化展示 UIEditMenuInteraction 时也复用同一套自定义菜单逻辑。
- (UIMenu *)editMenuInteraction:(UIEditMenuInteraction *)interaction
           menuForConfiguration:(UIEditMenuConfiguration *)configuration
               suggestedActions:(NSArray<UIMenuElement *> *)suggestedActions API_AVAILABLE(ios(16.0))
{
  return [self menuWithSuggestedActions:suggestedActions selectedRange:self.selectedRange];
}

// 统一组装系统菜单项和 JS 传入的自定义菜单项，避免不同入口菜单表现不一致。
- (UIMenu *)menuWithSuggestedActions:(NSArray<UIMenuElement *> *)suggestedActions
                       selectedRange:(NSRange)selectedRange API_AVAILABLE(ios(16.0))
{
  NSMutableArray<UIMenuElement *> *children =
      self.showSystemMenuItems ? [suggestedActions mutableCopy] : [NSMutableArray new];

  // 如果系统菜单项被保留但 UIKit 未提供 suggestedActions，则从空数组开始构建菜单。
  if (children == nil) {
    children = [NSMutableArray new];
  }

  NSArray<UIMenuElement *> *customActions = [self customMenuActionsForSelectedRange:selectedRange];

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
  [self updateNativeSelectableState];
}

// 返回 RN 记录的 selectable 状态，和 UITextView 的 isSelectable getter 保持一致。
- (BOOL)isSelectable
{
  return _rnSelectable;
}

- (void)setSelectionMode:(NSString *)selectionMode
{
  // selectionMode 为空时回到默认原生选择模式。
  _selectionMode = selectionMode ?: @"default";
  [self clearTextSelection];
  [self updateNativeSelectableState];
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
