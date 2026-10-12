package com.example.intervaltimer

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class Phase(val label: String) {
    IDLE("准备"), PREP("预备"), WORK("锻炼"), REST("休息"), DONE("完成")
}

/** 一个运动方案：名称、预备秒数、组数、锻炼秒数、休息秒数、是否跳过最后一次休息 */
data class Preset(
    val name: String,
    val prep: Int = 5,
    val sets: Int = 5,
    val work: Int = 30,
    val rest: Int = 60,
    val skipLastRest: Boolean = true
) {
    val totalSeconds get() = prep + work * sets + rest * (if (skipLastRest) sets - 1 else sets)

    fun toJson() = JSONObject().put("name", name).put("prep", prep).put("sets", sets)
        .put("work", work).put("rest", rest).put("skip", skipLastRest)

    companion object {
        fun fromJson(o: JSONObject) = Preset(
            o.getString("name"), o.getInt("prep"), o.getInt("sets"),
            o.getInt("work"), o.getInt("rest"), o.getBoolean("skip")
        )
    }
}

class TimerViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("timer", Context.MODE_PRIVATE)

    // ---------- 方案管理 ----------
    val presets = mutableStateListOf<Preset>()
    var sel by mutableIntStateOf(0); private set
    val current get() = presets[sel]

    init {
        val saved = prefs.getString("presets", null)
        if (saved != null) {
            val arr = JSONArray(saved)
            for (i in 0 until arr.length()) presets.add(Preset.fromJson(arr.getJSONObject(i)))
            sel = prefs.getInt("sel", 0).coerceIn(0, presets.lastIndex)
        }
        if (presets.isEmpty()) {
            presets.add(Preset("引体向上", prep = 5, sets = 5, work = 40, rest = 90))
            presets.add(Preset("跳绳", prep = 5, sets = 8, work = 60, rest = 30))
        }
    }

    private fun save() {
        val arr = JSONArray().also { a -> presets.forEach { a.put(it.toJson()) } }
        prefs.edit().putString("presets", arr.toString()).putInt("sel", sel).apply()
    }

    fun select(i: Int) { sel = i; save() }
    fun update(p: Preset) { presets[sel] = p; save() }
    fun addPreset() { presets.add(Preset("新方案")); sel = presets.lastIndex; save() }
    fun deletePreset() {
        if (presets.size <= 1) return
        presets.removeAt(sel); sel = sel.coerceAtMost(presets.lastIndex); save()
    }

    // ---------- 计时 ----------
    var phase by mutableStateOf(Phase.IDLE); private set
    var remaining by mutableIntStateOf(0); private set
    var round by mutableIntStateOf(1); private set
    var running by mutableStateOf(false); private set

    private var job: Job? = null
    private val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)

    private fun beep(long: Boolean) { tone.startTone(ToneGenerator.TONE_PROP_BEEP, if (long) 500 else 150) }

    private fun setPhase(p: Phase) {
        phase = p
        remaining = when (p) {
            Phase.PREP -> current.prep
            Phase.WORK -> current.work
            Phase.REST -> current.rest
            else -> 0
        }
    }

    private fun finish(): Boolean {
        phase = Phase.DONE; remaining = 0; running = false; beep(true); return false
    }

    /** 进入下一阶段，返回 false 表示全部结束 */
    private fun next(): Boolean {
        val p = current
        when (phase) {
            Phase.PREP -> setPhase(Phase.WORK)
            Phase.WORK -> when {
                round >= p.sets && (p.skipLastRest || p.rest == 0) -> return finish()
                p.rest > 0 -> setPhase(Phase.REST)
                else -> { round++; setPhase(Phase.WORK) }
            }
            Phase.REST -> if (round >= p.sets) return finish() else { round++; setPhase(Phase.WORK) }
            else -> return false
        }
        beep(true)
        return true
    }

    fun toggle() {
        if (running) { job?.cancel(); running = false; return }
        if (phase == Phase.IDLE || phase == Phase.DONE) {
            round = 1
            setPhase(if (current.prep > 0) Phase.PREP else Phase.WORK)
        }
        running = true
        job = viewModelScope.launch {
            while (true) {
                delay(1000)
                remaining--
                if (remaining in 1..3) beep(false)
                if (remaining <= 0 && !next()) break
            }
        }
    }

    fun reset() { job?.cancel(); running = false; phase = Phase.IDLE; remaining = 0; round = 1 }

    override fun onCleared() { tone.release() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { TimerScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(vm: TimerViewModel = viewModel()) {
    LocalView.current.keepScreenOn = vm.running

    val idle = vm.phase == Phase.IDLE
    val bg = when (vm.phase) {
        Phase.PREP -> Color(0xFFF9A825)
        Phase.WORK -> Color(0xFF2E7D32)
        Phase.REST -> Color(0xFF1565C0)
        Phase.DONE -> Color(0xFF6A1B9A)
        Phase.IDLE -> MaterialTheme.colorScheme.background
    }

    Column(Modifier.fillMaxSize().background(bg).systemBarsPadding().padding(24.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().then(if (idle) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (idle) Arrangement.Top else Arrangement.Center
        ) {
            if (idle) {
                val p = vm.current
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(vm.presets) { i, item ->
                        FilterChip(selected = i == vm.sel, onClick = { vm.select(i) }, label = { Text(item.name) })
                    }
                    item { AssistChip(onClick = vm::addPreset, label = { Text("＋ 新建") }) }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = p.name, onValueChange = { vm.update(p.copy(name = it)) },
                    label = { Text("方案名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Stepper("预备(秒)", p.prep, 0, 5) { vm.update(p.copy(prep = it)) }
                Stepper("组数", p.sets, 1, 1) { vm.update(p.copy(sets = it)) }
                Stepper("锻炼(秒)", p.work, 5, 5) { vm.update(p.copy(work = it)) }
                Stepper("休息(秒)", p.rest, 0, 5) { vm.update(p.copy(rest = it)) }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("跳过最后一次休息", fontSize = 20.sp)
                    Switch(checked = p.skipLastRest, onCheckedChange = { vm.update(p.copy(skipLastRest = it)) })
                }
                Spacer(Modifier.height(8.dp))
                Text("总时长 ${fmt(p.totalSeconds)}", fontSize = 18.sp)
            } else {
                Text(vm.current.name, fontSize = 20.sp)
                Text(vm.phase.label, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text(fmt(vm.remaining), fontSize = 120.sp, fontWeight = FontWeight.Bold)
                if (vm.phase != Phase.DONE) Text("第 ${vm.round} / ${vm.current.sets} 组", fontSize = 24.sp)
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
            Button(onClick = vm::toggle) {
                Text(if (vm.running) "暂停" else if (idle || vm.phase == Phase.DONE) "开始" else "继续")
            }
            if (!idle) OutlinedButton(onClick = vm::reset) { Text("重置") }
            if (idle && vm.presets.size > 1) OutlinedButton(onClick = vm::deletePreset) { Text("删除方案") }
        }
    }
}

@Composable
fun Stepper(label: String, value: Int, min: Int, step: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 20.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Text("−") }
            Text("$value", fontSize = 24.sp, modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
            FilledTonalButton(onClick = { onChange(value + step) }) { Text("+") }
        }
    }
}

private fun fmt(s: Int) = "%02d:%02d".format(s / 60, s % 60)
