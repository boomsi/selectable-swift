# SelectableText 使用示例

`SelectableText` 是一个 iOS 原生 `UITextView` 封装，用于支持富文本选区、系统手柄、选中后菜单，以及业务侧长按菜单。

## 基础用法

```tsx
import React from 'react';
import {Text} from 'react-native';
import SelectableText from './src/components/SelectableText';

function Example() {
  return (
    <SelectableText selectable style={{fontSize: 16, lineHeight: 24}}>
      <Text>普通文本</Text>
      <Text style={{fontWeight: '700'}}>加粗文本</Text>
    </SelectableText>
  );
}
```

`SelectableText` 内部只允许文本子树，不要放 `View`。如果需要布局容器，把 `View` 放在 `SelectableText` 外面。

## RN 长按菜单 + 选中段落

`selectionMode="menuThenParagraph"` 下，长按不会直接选中文本。native 会计算长按位置所在段落，并把段落范围和菜单坐标回传给 RN。RN 渲染业务菜单，点击菜单项后再调用 ref 命令选中或复制段落。

```tsx
import React from 'react';
import {Pressable, Text, View} from 'react-native';
import SelectableText, {
  type SelectableTextLongPressEvent,
  type SelectableTextRef,
} from './src/components/SelectableText';

function ParagraphMenuExample() {
  const textRef = React.useRef<SelectableTextRef>(null);
  const [menu, setMenu] = React.useState<SelectableTextLongPressEvent | null>(
    null,
  );

  return (
    <View>
      <SelectableText
        ref={textRef}
        selectionMode="menuThenParagraph"
        onTextLongPress={({nativeEvent}) => {
          setMenu(nativeEvent);
        }}>
        <Text>第一段文本</Text>
        <Text>{'\n'}第二段文本</Text>
      </SelectableText>

      {menu && (
        <View style={{position: 'absolute', left: menu.pageX, top: menu.pageY}}>
          <Pressable
            onPress={() => {
              textRef.current?.selectRange(
                menu.selectionStart,
                menu.selectionEnd,
              );
              setMenu(null);
            }}>
            <Text>选取文本</Text>
          </Pressable>

          <Pressable
            onPress={() => {
              textRef.current?.copyRange(menu.selectionStart, menu.selectionEnd);
              textRef.current?.clearSelection();
              setMenu(null);
            }}>
            <Text>复制</Text>
          </Pressable>
        </View>
      )}
    </View>
  );
}
```

段落按换行符 `\n` 分割。

## 选中后的原生自定义菜单

点击 RN 菜单里的“选取文本”后，native 会设置 `selectedRange` 并显示 iOS 选区菜单。可以通过 `menuItems` 添加业务菜单项。

```tsx
<SelectableText
  ref={textRef}
  selectionMode="menuThenParagraph"
  menuItems={[
    {id: 'quote', title: '引用'},
    {id: 'explain', title: '解释'},
  ]}
  showSystemMenuItems={false}
  clearSelectionOnMenuAction={true}
  onMenuAction={({nativeEvent}) => {
    console.log(nativeEvent.id, nativeEvent.selectedText);
  }}
  onTextLongPress={({nativeEvent}) => {
    setMenu(nativeEvent);
  }}>
  <Text>这是一段可选中的文本</Text>
</SelectableText>
```

- `showSystemMenuItems={false}`：只显示业务菜单项，不显示系统复制/全选等菜单。
- `clearSelectionOnMenuAction={true}`：点击选中后的原生菜单项后自动清空选区。

## FlatList 推荐写法

列表里不需要在父组件维护所有 `SelectableText` 的 ref。每个 item 自己创建 ref，长按时只把当前 item 的 ref 和 range 交给父级菜单状态。

```tsx
import React from 'react';
import {FlatList, Pressable, Text} from 'react-native';
import SelectableText, {
  type SelectableTextLongPressEvent,
  type SelectableTextRef,
} from './src/components/SelectableText';

function Row({item, onTextLongPress}: any) {
  const textRef = React.useRef<SelectableTextRef>(null);

  return (
    <SelectableText
      ref={textRef}
      selectionMode="menuThenParagraph"
      onTextLongPress={({nativeEvent}) => {
        onTextLongPress({
          textRef,
          itemId: item.id,
          ...nativeEvent,
        });
      }}>
      <Text>{item.content}</Text>
    </SelectableText>
  );
}

function ListExample({data}: {data: Array<{id: string; content: string}>}) {
  const [menu, setMenu] = React.useState<any>(null);

  return (
    <>
      <FlatList
        data={data}
        keyExtractor={item => item.id}
        renderItem={({item}) => (
          <Row item={item} onTextLongPress={setMenu} />
        )}
      />

      {menu && (
        <Pressable
          onPress={() => {
            menu.textRef.current?.selectRange(
              menu.selectionStart,
              menu.selectionEnd,
            );
            setMenu(null);
          }}>
          <Text>选取文本</Text>
        </Pressable>
      )}
    </>
  );
}
```

这种方式父级只保存当前长按的 item，不保存整个列表的 ref。
