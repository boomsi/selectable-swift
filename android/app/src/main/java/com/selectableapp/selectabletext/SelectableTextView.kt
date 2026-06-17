package com.selectableapp.selectabletext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Selection
import android.text.Spannable
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
import com.facebook.react.views.text.ReactTextView
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

// SelectableTextMenuItem 保存 JS 传入的 ActionMode 自定义菜单项。
data class SelectableTextMenuItem(val id: String, val title: String)

// SelectableTextView 在 RN ReactTextView 基础上接入 Android 原生文本选区和菜单能力。
class SelectableTextView(context: Context) : ReactTextView(context) {
  // menuItems 是 JS 侧传入的自定义选中文本菜单配置。
  var menuItems: List<SelectableTextMenuItem> = emptyList()
    set(value) {
      field = value
      invalidateActionMode()
    }

  // showSystemMenuItems 控制是否保留复制、全选等 Android 系统菜单项。
  var showSystemMenuItems: Boolean = true
    set(value) {
      field = value
      invalidateActionMode()
    }

  // clearSelectionOnMenuAction 控制自定义菜单点击后是否自动清空当前选区。
  var clearSelectionOnMenuAction: Boolean = false

  // selectionMode 保留 JS/iOS 同名 API；Android 当前先实现原生长按选区主流程。
  var selectionMode: String = SELECTION_MODE_DEFAULT

  // currentActionMode 记录当前浮动菜单，用于属性更新、清理选区和释放父级手势拦截。
  private var currentActionMode: ActionMode? = null

  // selectionActionModeCallback 统一处理系统菜单保留策略、自定义菜单和 JS 回调。
  private val selectionActionModeCallback =
      object : ActionMode.Callback {
        // onCreateActionMode 在 Android 进入文本选择菜单时初始化菜单内容。
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
          currentActionMode = mode
          populateSelectionMenu(menu)
          return true
        }

