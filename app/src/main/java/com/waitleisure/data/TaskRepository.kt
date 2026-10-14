package com.waitleisure.data

import com.waitleisure.model.Task
import com.waitleisure.model.TaskKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 任务的增删改查。
 *
 * 首次使用时预置几个「现成的」任务，这样定时规则里一打开就有东西可选，
 * 不必先去别的页面从头建一个 —— 用户的原话就是「可选择现成的任务」。
 *
 * 预置内容一旦写进 prefs 就是普通数据了，用户可以随便改、随便删；
 * 只有 tasksJson 为空（全新安装 / 被清空）时才重新播种。
 */
class TaskRepository(private val prefs: AppPrefs) {

    private val _tasks = MutableStateFlow(load())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    fun current(): List<Task> = _tasks.value

    fun byId(id: Long): Task? = _tasks.value.firstOrNull { it.id == id }

    fun nextId(): Long = (_tasks.value.maxOfOrNull { it.id } ?: 0L) + 1L

    fun upsert(task: Task) {
        val list = _tasks.value.toMutableList()
        val index = list.indexOfFirst { it.id == task.id }
        if (index >= 0) list[index] = task else list.add(task)
        persist(list)
    }

    fun delete(id: Long) = persist(_tasks.value.filterNot { it.id == id })

    private fun persist(list: List<Task>) {
        prefs.tasksJson = toJson(list)
        _tasks.value = list
    }

    private fun load(): List<Task> {
        val raw = prefs.tasksJson
        if (raw.isBlank() || raw == "[]") {
            // 全新安装：播种预置任务，并立刻落盘 —— 之后用户删光了也不会又冒出来
            val seeds = seedTasks()
            prefs.tasksJson = toJson(seeds)
            return seeds
        }
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }.getOrElse { emptyList() }
    }

    private fun seedTasks(): List<Task> = listOf(
        Task(id = 1, name = "专注 25 分钟", kind = TaskKind.SIMPLE_LOCK, durationMinutes = 25),
        Task(id = 2, name = "锁机 1 小时", kind = TaskKind.SIMPLE_LOCK, durationMinutes = 60),
        Task(id = 3, name = "深度锁机 2 小时", kind = TaskKind.SIMPLE_LOCK, durationMinutes = 120),
        Task(id = 4, name = "番茄钟 4×25", kind = TaskKind.POMODORO, pomodoroConfig = "4,25,5,15,2"),
        Task(id = 5, name = "番茄钟 2×45（长时专注）", kind = TaskKind.POMODORO, pomodoroConfig = "2,45,10,20,2")
    )

    private fun toJson(list: List<Task>): String {
        val array = JSONArray()
        list.forEach { t ->
            array.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("kind", t.kind.name)
                    put("durationMinutes", t.durationMinutes)
                    put("pomodoroConfig", t.pomodoroConfig)
                }
            )
        }
        return array.toString()
    }

    private fun fromJson(json: JSONObject): Task = Task(
        id = json.optLong("id"),
        name = json.optString("name", "未命名任务"),
        kind = runCatching {
            TaskKind.valueOf(json.optString("kind", TaskKind.SIMPLE_LOCK.name))
        }.getOrDefault(TaskKind.SIMPLE_LOCK),
        durationMinutes = json.optInt("durationMinutes", 60),
        pomodoroConfig = json.optString("pomodoroConfig", "4,25,5,15,2")
    )
}
