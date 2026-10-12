package com.selflock.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简诊断日志：只留最近几十条，存在内存里，不落盘、不上报、不联网。
 *
 * 为什么需要它：像「按 Home 回桌面拦不住、但点通知能拦住」这类问题，
 * 光读代码猜不出来 —— 必须看无障碍服务实际收到了哪些窗口事件、每一步走了哪个分支。
 * 猜了三轮都错之后加的。
 */
object DiagLog {

    private const val CAPACITY = 40

    private val buffer = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(tag: String, detail: String) {
        buffer.addLast(formatter.format(Date()) + " [" + tag + "] " + detail)
        while (buffer.size > CAPACITY) buffer.removeFirst()
    }

    /** 返回按时间正序的快照；界面自己决定怎么展示。 */
    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    @Synchronized
    fun clear() = buffer.clear()
}
