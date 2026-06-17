import React from 'react';
import {Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import SelectableText, {
  type SelectableTextLongPressEvent,
  type SelectableTextMenuActionEvent,
  type SelectableTextMenuItem,
  type SelectableTextRef,
} from '../components/SelectableText';

interface TextSegment {
  text: string;
  bold?: boolean;
  italic?: boolean;
}

interface ContentBlock {
  type: 'h1' | 'h2' | 'h3' | 'paragraph';
  segments?: TextSegment[];
  text?: string;
}

const mockData: ContentBlock[] = [
  {
    type: 'h1',
    text: 'React Native 文本选取能力',
  },
  {
    type: 'h2',
    text: '背景介绍',
  },
  {
    type: 'paragraph',
    segments: [
      {text: 'React Native 的 '},
      {text: 'Text', bold: true},
      {text: ' 组件从早期版本就内置了 '},
      {text: 'selectable', bold: true, italic: true},
      {text: ' 属性。当该属性设置为 '},
      {text: 'true', italic: true},
      {text: ' 时，用户可以通过 '},
      {text: '长按', bold: true},
      {
        text: ' 文本来触发系统原生的文本选取能力。iOS 端会弹出系统选取菜单（复制、全选等），Android 端同样支持选取和复制操作。',
      },
    ],
  },
  {
    type: 'h2',
    text: '实现原理',
  },
  {
    type: 'paragraph',
    segments: [
      {text: '在底层，React Native 将 '},
      {text: 'selectable', italic: true},
      {text: ' 映射到 iOS 的 '},
      {text: 'UITextView.selectable', bold: true},
      {text: ' 和 Android 的 '},
      {text: 'TextView.setTextIsSelectable()', bold: true},
      {text: '。整个映射过程对开发者透明，只需声明式地设置属性即可。'},
    ],
  },
  {
    type: 'h3',
    text: '嵌套文本支持',
  },
  {
    type: 'paragraph',
    segments: [
      {text: 'React Native 的 Text 组件支持嵌套。'},
      {text: '父级 Text', bold: true},
      {text: ' 设置 '},
      {text: 'selectable={true}', italic: true},
      {text: ' 后，'},
      {text: '所有嵌套的子 Text', bold: true},
      {text: ' 内容均可被选中和复制。这意味着我们可以在一个段落中混合使用 '},
      {text: '粗体', bold: true},
      {text: '、'},
      {text: '斜体', italic: true},
      {text: '、'},
      {text: '粗斜体', bold: true, italic: true},
      {text: ' 等多种样式，而文本选取功能依然正常工作。'},
    ],
  },
  {
    type: 'h2',
    text: 'Markdown 风格渲染示例',
  },
  {
    type: 'paragraph',
    segments: [
      {text: '下面是一段模拟 Markdown 渲染的富文本内容。'},
      {text: '这段文字完全由嵌套的 Text 组件拼接而成，', bold: true},
      {text: '但对外表现为一个完整可选中的文本块', italic: true},
      {text: '。用户长按任意位置即可触发文本选取，然后拖动选取手柄调整范围。'},
    ],
  },
  {
    type: 'h3',
    text: '使用注意事项',
  },
  {
    type: 'paragraph',
    segments: [
      {text: '1. ', bold: true},
      {
        text: '每个文本块（标题/段落）应作为独立的 selectable Text 组件。不要将整个页面包在一个 selectable Text 中，否则会导致不同层级的文本被不恰当地合并选取。',
      },
    ],
  },
  {
    type: 'paragraph',
    segments: [
      {text: '2. ', bold: true},
      {
        text: '在 ScrollView 中使用 selectable Text 时，需要注意手势冲突。React Native 内部已处理了大部分情况，但在极端场景下可能需要手动调整。',
      },
    ],
  },
  {
    type: 'paragraph',
    segments: [
      {text: '3. ', bold: true},
      {text: '嵌套 Text 的样式继承：子 Text 会继承父 Text 的默认样式（如 '},
      {text: 'fontSize', italic: true},
      {text: '、'},
      {text: 'color', italic: true},
      {text: ' 等），但 '},
      {text: 'fontWeight', bold: true},
      {text: ' 和 '},
      {text: 'fontStyle', bold: true},
      {text: ' 需要显式设置以覆盖。'},
    ],
  },
  {
    type: 'h2',
    text: '总结',
  },
  {
    type: 'paragraph',
    segments: [
      {text: 'React Native 的文本选取是一个开箱即用的功能，通过简单的 '},
      {text: 'selectable', bold: true, italic: true},
      {
        text: ' 属性即可实现。配合嵌套 Text 组件，可以在保持富文本样式的同时，提供与原生应用一致的文本选取体验。这使得 React Native 非常适合构建内容展示类应用，如新闻阅读器、文档浏览器、笔记应用等。',
      },
    ],
  },
];

// 块之间的间距用空行 + 行高模拟
const BLOCK_SEPARATOR = '\n\n';

// iOS 选中文本后的原生菜单项，点击“选取文本”进入选区后展示。
const SELECTABLE_TEXT_MENU_ITEMS: SelectableTextMenuItem[] = [
  {id: 'quote', title: '引用'},
  {id: 'explain', title: '解释'},
];

interface ParagraphMenuState {
  targetRef: React.RefObject<SelectableTextRef | null>;
  paragraphText: string;
  selectionStart: number;
  selectionEnd: number;
  pageX: number;
  pageY: number;
}

function MarkdownPage(): React.JSX.Element {
  const children: React.ReactNode[] = [];

  // 第一个示例文本的原生命令 ref，用于 RN 菜单点击后选择或复制段落。
  const firstTextRef = React.useRef<SelectableTextRef>(null);
  // 第二个示例文本的原生命令 ref，用于 RN 菜单点击后选择或复制段落。
  const secondTextRef = React.useRef<SelectableTextRef>(null);
  // 正文文本的原生命令 ref，用于 RN 菜单点击后选择或复制段落。
  const documentTextRef = React.useRef<SelectableTextRef>(null);
  // RN 下拉菜单状态，保存 native 长按命中的段落 range 和菜单锚点。
  const [paragraphMenu, setParagraphMenu] =
    React.useState<ParagraphMenuState | null>(null);
  // 已经通过菜单选中的段落状态，点击页面其他位置时需要清空它。
  const [activeParagraphRef, setActiveParagraphRef] =
    React.useState<React.RefObject<SelectableTextRef | null> | null>(null);

  // 处理 iOS 选中后原生自定义菜单点击结果。
  const handleSelectionMenuAction = ({
    nativeEvent,
  }: {
    nativeEvent: SelectableTextMenuActionEvent;
  }) => {
    console.log('[SelectableText] selection menu action:', nativeEvent);
  };

  // native 长按命中段落后回调，RN 根据业务状态渲染自己的菜单。
  const handleTextLongPress =
    (targetRef: React.RefObject<SelectableTextRef | null>) =>
    ({nativeEvent}: {nativeEvent: SelectableTextLongPressEvent}) => {
      // 新菜单出现前先清掉上一次通过菜单选中的段落，避免旧高亮残留。
      if (activeParagraphRef) {
        activeParagraphRef.current?.clearSelection();
        setActiveParagraphRef(null);
      }

      setParagraphMenu({
        targetRef,
        paragraphText: nativeEvent.paragraphText,
        selectionStart: nativeEvent.selectionStart,
        selectionEnd: nativeEvent.selectionEnd,
        pageX: nativeEvent.pageX,
        pageY: nativeEvent.pageY,
      });
    };

  // 点击“选取文本”时，按 native 返回的换行段落 range 选中整段。
  const handleSelectParagraph = () => {
    // 没有菜单上下文时不执行选区命令。
    if (!paragraphMenu) {
      return;
    }

    paragraphMenu.targetRef.current?.selectRange(
      paragraphMenu.selectionStart,
      paragraphMenu.selectionEnd,
    );
    setActiveParagraphRef(paragraphMenu.targetRef);
    setParagraphMenu(null);
  };

  // 点击“复制”时，复制 native 命中的段落文本并回到初始未选中状态。
  const handleCopyParagraph = () => {
    // 没有菜单上下文时不执行复制命令。
    if (!paragraphMenu) {
      return;
    }

    paragraphMenu.targetRef.current?.copyRange(
      paragraphMenu.selectionStart,
      paragraphMenu.selectionEnd,
    );
    paragraphMenu.targetRef.current?.clearSelection();
    setActiveParagraphRef(null);
    setParagraphMenu(null);
  };

  // 点击菜单外区域时关闭 RN 菜单，并清空通过菜单选中的段落。
  const handleDismissParagraphMenu = () => {
    paragraphMenu?.targetRef.current?.clearSelection();
    activeParagraphRef?.current?.clearSelection();
    setParagraphMenu(null);
    setActiveParagraphRef(null);
  };

  mockData.forEach((block, index) => {
    // 块间插入换行分隔
    if (index > 0) {
      children.push(<Text key={`sep-${index}`}>{BLOCK_SEPARATOR}</Text>);
    }

    switch (block.type) {
      case 'h1':
        children.push(
          <Text key={index} style={styles.h1}>
            {block.text}
          </Text>,
        );
        break;
      case 'h2':
        children.push(
          <Text key={index} style={styles.h2}>
            {block.text}
          </Text>,
        );
        break;
      case 'h3':
        children.push(
          <Text key={index} style={styles.h3}>
            {block.text}
          </Text>,
        );
        break;
      case 'paragraph':
        children.push(
          <Text key={index} style={styles.paragraph}>
            {block.segments?.map((seg, segIndex) => {
              const segStyle = [
                seg.bold && styles.bold,
                seg.italic && styles.italic,
              ].filter(Boolean);
              return (
                <Text key={segIndex} style={segStyle}>
                  {seg.text}
                </Text>
              );
            })}
          </Text>,
        );
        break;
    }
  });

  return (
    <View style={styles.container}>
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.contentContainer}
        // 允许 ScrollView 在用户上下拖动时接管触摸，长按无滚动位移时仍由 SelectableText 处理文本选中。
        canCancelContentTouches={true}
        keyboardShouldPersistTaps="handled">
        <View
          style={{
            backgroundColor: '#38f',
            padding: 10,
            marginBottom: 20,
            maxWidth: '100%',
            borderRadius: 8,
          }}>
          <SelectableText
            ref={firstTextRef}
            selectable={true}
            style={[styles.document]}
            menuItems={SELECTABLE_TEXT_MENU_ITEMS}
            showSystemMenuItems={false}
            clearSelectionOnMenuAction={true}
            selectionMode="menuThenParagraph"
            onMenuAction={handleSelectionMenuAction}
            onTextLongPress={handleTextLongPress(firstTextRef)}>
            <Text>这是 Text</Text>
            <Text>{'\n'}这是 Text 内的 Text</Text>
          </SelectableText>
        </View>

        <SelectableText
          ref={secondTextRef}
          selectable={true}
          style={styles.document}
          menuItems={SELECTABLE_TEXT_MENU_ITEMS}
          showSystemMenuItems={false}
          clearSelectionOnMenuAction={true}
          selectionMode="menuThenParagraph"
          onMenuAction={handleSelectionMenuAction}
          onTextLongPress={handleTextLongPress(secondTextRef)}>
          <Text>123</Text>
          <Text>123</Text>
          <Text>123</Text>
          <Text>123</Text>
          <Text>123dmsajndoa</Text>
        </SelectableText>
        <SelectableText
          ref={documentTextRef}
          selectable={true}
          style={styles.document}
          menuItems={SELECTABLE_TEXT_MENU_ITEMS}
          showSystemMenuItems={false}
          clearSelectionOnMenuAction={true}
          selectionMode="menuThenParagraph"
          onMenuAction={handleSelectionMenuAction}
          onTextLongPress={handleTextLongPress(documentTextRef)}>
          {children}
        </SelectableText>
      </ScrollView>
      {paragraphMenu && (
        <Pressable
          style={styles.menuDismissLayer}
          onPress={handleDismissParagraphMenu}>
          <View
            style={[
              styles.paragraphMenu,
              {
                left: Math.max(12, paragraphMenu.pageX - 24),
                top: paragraphMenu.pageY + 10,
              },
            ]}>
            <Pressable
              style={styles.paragraphMenuItem}
              onPress={event => {
                // 菜单项点击只执行当前动作，不触发外层关闭层。
                event.stopPropagation();
                handleSelectParagraph();
              }}>
              <Text style={styles.paragraphMenuItemText}>选取文本</Text>
            </Pressable>
            <Pressable
              style={styles.paragraphMenuItem}
              onPress={event => {
                // 菜单项点击只执行当前动作，不触发外层关闭层。
                event.stopPropagation();
                handleCopyParagraph();
              }}>
              <Text style={styles.paragraphMenuItemText}>复制</Text>
            </Pressable>
          </View>
        </Pressable>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#ffffff',
  },
  scrollView: {
    flex: 1,
  },
  contentContainer: {
    // 让 ScrollView 的直接子元素按内容宽度布局，而不是被默认 stretch 拉满横向宽度。
    alignItems: 'flex-start',
    paddingHorizontal: 20,
    paddingTop: 60,
    paddingBottom: 40,
  },
  document: {
    // 长文本最多占满可用宽度，短文本保持由内容撑开的宽度。
    maxWidth: '100%',
    fontSize: 16,
    color: '#333333',
    lineHeight: 26,
  },
  h1: {
    fontSize: 28,
    fontWeight: '700',
    color: '#1a1a2e',
    lineHeight: 36,
  },
  h2: {
    fontSize: 22,
    fontWeight: '600',
    color: '#16213e',
    lineHeight: 30,
  },
  h3: {
    fontSize: 18,
    fontWeight: '600',
    color: '#0f3460',
    lineHeight: 26,
  },
  paragraph: {
    fontSize: 16,
    color: '#333333',
    lineHeight: 26,
  },
  bold: {
    fontWeight: '700',
  },
  italic: {
    fontStyle: 'italic',
  },
  menuDismissLayer: {
    ...StyleSheet.absoluteFillObject,
    zIndex: 10,
  },
  paragraphMenu: {
    position: 'absolute',
    minWidth: 120,
    overflow: 'hidden',
    borderRadius: 8,
    backgroundColor: '#ffffff',
    shadowColor: '#000000',
    shadowOpacity: 0.18,
    shadowRadius: 12,
    shadowOffset: {
      width: 0,
      height: 4,
    },
    elevation: 6,
  },
  paragraphMenuItem: {
    paddingHorizontal: 14,
    paddingVertical: 11,
  },
  paragraphMenuItemText: {
    fontSize: 15,
    color: '#111111',
  },
});

export default MarkdownPage;
