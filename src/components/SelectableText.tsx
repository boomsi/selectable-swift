import React from 'react';
import {
  findNodeHandle,
  NativeModules,
  UIManager,
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

interface SelectableTextLongPressEvent {
  paragraphText: string;
  selectionStart: number;
  selectionEnd: number;
  locationX: number;
  locationY: number;
  pageX: number;
  pageY: number;
}

type SelectableTextSelectionMode = 'default' | 'menuThenParagraph';

interface SelectableTextRef {
  selectRange: (start: number, end: number) => void;
  clearSelection: () => void;
  copyRange: (start: number, end: number) => void;
}

interface NativeSelectableTextProps {
  selectable?: boolean;
  style?: StyleProp<TextStyle>;
  children?: React.ReactNode;
  menuItems?: SelectableTextMenuItem[];
  showSystemMenuItems?: boolean;
  clearSelectionOnMenuAction?: boolean;
  selectionMode?: SelectableTextSelectionMode;
  onMenuAction?: (
    event: NativeSyntheticEvent<SelectableTextMenuActionEvent>,
  ) => void;
  onTextLongPress?: (
    event: NativeSyntheticEvent<SelectableTextLongPressEvent>,
  ) => void;
}

// SelectableText 原生命令名称，和 RCTSelectableTextViewManager 导出的 command 保持一致。
const SELECTABLE_TEXT_COMMANDS = {
  selectRange: 'selectRange',
  clearSelection: 'clearSelection',
  copyRange: 'copyRange',
} as const;

// iOS 和 Android 都使用同名原生 SelectableText，其他平台保留 RN Text fallback。
const NativeSelectableText =
  Platform.OS === 'ios' || Platform.OS === 'android'
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

function dispatchSelectableTextCommand(
  nativeRef: React.RefObject<any>,
  command: (typeof SELECTABLE_TEXT_COMMANDS)[keyof typeof SELECTABLE_TEXT_COMMANDS],
  args: unknown[] = [],
) {
  const nativeTag = findNodeHandle(nativeRef.current);

  // nativeTag 为空时说明原生视图尚未挂载，不能发送命令。
  if (nativeTag == null) {
    return;
  }

  const nativeSelectableTextManager = NativeModules.SelectableText;

  // 优先直接调用 ViewManager 导出的 native method，避免旧架构 command 名称映射不一致。
  if (typeof nativeSelectableTextManager?.[command] === 'function') {
    nativeSelectableTextManager[command](nativeTag, ...args);
    return;
  }

  // 兜底走 UIManager command 通道，兼容只暴露 command config 的运行环境。
  UIManager.dispatchViewManagerCommand(nativeTag, command, args);
}

const SelectableText = React.forwardRef<SelectableTextRef, NativeSelectableTextProps>(
  (
    {
      selectable = true,
      style,
      children,
      menuItems,
      showSystemMenuItems = true,
      clearSelectionOnMenuAction = false,
      selectionMode = 'default',
      onMenuAction,
      onTextLongPress,
    },
    ref,
  ): React.JSX.Element => {
    // 原生命令通过 findNodeHandle 定位 HostComponent，避免暴露底层 native view 类型。
    const nativeRef = React.useRef<any>(null);

    // 暴露给 RN 菜单调用的原生选区命令。
    React.useImperativeHandle(
      ref,
      () => ({
        selectRange: (start: number, end: number) => {
          dispatchSelectableTextCommand(nativeRef, SELECTABLE_TEXT_COMMANDS.selectRange, [
            start,
            end,
          ]);
        },
        clearSelection: () => {
          dispatchSelectableTextCommand(nativeRef, SELECTABLE_TEXT_COMMANDS.clearSelection);
        },
        copyRange: (start: number, end: number) => {
          dispatchSelectableTextCommand(nativeRef, SELECTABLE_TEXT_COMMANDS.copyRange, [
            start,
            end,
          ]);
        },
      }),
      [],
    );

  // SelectableText 只允许文本子树，避免 View 进入原生层后变成不可拆分附件。
  if (containsUnsupportedViewChild(children)) {
    throw new Error(
      'SelectableText does not support View children. Use nested Text, or render View outside SelectableText.',
    );
  }

  // 未注册原生 SelectableText 的平台使用 RN Text 自带 selectable 能力。
  if (!NativeSelectableText) {
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
      ref={nativeRef}
      selectable={selectable}
      style={style}
      menuItems={menuItems}
      showSystemMenuItems={showSystemMenuItems}
      clearSelectionOnMenuAction={clearSelectionOnMenuAction}
      selectionMode={selectionMode}
      onMenuAction={onMenuAction}
      onTextLongPress={onTextLongPress}>
      <TextAncestor.Provider value={true}>
        {children}
      </TextAncestor.Provider>
    </NativeSelectableText>
  );
  },
);

export default SelectableText;
export type {
  SelectableTextLongPressEvent,
  SelectableTextMenuActionEvent,
  SelectableTextMenuItem,
  SelectableTextRef,
  SelectableTextSelectionMode,
};
