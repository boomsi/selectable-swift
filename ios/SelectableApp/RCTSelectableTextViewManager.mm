#import "RCTSelectableTextViewManager.h"
#import "RCTSelectableTextView.h"
#import "RCTSelectableTextShadowView.h"

#import <React/RCTUIManager.h>
#import <React/RCTUIManagerObserverCoordinator.h>
#import <React/RCTUIManagerUtils.h>
#import <React/RCTComponent.h>
#import <React/RCTTextShadowView.h>

@interface RCTSelectableTextViewManager () <RCTUIManagerObserver>

@end

@implementation RCTSelectableTextViewManager {
  NSHashTable<RCTTextShadowView *> *_shadowViews;
}

RCT_EXPORT_MODULE(SelectableText)

- (void)setBridge:(RCTBridge *)bridge
{
  [super setBridge:bridge];
  _shadowViews = [NSHashTable weakObjectsHashTable];

  [bridge.uiManager.observerCoordinator addObserver:self];

  [[NSNotificationCenter defaultCenter] addObserver:self
                                           selector:@selector(handleDidUpdateMultiplierNotification)
                                               name:@"RCTAccessibilityManagerDidUpdateMultiplierNotification"
                                             object:[bridge moduleForName:@"AccessibilityManager"
                                                        lazilyLoadIfNecessary:YES]];
}

- (UIView *)view
{
  return [[RCTSelectableTextView alloc] initWithFrame:CGRectZero];
}

- (RCTShadowView *)shadowView
{
  RCTSelectableTextShadowView *shadowView = [[RCTSelectableTextShadowView alloc] initWithBridge:self.bridge];
  shadowView.textAttributes.fontSizeMultiplier =
      [[[self.bridge moduleForName:@"AccessibilityManager"] valueForKey:@"multiplier"] floatValue];
  [_shadowViews addObject:shadowView];
  return shadowView;
}

#pragma mark - RCTUIManagerObserver

- (void)uiManagerWillPerformMounting:(__unused RCTUIManager *)uiManager
{
  for (RCTTextShadowView *shadowView in _shadowViews) {
    [shadowView uiManagerWillPerformMounting];
  }
}

#pragma mark - Font Size Multiplier

- (void)handleDidUpdateMultiplierNotification
{
  CGFloat fontSizeMultiplier =
      [[[self.bridge moduleForName:@"AccessibilityManager"] valueForKey:@"multiplier"] floatValue];

  NSHashTable<RCTTextShadowView *> *shadowViews = _shadowViews;
  RCTExecuteOnUIManagerQueue(^{
    for (RCTTextShadowView *shadowView in shadowViews) {
      shadowView.textAttributes.fontSizeMultiplier = fontSizeMultiplier;
      YGNodeMarkDirty(shadowView.yogaNode);
    }

    [self.bridge.uiManager setNeedsLayout];
  });
}

RCT_EXPORT_VIEW_PROPERTY(selectable, BOOL)
RCT_EXPORT_VIEW_PROPERTY(menuItems, NSArray)
RCT_EXPORT_VIEW_PROPERTY(showSystemMenuItems, BOOL)
RCT_EXPORT_VIEW_PROPERTY(clearSelectionOnMenuAction, BOOL)
RCT_EXPORT_VIEW_PROPERTY(onMenuAction, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(selectionMode, NSString)
RCT_EXPORT_VIEW_PROPERTY(onTextLongPress, RCTDirectEventBlock)

RCT_EXPORT_METHOD(selectRange : (nonnull NSNumber *)reactTag start : (NSInteger)start end : (NSInteger)end)
{
  [self.bridge.uiManager addUIBlock:^(__unused RCTUIManager *uiManager, NSDictionary<NSNumber *, UIView *> *viewRegistry) {
    RCTSelectableTextView *view = (RCTSelectableTextView *)viewRegistry[reactTag];

    // 只对 SelectableText 实例执行选区命令，避免错误 tag 影响其他原生视图。
    if (![view isKindOfClass:[RCTSelectableTextView class]]) {
      return;
    }

    [view selectTextRangeWithStart:start end:end];
  }];
}

RCT_EXPORT_METHOD(clearSelection : (nonnull NSNumber *)reactTag)
{
  [self.bridge.uiManager addUIBlock:^(__unused RCTUIManager *uiManager, NSDictionary<NSNumber *, UIView *> *viewRegistry) {
    RCTSelectableTextView *view = (RCTSelectableTextView *)viewRegistry[reactTag];

    // 只对 SelectableText 实例执行清理命令，避免错误 tag 影响其他原生视图。
    if (![view isKindOfClass:[RCTSelectableTextView class]]) {
      return;
    }

    [view clearTextSelection];
  }];
}

RCT_EXPORT_METHOD(copyRange : (nonnull NSNumber *)reactTag start : (NSInteger)start end : (NSInteger)end)
{
  [self.bridge.uiManager addUIBlock:^(__unused RCTUIManager *uiManager, NSDictionary<NSNumber *, UIView *> *viewRegistry) {
    RCTSelectableTextView *view = (RCTSelectableTextView *)viewRegistry[reactTag];

    // 只对 SelectableText 实例执行复制命令，避免错误 tag 影响其他原生视图。
    if (![view isKindOfClass:[RCTSelectableTextView class]]) {
      return;
    }

    [view copyTextRangeWithStart:start end:end];
  }];
}

@end
