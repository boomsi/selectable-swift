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

@end
