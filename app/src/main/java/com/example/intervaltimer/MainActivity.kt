package com.example.intervaltimer

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
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
    var phaseTotal by mutableIntStateOf(0); private set  // 当前阶段总秒数，用于进度条
    var round by mutableIntStateOf(1); private set
    var running by mutableStateOf(false); private set

    private var job: Job? = null
    private val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)

    private fun beep(long: Boolean) { tone.startTone(ToneGenerator.TONE_PROP_BEEP, if (long) 500 else 150) }

    private fun enterPhase(p: Phase) {
        phase = p
        val t = when (p) {
            Phase.PREP -> current.prep
            Phase.WORK -> current.work
            Phase.REST -> current.rest
            else -> 0
        }
        remaining = t; phaseTotal = t
    }

    /** 下一阶段预览：名称 to 时间，null 表示即将结束 */
    val nextUp: Pair<String, String>?
        get() {
            val p = current
            return when (phase) {
                Phase.PREP -> "锻炼" to fmt(p.work)
                Phase.WORK -> when {
                    round >= p.sets && (p.skipLastRest || p.rest == 0) -> null
                    p.rest > 0 -> "休息" to fmt(p.rest)
                    else -> "第 ${round + 1} 组 锻炼" to fmt(p.work)
                }
                Phase.REST -> if (round >= p.sets) null else "第 ${round + 1} 组 锻炼" to fmt(p.work)
                else -> null
            }
        }

    private fun finish(): Boolean {
        phase = Phase.DONE; remaining = 0; phaseTotal = 0; running = false; beep(true); return false
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

    fun reset() { job?.cancel(); running = false; phase = Phase.IDLE; remaining = 0; phaseTotal = 0; round = 1 }

    override fun onCleared() { tone.release() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { TimerScreen() } }
    }
}

// ---------- 配色（按 UI 稿） ----------
private val Lime = Color(0xFFC8F135)
private val Blue = Color(0xFF2E5BFF)
private val Amber = Color(0xFFFFB300)
private val PageBg = Color(0xFF0A0A0D)
private val CardBg = Color(0xFF17171B)
private val BtnBg = Color(0xFF26262C)
private val TextPrimary = Color(0xFFF5F5F7)
private val TextDim = Color(0xFF8E8E93)

/** 每个阶段的整屏配色：bg 底色 / fg 主文字色 */
private data class Scheme(val bg: Color, val fg: Color)

@Composable
fun TimerScreen(vm: TimerViewModel = viewModel()) {
    LocalView.current.keepScreenOn = vm.running

    val scheme = when (vm.phase) {
        Phase.PREP -> Scheme(Amber, Color.Black)
        Phase.WORK -> Scheme(Lime, Color.Black)
        Phase.REST -> Scheme(Blue, Color.White)
        Phase.DONE -> Scheme(PageBg, Lime)
        Phase.IDLE -> Scheme(PageBg, TextPrimary)
    }
    val idle = vm.phase == Phase.IDLE

    Surface(Modifier.fillMaxSize(), color = scheme.bg) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
            if (idle) {
                EditorPanel(vm, Modifier.weight(1f))
                StartBar(vm)
            } else {
                RunningPanel(vm, scheme, Modifier.weight(1f))
                ControlsBar(vm, scheme)
            }
        }
    }
}

/** 编辑页（UI 稿 1）：标题 + 方案选择 + 总时长卡 + 2×2 参数卡 + 开关 */
@Composable
private fun EditorPanel(vm: TimerViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 24.dp)) {
        Text("间歇计时器", fontSize = 13.sp, color = TextDim)
        Text("选择方案", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(Modifier.height(16.dp))

        val p = vm.current
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), state = rememberLazyListState()) {
            itemsIndexed(vm.presets) { i, item ->
                FilterChip(
                    selected = i == vm.sel,
                    onClick = { vm.select(i) },
                    label = {
                        Text(item.name, fontWeight = FontWeight.SemiBold,
                            color = if (i == vm.sel) Color.Black else TextPrimary)
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = CardBg, selectedContainerColor = Lime
                    )
                )
            }
            item {
                AssistChip(
                    onClick = vm::addPreset,
                    label = { Text("＋ 新建", color = TextDim) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = Color.Transparent)
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // 总时长主卡
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg).padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("总时长", fontSize = 13.sp, color = TextDim)
                Text(fmt(p.totalSeconds), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Lime)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${p.sets} 组", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text("练 ${fmt(p.work)} · 休 ${fmt(p.rest)}", fontSize = 12.sp, color = TextDim)
            }
        }
        Spacer(Modifier.height(12.dp))

        // 2×2 参数卡：预备按 1 秒步进，其余按 5 秒
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ParamCard("组数", "${p.sets}", Modifier.weight(1f),
                onMinus = { vm.update(p.copy(sets = (p.sets - 1).coerceAtLeast(1))) },
                onPlus = { vm.update(p.copy(sets = p.sets + 1)) })
            ParamCard("锻炼", fmt(p.work), Modifier.weight(1f),
                onMinus = { vm.update(p.copy(work = (p.work - 5).coerceAtLeast(5))) },
                onPlus = { vm.update(p.copy(work = p.work + 5)) })
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ParamCard("休息", fmt(p.rest), Modifier.weight(1f),
                onMinus = { vm.update(p.copy(rest = (p.rest - 5).coerceAtLeast(0))) },
                onPlus = { vm.update(p.copy(rest = p.rest + 5)) })
            ParamCard("预备", fmt(p.prep), Modifier.weight(1f),
                onMinus = { vm.update(p.copy(prep = (p.prep - 1).coerceAtLeast(0))) },
                onPlus = { vm.update(p.copy(prep = p.prep + 1)) })
        }
        Spacer(Modifier.height(12.dp))

        // 跳过最后一次休息
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg).padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("跳过最后一次休息", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text("最后一组结束后直接完成", fontSize = 12.sp, color = TextDim)
            }
            Switch(
                checked = p.skipLastRest,
                onCheckedChange = { vm.update(p.copy(skipLastRest = it)) },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Lime, checkedThumbColor = Color.Black,
                    uncheckedTrackColor = BtnBg, uncheckedThumbColor = TextDim
                )
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ParamCard(label: String, value: String, modifier: Modifier = Modifier, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(CardBg).padding(16.dp)) {
        Text(label, fontSize = 13.sp, color = TextDim)
        Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StepButton("−", onMinus, Modifier.weight(1f))
            StepButton("+", onPlus, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).background(BtnBg).clickable(onClick = onClick)
    ) {
        Text(symbol, fontSize = 22.sp, color = TextPrimary)
    }
}

