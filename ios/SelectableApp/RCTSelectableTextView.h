#import <UIKit/UIKit.h>

#import <React/RCTComponent.h>

NS_ASSUME_NONNULL_BEGIN

@interface RCTSelectableTextView : UITextView

@property (nonatomic, assign, getter=isSelectable) BOOL selectable;
@property (nonatomic, copy, nullable) NSArray<NSDictionary *> *menuItems;
@property (nonatomic, assign) BOOL showSystemMenuItems;
@property (nonatomic, assign) BOOL clearSelectionOnMenuAction;
@property (nonatomic, copy, nullable) RCTDirectEventBlock onMenuAction;
@property (nonatomic, copy, nullable) NSString *selectionMode;
@property (nonatomic, copy, nullable) RCTDirectEventBlock onTextLongPress;

- (void)setTextStorage:(NSTextStorage *)textStorage
          contentFrame:(CGRect)contentFrame
       descendantViews:(NSArray<UIView *> *)descendantViews;

- (void)selectTextRangeWithStart:(NSInteger)start end:(NSInteger)end;
- (void)clearTextSelection;
- (void)copyTextRangeWithStart:(NSInteger)start end:(NSInteger)end;

@end

NS_ASSUME_NONNULL_END
