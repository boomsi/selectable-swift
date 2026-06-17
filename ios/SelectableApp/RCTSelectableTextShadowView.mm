#import "RCTSelectableTextShadowView.h"
#import "RCTSelectableTextView.h"

#import <React/RCTUIManager.h>
#import <React/RCTBaseTextShadowView.h>
#import <React/RCTRawTextShadowView.h>
#import <React/RCTVirtualTextShadowView.h>

@implementation RCTSelectableTextShadowView

- (void)uiManagerWillPerformMounting
{
  // 调试：打印子 shadow view 类型和数量
  NSLog(@"[SelectableText] uiManagerWillPerformMounting: reactSubviews.count=%lu, tag=%@",
        (unsigned long)self.reactSubviews.count, self.reactTag);
  for (RCTShadowView *child in self.reactSubviews) {
    NSLog(@"[SelectableText]   child class=%@, tag=%@", NSStringFromClass([child class]), child.reactTag);
  }

  // 调试：打印收集到的 attributedText
  NSAttributedString *attrText = [self attributedTextWithBaseTextAttributes:nil];
  NSLog(@"[SelectableText]   attributedText.length=%lu, string='%@'",
        (unsigned long)attrText.length, attrText.string);

  [super uiManagerWillPerformMounting];
}

@end
