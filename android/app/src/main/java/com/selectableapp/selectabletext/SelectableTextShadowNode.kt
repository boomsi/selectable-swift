package com.selectableapp.selectabletext

import com.facebook.react.views.text.ReactTextShadowNode
import com.facebook.react.views.text.ReactTextViewManagerCallback

// SelectableTextShadowNode 复用 RN Text 的 Spannable 构建和 Yoga 测量逻辑。
class SelectableTextShadowNode(
    reactTextViewManagerCallback: ReactTextViewManagerCallback? = null
) : ReactTextShadowNode(reactTextViewManagerCallback)
