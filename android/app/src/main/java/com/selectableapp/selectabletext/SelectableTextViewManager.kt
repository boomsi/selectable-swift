package com.selectableapp.selectabletext

import com.facebook.react.bridge.JSApplicationIllegalArgumentException
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.common.MapBuilder
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.views.text.ReactTextShadowNode
import com.facebook.react.views.text.ReactTextView
import com.facebook.react.views.text.ReactTextViewManager

// SelectableTextViewManager 继承 RN Text manager，复用 Text props、Spannable 和测量链路。
@ReactModule(name = SelectableTextViewManager.REACT_CLASS)
class SelectableTextViewManager : ReactTextViewManager() {
  // getName 导出给 JS requireNativeComponent('SelectableText') 使用的组件名。
  override fun getName(): String = REACT_CLASS

  // createViewInstance 创建真正承载 Android 原生选区能力的 ReactTextView 子类。
  override fun createViewInstance(context: ThemedReactContext): SelectableTextView =
      SelectableTextView(context)

  // createShadowNodeInstance 继续使用 RN TextShadowNode 的文本解析和 Yoga 测量。
  override fun createShadowNodeInstance(): ReactTextShadowNode =
      SelectableTextShadowNode(mReactTextViewManagerCallback)

  // getShadowNodeClass 告诉 RN 当前组件的 shadow node 类型。
  @Suppress("UNCHECKED_CAST")
  override fun getShadowNodeClass(): Class<ReactTextShadowNode> =
      SelectableTextShadowNode::class.java as Class<ReactTextShadowNode>

  // setMenuItems 把 JS menuItems 数组转换成 Android ActionMode 菜单配置。
  @ReactProp(name = "menuItems")
  fun setMenuItems(view: SelectableTextView, menuItems: ReadableArray?) {
    val parsedMenuItems = mutableListOf<SelectableTextMenuItem>()

    // menuItems 为空时清空自定义菜单配置。
    if (menuItems == null) {
      view.menuItems = parsedMenuItems
      return
    }

    for (index in 0 until menuItems.size()) {
      val item = menuItems.getMap(index)

      // 缺少 id 或 title 的项不传给原生菜单，避免点击后 JS 无法区分动作。
      if (item == null || !item.hasKey("id") || !item.hasKey("title")) {
        continue
      }

      val id = item.getString("id")
      val title = item.getString("title")

      // id/title 不是有效字符串时跳过该菜单项。
      if (id.isNullOrBlank() || title.isNullOrBlank()) {
        continue
      }

      parsedMenuItems.add(SelectableTextMenuItem(id, title))
    }

    view.menuItems = parsedMenuItems
  }

  // setShowSystemMenuItems 控制 Android ActionMode 是否保留系统菜单项。
  @ReactProp(name = "showSystemMenuItems", defaultBoolean = true)
  fun setShowSystemMenuItems(view: SelectableTextView, showSystemMenuItems: Boolean) {
    view.showSystemMenuItems = showSystemMenuItems
  }

  // setClearSelectionOnMenuAction 控制自定义菜单点击后是否清空选区。
  @ReactProp(name = "clearSelectionOnMenuAction", defaultBoolean = false)
  fun setClearSelectionOnMenuAction(view: SelectableTextView, clearSelectionOnMenuAction: Boolean) {
    view.clearSelectionOnMenuAction = clearSelectionOnMenuAction
  }

  // setSelectionMode 保持 Android 与 iOS 的 JS API 对齐；Android 当前先走原生长按选区。
  @ReactProp(name = "selectionMode")
  fun setSelectionMode(view: SelectableTextView, selectionMode: String?) {
    view.selectionMode = selectionMode ?: SelectableTextView.SELECTION_MODE_DEFAULT
  }

  // getCommandsMap 导出旧架构 UIManager command 名称到数字 id 的映射。
  override fun getCommandsMap(): Map<String, Int> =
      mapOf(
          COMMAND_SELECT_RANGE to COMMAND_SELECT_RANGE_ID,
          COMMAND_CLEAR_SELECTION to COMMAND_CLEAR_SELECTION_ID,
          COMMAND_COPY_RANGE to COMMAND_COPY_RANGE_ID,
      )

