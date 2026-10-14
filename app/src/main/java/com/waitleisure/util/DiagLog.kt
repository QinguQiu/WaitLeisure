package com.waitleisure.util

import android.util.Log
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

    /** logcat 用的 tag：adb logcat -s WaitLeisure:D */
    const val LOG_TAG = "WaitLeisure"

    private const val CAPACITY = 40

    private val buffer = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(tag: String, detail: String) {
        val line = formatter.format(Date()) + " [" + tag + "] " + detail
        buffer.addLast(line)
        while (buffer.size > CAPACITY) buffer.removeFirst()
        // 同时打到 logcat。
        // 界面上的环形缓冲只有 40 条，测试时很容易被冲掉；而用 adb 远程调试时，
        // logcat 是唯一能看到服务内部每一步的窗口 —— 之前几轮排查全靠猜，就是因为看不到这个。
        Log.d(LOG_TAG, line)
    }

    /** 返回按时间正序的快照；界面自己决定怎么展示。 */
    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    @Synchronized
    fun clear() = buffer.clear()
}