@Composable
private fun StartBar(vm: TimerViewModel) {
    Column(Modifier.padding(top = 8.dp, bottom = 16.dp)) {
        Button(
            onClick = vm::toggle,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(30.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Color.Black)
        ) {
            Text("▶  开始", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (vm.presets.size > 1) {
            TextButton(onClick = vm::deletePreset, modifier = Modifier.fillMaxWidth()) {
                Text("删除当前方案", fontSize = 13.sp, color = TextDim)
            }
        }
    }
}

/** 计时页（UI 稿 2/3）：顶部名称与组数 + 分段进度 + 大倒计时 + 下一阶段预览 */
@Composable
private fun RunningPanel(vm: TimerViewModel, scheme: Scheme, modifier: Modifier = Modifier) {
    val p = vm.current
    val fraction = if (vm.phaseTotal > 0) 1f - vm.remaining.toFloat() / vm.phaseTotal else 0f
    val (completed, partial) = when (vm.phase) {
        Phase.PREP -> 0 to fraction
        Phase.WORK -> (vm.round - 1) to fraction
        Phase.REST -> vm.round to 0f
        else -> p.sets to 0f
    }

    Column(modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(p.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = scheme.fg)
            if (vm.phase != Phase.DONE) {
                Text("第 ${vm.round}/${p.sets} 组", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = scheme.fg)
            }
        }
        Spacer(Modifier.height(12.dp))
        Segments(total = p.sets, completed = completed, partial = partial, fg = scheme.fg)

        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(vm.phase.label, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = scheme.fg, letterSpacing = 6.sp)
            Spacer(Modifier.height(8.dp))
            CountdownText(fmt(vm.remaining), scheme.fg)
        }

        // 下一阶段预览
        vm.nextUp?.let { (name, time) ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (vm.phase == Phase.REST) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.12f))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Text("下一阶段", fontSize = 14.sp, color = scheme.fg.copy(alpha = 0.7f))
                Text("$name  $time", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = scheme.fg)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 倒计时：逐字符定宽槽位 + 字号抵消系统字体缩放，任何设备上都不会重叠溢出 */
@Composable
private fun CountdownText(time: String, color: Color) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val digitSlot = maxWidth * 0.2f
        val colonSlot = maxWidth * 0.1f
        // dp 推导转 sp 后再除以 fontScale：字号只由屏幕宽度决定
        val fontSize = with(density) { (digitSlot / 0.9f).coerceAtMost(90.dp).toSp() } / density.fontScale
        Row(verticalAlignment = Alignment.CenterVertically) {
            time.forEach { c ->
                Box(Modifier.width(if (c == ':') colonSlot else digitSlot), contentAlignment = Alignment.Center) {
                    Text(c.toString(), fontSize = fontSize, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
                }
            }
        }
    }
}

/** 每组一格的分段进度条 */
@Composable
private fun Segments(total: Int, completed: Int, partial: Float, fg: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            val fill = when {
                i < completed -> 1f
                i == completed && partial > 0f -> partial.coerceIn(0f, 1f)
                else -> 0f
            }
            Box(
                Modifier.weight(1f).height(6.dp)
                    .clip(RoundedCornerShape(3.dp)).background(fg.copy(alpha = 0.25f))
            ) {
                Box(
                    Modifier.fillMaxHeight()
                        .fillMaxWidth(fill)
                        .clip(RoundedCornerShape(3.dp)).background(fg)
                )
            }
        }
    }
}

/** 底部控制：重置 + 播放/暂停 */
@Composable
private fun ControlsBar(vm: TimerViewModel, scheme: Scheme) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(56.dp).clip(CircleShape)
                .border(2.dp, scheme.fg.copy(alpha = 0.8f), CircleShape).clickable(onClick = vm::reset)
        ) {
            Text("↺", fontSize = 26.sp, color = scheme.fg)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(84.dp).clip(CircleShape).background(scheme.fg).clickable(onClick = vm::toggle)
        ) {
            if (vm.running) PauseGlyph(scheme.bg) else PlayGlyph(scheme.bg)
        }
    }
}

@Composable
private fun PauseGlyph(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(width = 8.dp, height = 26.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Box(Modifier.size(width = 8.dp, height = 26.dp).clip(RoundedCornerShape(2.dp)).background(color))
    }
}

@Composable
private fun PlayGlyph(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val path = Path().apply {
            moveTo(2f, 0f)
            lineTo(size.width, size.height / 2)
            lineTo(2f, size.height)
            close()
        }
        drawPath(path, color)
    }
}

private fun fmt(s: Int) = "%02d:%02d".format(s / 60, s % 60)