  // receiveCommand 兼容旧的数字 command id 调用路径。
  @Deprecated(
      message = "Use receiveCommand(SelectableTextView, String, ReadableArray) instead",
      replaceWith = ReplaceWith("receiveCommand(root, commandIdString, args)"))
  override fun receiveCommand(root: ReactTextView, commandId: Int, args: ReadableArray?) {
    val selectableTextView = root as? SelectableTextView

    // command 只处理本 manager 创建的 SelectableTextView，避免错误 view 类型执行选区命令。
    if (selectableTextView == null) {
      return
    }

    when (commandId) {
      COMMAND_SELECT_RANGE_ID -> handleSelectRangeCommand(selectableTextView, args)
      COMMAND_CLEAR_SELECTION_ID -> selectableTextView.clearSelection()
      COMMAND_COPY_RANGE_ID -> handleCopyRangeCommand(selectableTextView, args)
    }
  }

  // receiveCommand 处理 JS UIManager.dispatchViewManagerCommand 传入的字符串命令。
  override fun receiveCommand(root: ReactTextView, commandId: String, args: ReadableArray?) {
    val selectableTextView = root as? SelectableTextView

    // command 只处理本 manager 创建的 SelectableTextView，避免错误 view 类型执行选区命令。
    if (selectableTextView == null) {
      return
    }

    when (commandId) {
      COMMAND_SELECT_RANGE -> handleSelectRangeCommand(selectableTextView, args)
      COMMAND_CLEAR_SELECTION -> selectableTextView.clearSelection()
      COMMAND_COPY_RANGE -> handleCopyRangeCommand(selectableTextView, args)
    }
  }

  // getExportedCustomDirectEventTypeConstants 注册 onMenuAction/onTextLongPress 这两个 direct event。
  @Suppress("UNCHECKED_CAST")
  override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> {
    val eventTypeConstants = mutableMapOf<String, Any>()
    val baseEventTypeConstants =
        super.getExportedCustomDirectEventTypeConstants() as? Map<String, Any>
    baseEventTypeConstants?.let { eventTypeConstants.putAll(it) }
    return eventTypeConstants.apply {
      put(
          SelectableTextView.EVENT_MENU_ACTION,
          MapBuilder.of("registrationName", "onMenuAction"))
      put(
          SelectableTextView.EVENT_TEXT_LONG_PRESS,
          MapBuilder.of("registrationName", "onTextLongPress"))
    }
  }

  // handleSelectRangeCommand 校验 selectRange 命令参数并转发给原生视图。
  private fun handleSelectRangeCommand(view: SelectableTextView, args: ReadableArray?) {
    // selectRange 必须包含 start/end 两个数字参数。
    if (args == null || args.size() < 2) {
      throw JSApplicationIllegalArgumentException("selectRange requires start and end arguments")
    }

    view.selectRange(args.getInt(0), args.getInt(1))
  }

  // handleCopyRangeCommand 校验 copyRange 命令参数并复制指定文本范围。
  private fun handleCopyRangeCommand(view: SelectableTextView, args: ReadableArray?) {
    // copyRange 必须包含 start/end 两个数字参数。
    if (args == null || args.size() < 2) {
      throw JSApplicationIllegalArgumentException("copyRange requires start and end arguments")
    }

    view.copyRange(args.getInt(0), args.getInt(1))
  }

  companion object {
    // REACT_CLASS 必须和 JS requireNativeComponent('SelectableText') 完全一致。
    const val REACT_CLASS = "SelectableText"

    // COMMAND_SELECT_RANGE 是 JS ref.selectRange 派发的 command 名称。
    private const val COMMAND_SELECT_RANGE = "selectRange"

    // COMMAND_CLEAR_SELECTION 是 JS ref.clearSelection 派发的 command 名称。
    private const val COMMAND_CLEAR_SELECTION = "clearSelection"

    // COMMAND_COPY_RANGE 是 JS ref.copyRange 派发的 command 名称。
    private const val COMMAND_COPY_RANGE = "copyRange"

    // COMMAND_SELECT_RANGE_ID 是旧数字 command 通道里的 selectRange id。
    private const val COMMAND_SELECT_RANGE_ID = 1

    // COMMAND_CLEAR_SELECTION_ID 是旧数字 command 通道里的 clearSelection id。
    private const val COMMAND_CLEAR_SELECTION_ID = 2

    // COMMAND_COPY_RANGE_ID 是旧数字 command 通道里的 copyRange id。
    private const val COMMAND_COPY_RANGE_ID = 3
  }
}
