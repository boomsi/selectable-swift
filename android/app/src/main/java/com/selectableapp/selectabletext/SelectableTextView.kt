package com.selectableapp.selectabletext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.text.Selection
import android.text.Spannable
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.PixelUtil
import com.facebook.react.uimanager.events.RCTEventEmitter
import com.facebook.react.views.text.ReactTextView
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

  // selectionMode 控制长按直接进入系统选区，还是先回调 JS 展示 RN 段落菜单。
  var selectionMode: String = SELECTION_MODE_DEFAULT
    set(value) {
      field = value
      updateNativeSelectableState()
    }

  // currentActionMode 记录当前浮动菜单，用于属性更新、清理选区和释放父级手势拦截。
  private var currentActionMode: ActionMode? = null

  // rnSelectable 保存 JS selectable prop 的值，selectionMode 会基于它计算真实原生可选状态。
  private var rnSelectable: Boolean = true

  // paragraphLongPressDetector 在 menuThenParagraph 模式下只负责命中段落并通知 JS。
  private val paragraphLongPressDetector =
      GestureDetector(
          context,
          object : GestureDetector.SimpleOnGestureListener() {
            // onDown 返回 true，保证后续 onLongPress 能收到同一轮触摸事件。
            override fun onDown(event: MotionEvent): Boolean = true

            // onLongPress 只在业务菜单模式下触发 RN 段落菜单，不直接进入 TextView 选区。
            override fun onLongPress(event: MotionEvent) {
              handleParagraphLongPress(event)
            }
          })

  // selectionActionModeCallback 统一处理系统菜单保留策略、自定义菜单、菜单定位和 JS 回调。
  private val selectionActionModeCallback =
      object : ActionMode.Callback2() {
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
          }

          return true
        }

        // onDestroyActionMode 在系统菜单关闭时释放父 ScrollView 的触摸拦截限制。
        override fun onDestroyActionMode(mode: ActionMode) {
          currentActionMode = null
          parent?.requestDisallowInterceptTouchEvent(false)
          restoreMenuThenParagraphTouchStateAfterActionMode()
        }

        // onGetContentRect 把浮动菜单锚点绑定到当前选区首行，而不是整个 SelectableText 视图。
        override fun onGetContentRect(mode: ActionMode, view: android.view.View, outRect: Rect) {
          selectedTextContentRect(outRect)
        }
      }

  init {
    // ReactTextView 默认 selectable=false，这里默认开启以匹配 SelectableText 的组件语义。
    setTextIsSelectable(true)
    setCustomSelectionActionModeCallback(selectionActionModeCallback)
  }

  // setTextIsSelectable 接收 RN selectable prop，并同步到底层 Android TextView。
  override fun setTextIsSelectable(selectable: Boolean) {
    rnSelectable = selectable
    updateNativeSelectableState()
  }

  // onTouchEvent 在不同 SelectableText 之间切换时清理旧选区，并在拖动手柄时保护父级手势。
  override fun onTouchEvent(event: MotionEvent): Boolean {
    // ACTION_DOWN 表示用户开始和当前文本块交互，需要清掉上一个文本块的残留选区。
    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
      markAsActiveSelectableTextView()
    }

    // menuThenParagraph 未选中时长按只弹 RN 菜单；已有选区时交还 TextView 处理手柄拖动。
    if (isMenuThenParagraphMode() && !hasInteractiveSelection()) {
      paragraphLongPressDetector.onTouchEvent(event)

      // 手指抬起或取消时释放父级拦截限制，普通上下滑动仍交给 ScrollView 判断。
      if (
          event.actionMasked == MotionEvent.ACTION_UP ||
              event.actionMasked == MotionEvent.ACTION_CANCEL) {
        parent?.requestDisallowInterceptTouchEvent(false)
      }

      return true
    }

    // 当前文本已经有选区时，后续拖动通常是选择手柄移动，不应被 ScrollView 抢走。
    if (hasInteractiveSelection()) {
      parent?.requestDisallowInterceptTouchEvent(true)
    }

    val handled = super.onTouchEvent(event)

    // 触摸结束且没有选区时，把滚动拦截权还给父 ScrollView。
    if (
        (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) &&
            !hasInteractiveSelection()) {
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
    val actionMode = currentActionMode
    val hasSelection = hasActiveSelection()
    val currentText = text
    val selectableText =
        when {
          currentText is Spannable -> currentText
          hasSelection || actionMode != null -> ensureSpannableText()
          else -> null
        }

    // 有真实选区或 ActionMode 时才改 Selection，避免首次长按时无意义 setText 触发布局重建。
    if (selectableText != null) {
      Selection.removeSelection(selectableText)
    }

    actionMode?.finish()
    clearFocus()
    updateNativeSelectableState()
    parent?.requestDisallowInterceptTouchEvent(false)
    invalidate()
  }

  // selectRange 根据 JS 菜单命令临时开启原生选择能力，并设置指定段落选区。
  fun selectRange(start: Int, end: Int) {
    markAsActiveSelectableTextView()
    super.setTextIsSelectable(true)

    val selectableText = ensureSpannableText()
    val range = clampedRange(start, end, selectableText?.length ?: 0)

    // range 无效或文本无法转成 Spannable 时，不能安全写入 Selection span。
    if (range == null || selectableText == null) {
      return
    }

    requestFocusFromTouch()
    parent?.requestDisallowInterceptTouchEvent(true)
    setSelectionThroughTextViewAction(range)
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

    emitDirectEvent(EVENT_MENU_ACTION, event)
  }

  // invalidateActionMode 在菜单相关 props 更新时请求 Android 刷新当前 ActionMode。
  private fun invalidateActionMode() {
    // 只有已有 ActionMode 时才需要刷新菜单，未选中文本时无需做任何 UI 更新。
    if (currentActionMode != null) {
      currentActionMode?.invalidate()
    }
  }

  // ensureSpannableText 确保 Android Selection 能写入当前 TextView 文本 buffer。
  private fun ensureSpannableText(): Spannable? {
    val currentText = text

    // 已经是 Spannable 时可以直接写 Selection span。
    if (currentText is Spannable) {
      return currentText
    }

    // 普通 SpannedString 需要切换成 SPANNABLE buffer，否则 Selection.setSelection 不会生效。
    setText(currentText, TextView.BufferType.SPANNABLE)

    val updatedText = text

    // Android TextView 理论上会返回 Spannable；如果系统实现异常，则调用方不继续选区操作。
    if (updatedText !is Spannable) {
      return null
    }

    return updatedText
  }

  // setSelectionThroughTextViewAction 使用 TextView 的公开无障碍选区动作，同时触发系统 selection mode。
  private fun setSelectionThroughTextViewAction(selectionRange: Pair<Int, Int>) {
    val arguments = Bundle().apply {
      putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selectionRange.first)
      putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selectionRange.second)
    }

    // ACTION_SET_SELECTION 内部会调用 TextView 的 startSelectionActionModeAsync，创建系统菜单和手柄。
    performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
  }

  // isMenuThenParagraphMode 判断当前是否使用“先弹 RN 菜单，再选中段落”的业务模式。
  private fun isMenuThenParagraphMode(): Boolean = selectionMode == SELECTION_MODE_MENU_THEN_PARAGRAPH

  // updateNativeSelectableState 根据 selectable 和 selectionMode 同步 Android TextView 真实可选状态。
  private fun updateNativeSelectableState() {
    super.setTextIsSelectable(if (isMenuThenParagraphMode()) false else rnSelectable)
  }

  // restoreMenuThenParagraphTouchStateAfterActionMode 在系统选区关闭后恢复 RN 菜单优先的长按状态。
  private fun restoreMenuThenParagraphTouchStateAfterActionMode() {
    // 只在 menuThenParagraph 模式下恢复，默认模式仍保留 Android TextView 的原生可选行为。
    if (!isMenuThenParagraphMode()) {
      return
    }

    post {
      updateNativeSelectableState()
      clearFocus()
    }
  }

  // handleParagraphLongPress 计算长按命中的段落范围，并把菜单定位信息回传给 JS。
  private fun handleParagraphLongPress(event: MotionEvent) {
    // 非业务菜单模式不处理段落长按，避免干扰默认原生选区流程。
    if (!isMenuThenParagraphMode()) {
      return
    }

    markAsActiveSelectableTextView()

    val paragraphRange = paragraphRangeAtPoint(event.x, event.y)

    // 没有命中文本时不弹 RN 菜单，避免空白区域长按产生无效菜单。
    if (paragraphRange == null) {
      return
    }

    clearSelection()

    val windowLocation = IntArray(2)
    getLocationInWindow(windowLocation)
    val pageX = windowLocation[0].toFloat() + event.x
    val pageY = windowLocation[1].toFloat() + event.y
    val eventPayload = Arguments.createMap().apply {
      putString(
          "paragraphText",
          text.subSequence(paragraphRange.first, paragraphRange.second).toString())
      putInt("selectionStart", paragraphRange.first)
      putInt("selectionEnd", paragraphRange.second)
      putDouble("locationX", PixelUtil.toDIPFromPixel(event.x).toDouble())
      putDouble("locationY", PixelUtil.toDIPFromPixel(event.y).toDouble())
      putDouble("pageX", PixelUtil.toDIPFromPixel(pageX).toDouble())
      putDouble("pageY", PixelUtil.toDIPFromPixel(pageY).toDouble())
    }

    parent?.requestDisallowInterceptTouchEvent(true)
    emitDirectEvent(EVENT_TEXT_LONG_PRESS, eventPayload)
  }

  // selectedTextContentRect 计算当前选区首行在 TextView 内部坐标系里的矩形，用于系统菜单定位。
  private fun selectedTextContentRect(outRect: Rect) {
    val currentLayout = layout
    val selectionStart = Selection.getSelectionStart(text)
    val selectionEnd = Selection.getSelectionEnd(text)
    val normalizedStart = min(selectionStart, selectionEnd)
    val normalizedEnd = max(selectionStart, selectionEnd)

    // 没有布局或有效选区时，返回空 rect，让系统使用自己的默认定位。
    if (currentLayout == null || normalizedStart < 0 || normalizedEnd <= normalizedStart) {
      outRect.setEmpty()
      return
    }

    val line = currentLayout.getLineForOffset(normalizedStart)
    val lineSelectionEnd = min(normalizedEnd, currentLayout.getLineEnd(line))
    val startX = currentLayout.getPrimaryHorizontal(normalizedStart)
    val endX = currentLayout.getPrimaryHorizontal(lineSelectionEnd)
    val left = min(startX, endX) + totalPaddingLeft - scrollX
    val right = max(startX, endX) + totalPaddingLeft - scrollX
    val top = currentLayout.getLineTop(line).toFloat() + totalPaddingTop - scrollY
    val bottom = currentLayout.getLineBottom(line).toFloat() + totalPaddingTop - scrollY

    outRect.set(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
  }

  // paragraphRangeAtPoint 把长按坐标映射到字符 index，并按换行切出所在段落。
  private fun paragraphRangeAtPoint(x: Float, y: Float): Pair<Int, Int>? {
    val layout = layout
    val currentText = text

    // 文本或布局为空时没有可命中的段落。
    if (currentText.isEmpty() || layout == null) {
      return null
    }

    val contentX = x - totalPaddingLeft + scrollX
    val contentY = y - totalPaddingTop + scrollY

    // 点击在文本布局垂直范围外时不返回段落。
    if (contentY < 0 || contentY > layout.height) {
      return null
    }

    val line = layout.getLineForVertical(contentY.toInt())

    // 点击在当前行文字左右范围外时不返回段落，避免空白区域误触发。
    if (contentX < layout.getLineLeft(line) || contentX > layout.getLineRight(line)) {
      return null
    }

    val charIndex = layout.getOffsetForHorizontal(line, contentX)
    val safeCharIndex = min(charIndex, currentText.length - 1)
    var start = safeCharIndex
    var end = safeCharIndex

    // 向前找到当前段落的换行边界。
    while (start > 0 && currentText[start - 1] != '\n') {
      start--
    }

    // 向后找到当前段落的换行边界。
    while (end < currentText.length && currentText[end] != '\n') {
      end++
    }

    // 长按命中空行时不返回段落。
    if (end <= start) {
      return null
    }

    return start to end
  }

  // emitDirectEvent 通过 RN 旧架构事件通道发送 SelectableText 的 direct event。
  private fun emitDirectEvent(eventName: String, eventPayload: WritableMap) {
    // 只有 ReactContext 才能发送 RN 旧架构 direct event。
    if (context is ReactContext) {
      (context as ReactContext)
          .getJSModule(RCTEventEmitter::class.java)
          .receiveEvent(id, eventName, eventPayload)
    }
  }

  // hasActiveSelection 判断当前 TextView 是否持有一个非空文本选区。
  private fun hasActiveSelection(): Boolean {
    val selectionStart = Selection.getSelectionStart(text)
    val selectionEnd = Selection.getSelectionEnd(text)
    return selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd
  }

  // hasInteractiveSelection 判断当前非空选区是否仍处在系统 ActionMode 交互生命周期里。
  private fun hasInteractiveSelection(): Boolean = currentActionMode != null && hasActiveSelection()

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

    // EVENT_TEXT_LONG_PRESS 是 Android 命中段落后发送给 JS RN 菜单的 direct event 名。
    const val EVENT_TEXT_LONG_PRESS = "topTextLongPress"

    // SELECTION_MODE_DEFAULT 是 Android 当前实现的原生长按选区模式。
    const val SELECTION_MODE_DEFAULT = "default"

    // SELECTION_MODE_MENU_THEN_PARAGRAPH 是先通知 JS 弹菜单，再由菜单命令选中段落的模式。
    private const val SELECTION_MODE_MENU_THEN_PARAGRAPH = "menuThenParagraph"

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
