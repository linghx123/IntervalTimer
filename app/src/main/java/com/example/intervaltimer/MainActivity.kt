package com.example.intervaltimer

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
            try {
                val arr = JSONArray(saved)
                for (i in 0 until arr.length()) presets.add(Preset.fromJson(arr.getJSONObject(i)))
                if (presets.isNotEmpty()) sel = prefs.getInt("sel", 0).coerceIn(0, presets.lastIndex)
            } catch (_: Exception) {
                presets.clear()  // 存储数据损坏时回退到默认方案，避免每次启动都崩溃
            }
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

    private fun enterPhase(p: Phase) {
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
            Phase.PREP -> enterPhase(Phase.WORK)
            Phase.WORK -> when {
                round >= p.sets && (p.skipLastRest || p.rest == 0) -> return finish()
                p.rest > 0 -> enterPhase(Phase.REST)
                else -> { round++; enterPhase(Phase.WORK) }
            }
            Phase.REST -> if (round >= p.sets) return finish() else { round++; enterPhase(Phase.WORK) }
            else -> return false
        }
        beep(true)
        return true
    }

    fun toggle() {
        if (running) { job?.cancel(); running = false; return }
        if (phase == Phase.IDLE || phase == Phase.DONE) {
            round = 1
            enterPhase(if (current.prep > 0) Phase.PREP else Phase.WORK)
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

private val CardBg = Color(0xFF1E1E23)
private val TextPrimary = Color(0xFFF5F5F5)
private val TextDim = Color(0xFF9E9E9E)

@Composable
fun TimerScreen(vm: TimerViewModel = viewModel()) {
    LocalView.current.keepScreenOn = vm.running

    val idle = vm.phase == Phase.IDLE
    val bg = when (vm.phase) {
        Phase.PREP -> Color(0xFFF9A825)
        Phase.WORK -> Color(0xFF2E7D32)
        Phase.REST -> Color(0xFF1565C0)
        Phase.DONE -> Color(0xFF6A1B9A)
        Phase.IDLE -> Color(0xFF121216)
    }

    Surface(Modifier.fillMaxSize(), color = bg) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
            if (idle) {
                EditorPanel(vm, Modifier.weight(1f))
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(vm.current.name, fontSize = 22.sp, color = Color.White.copy(alpha = 0.85f))
                    Text(vm.phase.label, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(fmt(vm.remaining), fontSize = 120.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    if (vm.phase != Phase.DONE) {
                        Text("第 ${vm.round} / ${vm.current.sets} 组", fontSize = 24.sp, color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }

            if (idle) {
                Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
                    Button(
                        onClick = vm::toggle,
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        shape = RoundedCornerShape(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                    ) {
                        Text("开始锻炼", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(fmt(vm.current.totalSeconds), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (vm.presets.size > 1) {
                        TextButton(onClick = vm::deletePreset, modifier = Modifier.fillMaxWidth()) {
                            Text("删除当前方案", fontSize = 14.sp, color = TextDim)
                        }
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                ) {
                    Button(
                        onClick = vm::toggle,
                        modifier = Modifier.height(64.dp),
                        shape = RoundedCornerShape(32.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                    ) {
                        Text(if (vm.running) "暂停" else if (vm.phase == Phase.DONE) "开始" else "继续", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = vm::reset,
                        modifier = Modifier.height(64.dp),
                        shape = RoundedCornerShape(32.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("重置", fontSize = 20.sp)
                    }
                }
            }
        }
    }
}

/** 编辑页：方案选择 + 参数卡片 */
@Composable
private fun EditorPanel(vm: TimerViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp)) {
        val p = vm.current
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), state = rememberLazyListState()) {
            itemsIndexed(vm.presets) { i, item ->
                FilterChip(selected = i == vm.sel, onClick = { vm.select(i) }, label = { Text(item.name) })
            }
            item { AssistChip(onClick = vm::addPreset, label = { Text("＋ 新建") }) }
        }
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = p.name, onValueChange = { vm.update(p.copy(name = it)) },
            label = { Text("方案名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))

        StepperCard("组数", TextDim, "${p.sets}",
            onMinus = { vm.update(p.copy(sets = (p.sets - 1).coerceAtLeast(1))) },
            onPlus = { vm.update(p.copy(sets = p.sets + 1)) }
        )
        StepperCard("预备", Color(0xFFFFB74D), fmt(p.prep),
            onMinus = { vm.update(p.copy(prep = (p.prep - 5).coerceAtLeast(0))) },
            onPlus = { vm.update(p.copy(prep = p.prep + 5)) }
        )
        StepperCard("锻炼", Color(0xFF66BB6A), fmt(p.work),
            onMinus = { vm.update(p.copy(work = (p.work - 5).coerceAtLeast(5))) },
            onPlus = { vm.update(p.copy(work = p.work + 5)) }
        )
        StepperCard("休息", Color(0xFF64B5F6), fmt(p.rest),
            onMinus = { vm.update(p.copy(rest = (p.rest - 5).coerceAtLeast(0))) },
            onPlus = { vm.update(p.copy(rest = p.rest + 5)) }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("跳过最后一次休息", fontSize = 16.sp, color = TextDim)
                Switch(checked = p.skipLastRest, onCheckedChange = { vm.update(p.copy(skipLastRest = it)) })
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 一张参数卡片：彩色标签 + 超大数值 + 左右加减 */
@Composable
private fun StepperCard(
    label: String,
    labelColor: Color,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    bottom: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp)
            .clip(RoundedCornerShape(24.dp)).background(CardBg)
    ) {
        Text(
            label, color = labelColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StepButton("−", onMinus)
            Text(
                value, fontSize = 52.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
            )
            StepButton("+", onPlus)
        }
        if (bottom != null) bottom()
    }
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(64.dp).clip(CircleShape).clickable(onClick = onClick)
    ) {
        Text(symbol, fontSize = 38.sp, color = TextPrimary, fontWeight = FontWeight.Light)
    }
}

private fun fmt(s: Int) = "%02d:%02d".format(s / 60, s % 60)
