package com.ltx

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/* 滑动事件中心 */
object SlideEventHub {
    private val _eventFlow = MutableSharedFlow<SlideEvent>(extraBufferCapacity = 64)
    val eventFlow = _eventFlow.asSharedFlow()

    /**
     * 发送事件
     * 
     * @param event 事件
     */
    fun sendEvent(event: SlideEvent) {
        _eventFlow.tryEmit(event)
    }
}

/* 滑动事件 */
sealed class SlideEvent {
    /* 强行停止滑动事件 */
    object ForceStop : SlideEvent()
}
