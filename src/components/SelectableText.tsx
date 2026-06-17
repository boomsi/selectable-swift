import React from 'react';
import {
  NativeSyntheticEvent,
  requireNativeComponent,
  StyleProp,
  Text,
  TextStyle,
  View,
  Platform,
} from 'react-native';
import TextAncestor from 'react-native/Libraries/Text/TextAncestor';

interface SelectableTextMenuItem {
  id: string;
  title: string;
}

interface SelectableTextMenuActionEvent {
  id: string;
  title: string;
  selectedText: string;
  selectionStart: number;
  selectionEnd: number;
}

interface NativeSelectableTextProps {
  selectable?: boolean;
  style?: StyleProp<TextStyle>;
  children?: React.ReactNode;
  menuItems?: SelectableTextMenuItem[];
  showSystemMenuItems?: boolean;
  clearSelectionOnMenuAction?: boolean;
  onMenuAction?: (
    event: NativeSyntheticEvent<SelectableTextMenuActionEvent>,
  ) => void;
}

// 只在 iOS 上使用原生 SelectableText。
const NativeSelectableText =
  Platform.OS === 'ios'
    ? requireNativeComponent<NativeSelectableTextProps>('SelectableText')
    : null;

// 检查 SelectableText 子树里是否包含 RN View，避免 View 作为 NSTextAttachment 被整体选中。
function containsUnsupportedViewChild(children: React.ReactNode): boolean {
  let hasUnsupportedView = false;

  React.Children.forEach(children, child => {
    // 已经找到 View 时跳过后续检查，避免重复遍历。
    if (hasUnsupportedView) {
      return;
    }

    // 空节点和布尔节点不会渲染成文本内容，不需要继续检查。
    if (child == null || typeof child === 'boolean') {
      return;
    }

    // 字符串和数字会进入文本存储，是 SelectableText 支持的内容。
    if (typeof child === 'string' || typeof child === 'number') {
      return;
    }

    // 非 React element 节点无法识别为 RN View，直接跳过。
    if (!React.isValidElement<{children?: React.ReactNode}>(child)) {
      return;
    }

    // RN View 会被 RN Text 系统转换成 attachment，因此禁止放入 SelectableText。
    if (child.type === View) {
      hasUnsupportedView = true;
      return;
    }

    // 继续检查 Text、Fragment 或自定义元素传入的 children，避免深层 View 绕过限制。
    if (
      child.props.children != null &&
      containsUnsupportedViewChild(child.props.children)
    ) {
      hasUnsupportedView = true;
    }
  });

  return hasUnsupportedView;
}

function SelectableText({
  selectable = true,
  style,
  children,
  menuItems,
  showSystemMenuItems = true,
  clearSelectionOnMenuAction = false,
  onMenuAction,
}: NativeSelectableTextProps): React.JSX.Element {
  // SelectableText 只允许文本子树，避免 View 进入原生层后变成不可拆分附件。
  if (containsUnsupportedViewChild(children)) {
    throw new Error(
      'SelectableText does not support View children. Use nested Text, or render View outside SelectableText.',
    );
  }

  // 非 iOS 平台使用 RN Text 自带 selectable 能力，不接入 iOS 原生自定义菜单。
  if (Platform.OS !== 'ios' || !NativeSelectableText) {
    return (
      <Text selectable={selectable} style={style}>
        {children}
      </Text>
    );
  }

  // 必须提供 TextAncestor context，使子 <Text> 渲染为 RCTVirtualText
  // 而非独立的 RCTText，这样文字才能合并到 RCTSelectableTextView 的 NSTextStorage 中
  return (
    <NativeSelectableText
      selectable={selectable}
      style={style}
      menuItems={menuItems}
      showSystemMenuItems={showSystemMenuItems}
      clearSelectionOnMenuAction={clearSelectionOnMenuAction}
      onMenuAction={onMenuAction}>
      <TextAncestor.Provider value={true}>
        {children}
      </TextAncestor.Provider>
    </NativeSelectableText>
  );
}

export default SelectableText;
export type {SelectableTextMenuActionEvent, SelectableTextMenuItem};