        // onPrepareActionMode 在菜单刷新时重新应用系统菜单开关和自定义菜单项。
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
          populateSelectionMenu(menu)
          return true
        }

        // onActionItemClicked 只消费 SelectableText 自定义菜单项，系统菜单继续交给 TextView 默认逻辑。
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
          val customItem = customMenuItemForMenuId(item.itemId)

          // 非自定义菜单项返回 false，让 Android TextView 继续处理复制、全选等系统动作。
          if (customItem == null) {
            return false
          }

          emitMenuAction(customItem)

          // 自定义菜单点击后按 prop 决定是否清掉 Android TextView 的当前选区。
          if (clearSelectionOnMenuAction) {
            clearSelection()
            mode.finish()
          }

          return true
        }

        // onDestroyActionMode 在系统菜单关闭时释放父 ScrollView 的触摸拦截限制。
        override fun onDestroyActionMode(mode: ActionMode) {
          currentActionMode = null
          parent?.requestDisallowInterceptTouchEvent(false)
        }
      }

  init {
    // ReactTextView 默认 selectable=false，这里默认开启以匹配 SelectableText 的组件语义。
    setTextIsSelectable(true)
    setCustomSelectionActionModeCallback(selectionActionModeCallback)
  }

  // setTextIsSelectable 接收 RN selectable prop，并同步到底层 Android TextView。
  override fun setTextIsSelectable(selectable: Boolean) {
    super.setTextIsSelectable(selectable)
  }

  // onTouchEvent 在不同 SelectableText 之间切换时清理旧选区，并在拖动手柄时保护父级手势。
  override fun onTouchEvent(event: MotionEvent): Boolean {
    // ACTION_DOWN 表示用户开始和当前文本块交互，需要清掉上一个文本块的残留选区。
    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
      markAsActiveSelectableTextView()
    }

    // 当前文本已经有选区时，后续拖动通常是选择手柄移动，不应被 ScrollView 抢走。
    if (hasActiveSelection()) {
      parent?.requestDisallowInterceptTouchEvent(true)
    }

    val handled = super.onTouchEvent(event)

    // 触摸结束且没有选区时，把滚动拦截权还给父 ScrollView。
    if (
        (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) &&
            !hasActiveSelection()) {
      parent?.requestDisallowInterceptTouchEvent(false)
    }

    return handled
  }

  // onSelectionChanged 在系统长按产生选区时标记当前实例，并保护手柄拖动不被父视图截断。
  override fun onSelectionChanged(selStart: Int, selEnd: Int) {
    super.onSelectionChanged(selStart, selEnd)

    // 只有真实选中文本时才标记 active，避免普通点击插入点状态影响其他文本块。
    if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
      markAsActiveSelectableTextView()
      parent?.requestDisallowInterceptTouchEvent(true)
    }
  }

  // clearSelection 清理 Android TextView 的 Selection span 和当前 ActionMode。
  fun clearSelection() {
    val currentText = text

    // 只有 Spannable 文本才包含 Android Selection span，可以被程序化清理。
    if (currentText is Spannable) {
      Selection.removeSelection(currentText)
    }

    currentActionMode?.finish()
    clearFocus()
    parent?.requestDisallowInterceptTouchEvent(false)
    invalidate()
  }

  // selectRange 根据 JS 命令设置文本选区；Android 主路径仍是用户长按进入原生选择。
  fun selectRange(start: Int, end: Int) {
    val currentText = text
    val range = clampedRange(start, end, currentText.length)

    // range 无效或文本不是 Spannable 时，不能安全写入 Selection span。
    if (range == null || currentText !is Spannable) {
      return
    }

    markAsActiveSelectableTextView()
    requestFocus()
    Selection.setSelection(currentText, range.first, range.second)
    parent?.requestDisallowInterceptTouchEvent(true)
    invalidate()
  }

  // copyRange 根据 JS 命令复制指定范围文本到 Android 剪贴板。
  fun copyRange(start: Int, end: Int) {
    val range = clampedRange(start, end, text.length)

    // range 无效时不写剪贴板，避免复制空内容覆盖用户已有剪贴板。
    if (range == null) {
      return
    }

    val selectedText = text.subSequence(range.first, range.second).toString()
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, selectedText))
  }

  // populateSelectionMenu 根据 props 重建 ActionMode 菜单。
  private fun populateSelectionMenu(menu: Menu) {
    // showSystemMenuItems=false 时先清掉系统菜单项，只保留业务自定义菜单。
    if (!showSystemMenuItems) {
      menu.clear()
    }

    menu.removeGroup(CUSTOM_MENU_GROUP_ID)

    menuItems.forEachIndexed { index, item ->
      // 缺少 id 或 title 的菜单项不加入 Android 菜单，避免无法回传明确动作。
      if (item.id.isBlank() || item.title.isBlank()) {
        return@forEachIndexed
      }

      menu.add(CUSTOM_MENU_GROUP_ID, CUSTOM_MENU_ITEM_ID_OFFSET + index, Menu.NONE, item.title)
          .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    }
  }

  // customMenuItemForMenuId 把 Android menu itemId 映射回 JS 传入的菜单项。
  private fun customMenuItemForMenuId(menuItemId: Int): SelectableTextMenuItem? {
    val index = menuItemId - CUSTOM_MENU_ITEM_ID_OFFSET

    // index 越界说明该菜单项不是 SelectableText 自定义菜单。
    if (index < 0 || index >= menuItems.size) {
      return null
    }

    return menuItems[index]
  }

  // emitMenuAction 把当前选区和菜单动作回调给 JS onMenuAction。
  private fun emitMenuAction(item: SelectableTextMenuItem) {
    val selectionStart = Selection.getSelectionStart(text)
    val selectionEnd = Selection.getSelectionEnd(text)
    val normalizedStart = min(selectionStart, selectionEnd)
    val normalizedEnd = max(selectionStart, selectionEnd)
    val selectedText =
        // 当前选区有效时回传真实文本，否则回传空字符串让 JS 明确知道没有选中文本。
        if (normalizedStart >= 0 && normalizedEnd > normalizedStart) {
          text.subSequence(normalizedStart, normalizedEnd).toString()
        } else {
          ""
        }
    val event = Arguments.createMap().apply {
      putString("id", item.id)
      putString("title", item.title)
      putString("selectedText", selectedText)
      putInt("selectionStart", normalizedStart)
      putInt("selectionEnd", normalizedEnd)
    }

    // 只有 ReactContext 才能发送 RN 旧架构 direct event。
    if (context is ReactContext) {
      (context as ReactContext)
          .getJSModule(RCTEventEmitter::class.java)
          .receiveEvent(id, EVENT_MENU_ACTION, event)
    }
  }

  // invalidateActionMode 在菜单相关 props 更新时请求 Android 刷新当前 ActionMode。
  private fun invalidateActionMode() {
    // 只有已有 ActionMode 时才需要刷新菜单，未选中文本时无需做任何 UI 更新。
    if (currentActionMode != null) {
      currentActionMode?.invalidate()
    }
  }

  // hasActiveSelection 判断当前 TextView 是否持有一个非空文本选区。
  private fun hasActiveSelection(): Boolean {
    val selectionStart = Selection.getSelectionStart(text)
    val selectionEnd = Selection.getSelectionEnd(text)
    return selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd
  }

  // markAsActiveSelectableTextView 清理上一个 SelectableText 的残留选区。
  private fun markAsActiveSelectableTextView() {
    val previousActiveTextView = activeTextView?.get()

    // 只有切换到另一个 SelectableText 实例时才清理，避免干扰当前实例内的手柄拖动。
    if (previousActiveTextView != null && previousActiveTextView !== this) {
      previousActiveTextView.clearSelection()
    }

    activeTextView = WeakReference(this)
  }

  companion object {
    // EVENT_MENU_ACTION 是 Android 发送给 JS onMenuAction 的 direct event 名。
    const val EVENT_MENU_ACTION = "topMenuAction"

    // EVENT_TEXT_LONG_PRESS 仅注册给 JS/iOS 同名 API；Android 当前不实现间接选中长按菜单。
    const val EVENT_TEXT_LONG_PRESS = "topTextLongPress"

    // SELECTION_MODE_DEFAULT 是 Android 当前实现的原生长按选区模式。
    const val SELECTION_MODE_DEFAULT = "default"

    // CLIP_LABEL 是写入 Android 剪贴板时使用的来源标签。
    private const val CLIP_LABEL = "SelectableText"

    // CUSTOM_MENU_GROUP_ID 用于批量移除 SelectableText 自定义菜单项。
    private const val CUSTOM_MENU_GROUP_ID = 0x53454c

    // CUSTOM_MENU_ITEM_ID_OFFSET 给自定义菜单生成不易和系统项冲突的 itemId。
    private const val CUSTOM_MENU_ITEM_ID_OFFSET = 0x53454c00

    // activeTextView 记录当前交互文本块，切换块时清理上一个 Android Selection。
    private var activeTextView: WeakReference<SelectableTextView>? = null

    // clampedRange 把 JS 或菜单传入的选区范围裁剪到当前文本长度内。
    fun clampedRange(start: Int, end: Int, textLength: Int): Pair<Int, Int>? {
      // 起止位置非法、反向或文本为空时返回 null，调用方不改变当前状态。
      if (start < 0 || end <= start || textLength <= 0) {
        return null
      }

      val clampedStart = min(start, textLength)
      val clampedEnd = min(end, textLength)

      // 裁剪后没有实际选中文本时返回 null。
      if (clampedEnd <= clampedStart) {
        return null
      }

      return clampedStart to clampedEnd
    }
  }
}
