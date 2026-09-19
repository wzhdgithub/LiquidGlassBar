package io.github.wzhdgithub.liquidglass

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

// ==================== 真·液态玻璃底栏（KernelSU 同款实现路径） ====================
//
// 原理（与 KernelSU manager 的 FloatingBottomBar 一致）：
//   1. 内容整屏绘制并挂到 LayerBackdrop 图层（Modifier.layerBackdrop）；
//   2. 底栏浮在内容之上，用 Modifier.drawBackdrop 采样该图层，做
//      模糊（blur）+ 饱和度增强（vibrancy）+ 边缘折射（lens）+ 高光描边（highlight）；
//   3. 选中胶囊同样由 drawBackdrop 绘制：它采样「应用内容 + 图标层副本」的合成
//      backdrop（CombinedBackdrop），按住时透镜折射放大图标并带色散，形成
//      iOS 液态玻璃的「放大镜」按压反馈；
//   4. 支持按住拖动切换 Tab（DampedDragAnimation）、越界橡皮筋、按压光斑
//      （InteractiveHighlight）与随重力方向旋转的镜面高光。
//
// 该路径依赖 API 33 的 RuntimeShader，miuix-blur 因此声明 minSdk 33
// （已在本模块 AndroidManifest 用 tools:overrideLibrary 放行）。本文件所有 miuix-blur
// API 仅在 API 33+ 才会被执行：调用方必须先经 isGlassBlurSupported() 判断，
// 不可用时回退到自己的普通底栏（见 GlassShell 的 fallbackBar 参数），
// 保证低版本设备不会加载该类库。
//
// 本文件随本仓库按 GPL-3.0-or-later 分发，详见根目录 LICENSE 与 NOTICE.md。

private val GlassBarShape = RoundedCornerShape(28.dp)

// ==================== 玻璃手感参数（更黏更弹） ====================
// 集中在此处便于调参：数值越大越"液态"，越小越克制。按下即进入形变态（无长按门槛）。

/** 松手时的惯性预测时长（s）：越大越"滑"，快速甩动更易吸附到相邻 Tab。 */
private const val INERTIA_PREDICT_SECONDS = 0.22f

/** 按压力度带来的玻璃厚度增益（模糊/折射强度 = 1 + 增益×进度）。 */
private const val THICKNESS_GAIN_PRESS = 0.25f

/**
 * 拖动幅度（处于两 Tab 之间）带来的额外厚度增益。
 * 刻意压到很小：厚度会改变底栏自身边缘的折射带宽度，肉眼上就像"底栏背景跟着手指动"，
 * 因此拖动时底栏本体的玻璃只允许极轻微的呼吸，视觉焦点留在胶囊放大镜上。
 */
private const val THICKNESS_GAIN_FLOW = 0.08f

/** 未按下时，拖动幅度仍按该基准参与厚度计算（数值越小底栏本体越稳定）。 */
private const val FLOW_BASELINE = 0.15f

/** 速度 → 沿运动方向拉伸的系数与上限（同样压到很小，避免底栏随拖动被"拉走"）。 */
private const val STRETCH_GAIN = 0.008f
private const val STRETCH_LIMIT = 0.010f

/** 按住时底栏本体向外鼓起的高度（dp）：只做微动。 */
private const val BAR_BULGE_DP = 6f

/** 拖动幅度叠加到底栏宽度上的比例（0.004 ≈ 0.4%，几乎察觉不到）。 */
private const val BAR_FLOW_SCALE = 0.004f

/** 玻璃模糊半径（常量）：拖动过程中不改变，避免反复重建 RenderEffect 链。 */
private const val BLUR_RADIUS_DP = 4f

/** 底栏高度 + 底部外边距：页面滚动内容需在末尾预留的额外空间（可滚到底栏下方）。 */
val GlassBarSpace = 88.dp

/**
 * 真实模糊依赖 API 33+ 的 RuntimeShader（AGSL），且设备需真的能编译着色器。
 * 任一条件不满足时回退到伪玻璃底栏（渐变 + 描边），避免出现"透明但无模糊"的半成品观感。
 */
fun isGlassBlurSupported(): Boolean =
    Build.VERSION.SDK_INT >= 33 && isRuntimeShaderSupported() && isAgsuUsable()

/**
 * 探测 AGSL 是否可用：个别设备/驱动在 API 33+ 上仍会编译失败。
 * 失败时 miuix 的模糊/折射/高光会全部静默失效，故这里提前判定并降级。
 * 只在 API 33+ 调用（由 isGlassBlurSupported 短路保证），因此抑制 NewApi 检查。
 */
@SuppressLint("NewApi")
private fun probeAgsu(): Boolean = try {
    android.graphics.RuntimeShader(AGSL_PROBE)
    true
} catch (_: Throwable) {
    false
}

private val agslUsable: Boolean by lazy { probeAgsu() }

private fun isAgsuUsable(): Boolean = agslUsable

private const val AGSL_PROBE = "half4 main(float2 c) { return half4(c.x, c.y, 0.0, 1.0); }"

data class GlassBarItem(val icon: ImageVector, val label: String)

/** 图标层副本的缩放（按压时被胶囊透镜放大）；用 lambda 供绘制阶段读取，避免重组。 */
private val LocalGlassBarTabScale = staticCompositionLocalOf { { 1f } }

/** iOS 风格镜面高光：双向加色辉光描边，光源方向随重力旋转。 */
private val GlassSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f
        ),
        dualPeak = true
    )
)

// 与 miuix-blur HighlightStyle 的 LIGHT_REF 保持一致
private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f
private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f // |g_xy| > 0.1，约 6° 倾斜
private const val GRAVITY_ANGLE_STEP_RAD = (3.0 * PI / 180.0).toFloat()

/**
 * 液态玻璃外壳：玻璃模式下内容铺满整屏并挂载 backdrop 图层，底栏浮于其上；
 * 非玻璃模式退化为普通 Scaffold（不触碰任何 miuix-blur API，行为与迁移前一致）。
 */
@Composable
fun GlassShell(
    glass: Boolean,
    darkTheme: Boolean,
    items: List<GlassBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    snackbarHost: @Composable () -> Unit,
    fallbackBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    if (!glass) {
        Scaffold(snackbarHost = snackbarHost, bottomBar = fallbackBar) { content(it) }
        return
    }
    val surfaceColor = MaterialTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        // 先铺主题底色再叠内容：底栏模糊的是「底色 + 内容」的合成结果
        drawRect(surfaceColor)
        drawContent()
    }
    Box(Modifier.fillMaxSize()) {
        // 内容不做底部避让：卡片可延伸到浮起的底栏之下，页面自身在滚动内容末尾
        // 预留 GlassBarSpace，保证最后一项仍能完整滚出（见各 Tab 的 bottomOverlap）
        Scaffold(
            modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
            // Snackbar 需要让出浮起的底栏高度，否则会被玻璃盖住
            snackbarHost = {
                Box(Modifier.padding(bottom = GlassBarSpace)) { snackbarHost() }
            },
            bottomBar = {}
        ) { p ->
            content(p)
        }
        GlassBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            backdrop = backdrop,
            darkTheme = darkTheme,
            items = items,
            selectedIndex = selectedIndex,
            onSelect = onSelect
        )
    }
}

@Composable
private fun GlassBar(
    modifier: Modifier,
    backdrop: LayerBackdrop,
    darkTheme: Boolean,
    items: List<GlassBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val glassShape = GlassBarShape
    val pillShape = remember { CircleShape }
    val accentColor = scheme.primary
    val tabContentColor = scheme.onSurfaceVariant
    // 玻璃涂层：半透明底色叠在模糊之上，形成牛奶玻璃质感
    val containerColor = scheme.surface.copy(alpha = 0.35f)
    // 外阴影强度随主题调整：深色背景上黑色阴影本就更不明显，故用更高不透明度
    val barShadowColor = if (darkTheme) Color.Black.copy(alpha = 0.5f)
    else Color.Black.copy(alpha = 0.32f)

    val tabsBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    val tabsCount = items.size

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    // 拖到两端之外时的橡皮筋位移：越界越多位移越小
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    val onSelectedUpdated by rememberUpdatedState(onSelect)

    fun indexAt(positionX: Float): Int {
        if (tabWidthPx == 0f) return currentIndex
        val horizontalPaddingPx = with(density) { 4.dp.toPx() }
        val logicalX = if (isLtr) positionX else totalWidthPx - positionX
        return ((logicalX - horizontalPaddingPx) / tabWidthPx)
            .toInt()
            .coerceIn(0, tabsCount - 1)
    }

    val dampedDragAnimation = remember(animationScope, tabsCount, density, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            canDrag = { offset -> offset.x in 0f..totalWidthPx },
            onDragStarted = { position -> updateValue(indexAt(position.x).toFloat()) },
            onDragStopped = {
                // 惯性吸附：位置 + 速度预测（INERTIA_PREDICT_SECONDS 的甩动行程），
                // 快速甩动即使未越过中点也能吸附到相邻 Tab，慢速拖动则按位置就近吸附
                val span = (tabsCount - 1).toFloat().coerceAtLeast(1e-6f)
                val predicted = targetValue + velocity * span * INERTIA_PREDICT_SECONDS
                val targetIndex = predicted.roundToInt().coerceIn(0, tabsCount - 1)
                if (currentIndex != targetIndex) {
                    currentIndex = targetIndex
                    onSelectedUpdated(targetIndex)
                }
                updateValue(targetIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDragCancelled = {
                updateValue(currentIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0f && dragAmount.x != 0f) {
                    val sign = if (isLtr) 1f else -1f
                    val requested = targetValue + dragAmount.x / tabWidthPx * sign
                    val clamped = requested.coerceIn(0f, (tabsCount - 1).toFloat())
                    updateValue(clamped)
                    // 橡皮筋位移只取「拖到两端之外」的越界量：Tab 区间内拖动时底栏本体不跟着位移，
                    // 否则整条底栏会随手指横移（观感上就是"底栏背景跟着放大镜一起动"）
                    val overshootPx = (requested - clamped) * tabWidthPx * sign
                    animationScope.launch {
                        offsetAnimation.snapTo(overshootPx)
                    }
                }
            }
        )
    }

    LaunchedEffect(selectedIndex) {
        if (currentIndex != selectedIndex) {
            currentIndex = selectedIndex
            dampedDragAnimation.animateToValue(selectedIndex.toFloat())
        }
    }

    fun activateTab(index: Int) {
        if (index !in 0 until tabsCount) return
        if (currentIndex != index) {
            currentIndex = index
            onSelectedUpdated(index)
        }
        dampedDragAnimation.animateToValue(index.toFloat())
    }

    val interactiveHighlight = remember(animationScope, tabWidthPx, dampedDragAnimation) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { size, _ ->
                Offset(
                    if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                    else size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset,
                    size.height / 2f
                )
            }
        )
    }

    val baseHighlight = rememberGravityRotatedHighlight(GlassSpecular, extraDegrees = -45f)
    val pillHighlight = rememberGravityRotatedHighlight(GlassSpecular, extraDegrees = 90f)
    // 选中胶囊需同时折射「应用内容」与「图标层」，故合并两个 backdrop
    val combinedBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)

    Box(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 18.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier
                .onGloballyPositioned { coords ->
                    totalWidthPx = coords.size.width.toFloat()
                    val contentWidthPx = totalWidthPx - with(density) { 8.dp.toPx() }
                    tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                }
                .selectableGroup()
                .graphicsLayer { translationX = panelOffset }
                .shadow(
                    elevation = 12.dp,
                    shape = glassShape,
                    clip = false,
                    ambientColor = barShadowColor,
                    spotColor = barShadowColor
                )
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { glassShape },
                    effects = {
                        // 以下动画值均在 draw 阶段读取：只触发重绘、不触发重组
                        val press = dampedDragAnimation.pressProgress
                        val flow = dampedDragAnimation.dragFlow
                        // 玻璃"厚度"只驱动 lens 折射（仅更新 shader uniform）：
                        // 模糊半径保持常量，拖动过程中不再重建 RenderEffect 链，更顺滑也更省电
                        val thickness = 1f +
                            THICKNESS_GAIN_PRESS * press +
                            THICKNESS_GAIN_FLOW * flow * maxOf(press, FLOW_BASELINE)
                        padding = maxOf(padding, 40.dp.toPx())
                        vibrancy()
                        blur(BLUR_RADIUS_DP.dp.toPx(), BLUR_RADIUS_DP.dp.toPx())
                        lens(
                            refractionHeight = 24.dp.toPx() * thickness,
                            refractionAmount = 24.dp.toPx() * thickness
                        )
                    },
                    highlight = { baseHighlight.value.copy(alpha = 0.75f) },
                    layerBlock = {
                        val press = dampedDragAnimation.pressProgress
                        val width = size.width.coerceAtLeast(1f)
                        val bulge = lerp(1f, 1f + BAR_BULGE_DP.dp.toPx() / width, press)
                        // 速度方向拉伸 + 拖动幅度延展：只保留极小幅度，底栏本体几乎不动
                        val flow = dampedDragAnimation.dragFlow
                        val stretch = (dampedDragAnimation.velocity * STRETCH_GAIN)
                            .fastCoerceIn(-STRETCH_LIMIT, STRETCH_LIMIT) * press
                        scaleX = bulge * (1f + stretch + BAR_FLOW_SCALE * flow * press)
                        scaleY = bulge
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(64.dp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { i, item ->
                CompositionLocalProvider(
                    LocalContentColor provides if (i == currentIndex) accentColor else tabContentColor
                ) {
                    GlassBarTabItem(
                        modifier = Modifier.weight(1f),
                        item = item,
                        selected = i == currentIndex,
                        onClick = { activateTab(i) }
                    )
                }
            }
        }

        // 图标层副本（alpha 0，仅被 tabsBackdrop 记录）：按住时被胶囊透镜放大并染成主色
        CompositionLocalProvider(
            LocalGlassBarTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            },
            LocalContentColor provides accentColor
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics { }
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { glassShape },
                        effects = {
                            padding = maxOf(padding, 40.dp.toPx())
                            vibrancy()
                            blur(4.dp.toPx(), 4.dp.toPx())
                            lens(
                                refractionHeight = 24.dp.toPx(),
                                refractionAmount = 24.dp.toPx()
                            )
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { i, item ->
                    GlassBarTabItem(
                        modifier = Modifier.weight(1f),
                        item = item,
                        selected = i == currentIndex,
                        onClick = { activateTab(i) }
                    )
                }
            }
        }

        if (tabWidthPx > 0f) {
            val tabWidthDp = with(density) { tabWidthPx.toDp() }
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .graphicsLayer {
                        val progressOffset = dampedDragAnimation.value * tabWidthPx
                        translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                    }
                    .drawBackdrop(
                        backdrop = combinedBackdrop,
                        shape = { pillShape },
                        effects = {
                            // 玻璃流动强度：按下不动时由按压力度驱动；只要手指在拖动，
                            // 该幅度就足以撑起完整放大镜（避免部分 ROM 取消"静止按住"手势时看不到效果）
                            val progress = dampedDragAnimation.pressProgress
                            val flow = dampedDragAnimation.dragFlow
                            val strength = maxOf(progress, flow)
                            lens(
                                refractionHeight = 10.dp.toPx() * strength,
                                refractionAmount = 14.dp.toPx() * strength * (1f + 0.5f * flow),
                                depthEffect = true,
                                chromaticAberration = 0.5f
                            )
                        },
                        highlight = { pillHighlight.value.copy(alpha = dampedDragAnimation.pressProgress) },
                        layerBlock = {
                            // 胶囊放大同样取「按压力度」与「拖动幅度」的较大者：
                            // 拖动过程中胶囊就是一枚放大镜（放大并折射其下方内容）
                            val bulge = lerp(
                                1f,
                                78f / 56f,
                                maxOf(dampedDragAnimation.pressProgress, dampedDragAnimation.dragFlow)
                            )
                            val velocity = dampedDragAnimation.velocity / 10f
                            scaleX = bulge / (1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f))
                            scaleY = bulge * (1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f))
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            drawRect(
                                color = if (darkTheme) Color.White.copy(alpha = 0.1f)
                                else Color.Black.copy(alpha = 0.1f),
                                alpha = 1f - progress
                            )
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        }
                    )
                    .innerShadow(shape = pillShape) {
                        InnerShadow(
                            radius = 8.dp * dampedDragAnimation.pressProgress,
                            color = Color.Black.copy(alpha = 0.15f),
                            alpha = dampedDragAnimation.pressProgress
                        )
                    }
                    .height(56.dp)
                    .width(tabWidthDp)
            )
        }

        // 交互覆盖层：放在最后（命中优先级最高），自己处理按下/拖动/点击。
        // 原实现依赖父级监听 Initial/Main pass，但在部分设备（如 API 36 的 ColorOS）上
        // 父级收不到指针事件，导致按压缩放/拖动切换完全失效，故改为顶层覆盖层直接处理。
        val touchSlop = with(density) { 8.dp.toPx() }
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(tabsCount, isLtr) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startPos = down.position
                        var travelled = 0f
                        with(dampedDragAnimation) { onDragStarted(startPos) }
                        dampedDragAnimation.press()
                        var lifted = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.fastFirstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                lifted = true
                                break
                            }
                            val delta = change.positionChangeIgnoreConsumed()
                            if (delta != Offset.Zero) {
                                travelled += delta.getDistance()
                                with(dampedDragAnimation) { onDrag(IntSize.Zero, delta) }
                                change.consume()
                            }
                        }
                        if (lifted && travelled < touchSlop) {
                            // 位移极小 → 视为点击
                            activateTab(indexAt(startPos.x))
                        }
                        with(dampedDragAnimation) { onDragStopped() }
                        dampedDragAnimation.release()
                    }
                }
        )
    }
}

@Composable
private fun GlassBarTabItem(
    modifier: Modifier,
    item: GlassBarItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scale = LocalGlassBarTabScale.current
    Column(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            }
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
    ) {
        // 取 LocalContentColor：主行按选中态着色，图标层副本统一为主色（供胶囊透镜采样）
        Icon(
            item.icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = LocalContentColor.current
        )
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall,
            color = LocalContentColor.current
        )
    }
}

// ==================== 组合 backdrop / 重力高光 ====================

/** 把两个 backdrop 顺序绘制为一个：用于让胶囊同时折射应用内容与图标层。 */
private class CombinedBackdrop(
    val first: Backdrop,
    val second: Backdrop
) : Backdrop {

    override val isCoordinatesDependent: Boolean =
        first.isCoordinatesDependent || second.isCoordinatesDependent

    override val offsetResidualX: Float get() = first.offsetResidualX
    override val offsetResidualY: Float get() = first.offsetResidualY

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int
    ) {
        with(first) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        with(second) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
    }
}

@Composable
private fun rememberCombinedBackdrop(first: Backdrop, second: Backdrop): Backdrop =
    remember(first, second) { CombinedBackdrop(first, second) }

/** 量化后的重力方向角：静止（|g_xy| 很小）时固定在屏幕上方。 */
@Composable
private fun rememberQuantizedGravityAngle(): State<Float> {
    val tiltState = rememberDeviceTilt()
    return remember(tiltState) {
        derivedStateOf {
            val tilt = tiltState.value
            val magnitudeSquared = tilt.gravityX * tilt.gravityX + tilt.gravityY * tilt.gravityY
            if (magnitudeSquared > GRAVITY_DIR_THRESHOLD_SQ) {
                (atan2(tilt.gravityY, tilt.gravityX) / GRAVITY_ANGLE_STEP_RAD).roundToInt() * GRAVITY_ANGLE_STEP_RAD
            } else {
                (-PI / 2).toFloat()
            }
        }
    }
}

/** 按重力方向旋转主光源，再附加一个固定偏移，得到随姿态变化的镜面高光。 */
@Composable
private fun rememberGravityRotatedHighlight(
    base: Highlight,
    extraDegrees: Float = 0f
): State<Highlight> {
    val baseStyle = base.style as BloomStroke
    val angle = rememberQuantizedGravityAngle()
    return remember(angle, base, extraDegrees) {
        derivedStateOf {
            val basePrimary = baseStyle.primaryLight
            val rad = angle.value + (extraDegrees * PI / 180.0).toFloat()
            base.copy(
                style = baseStyle.copy(
                    primaryLight = basePrimary.copy(
                        position = LightPosition(
                            x = LIGHT_REF_X + cos(rad),
                            y = LIGHT_REF_Y + sin(rad),
                            z = basePrimary.position.z
                        )
                    )
                )
            )
        }
    }
}

// ==================== 玻璃效果（着色器移植自 KernelSU，GPL-3.0-or-later） ====================
// 折射/色散着色器来自 tiann/KernelSU 的 liquid/Lens.kt，其文件头注明：
// Adapted from Kyant0/AndroidLiquidGlass — https://github.com/Kyant0/AndroidLiquidGlass (Apache 2.0)，
// 以及 Mirrored from compose-miuix-ui example。
// 本文件随本项目按 GPL-3.0-or-later 分发，详见根目录 LICENSE 与 NOTICE.md。

/** 饱和度增强：让透过玻璃的内容更「通透」，接近 iOS 液态玻璃观感。 */
private fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f
    )
}

/**
 * 边缘折射：把玻璃边缘附近的采样坐标向外偏移，形成「透镜」般的折射与厚度感。
 * 依赖 RuntimeShader（API 33+），由调用方保证执行路径。
 */
private fun BackdropEffectScope.lens(
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Float = 0f
) {
    if (!isRuntimeShaderSupported()) return
    if (refractionHeight <= 0f || refractionAmount <= 0f) return

    if (padding < refractionAmount) {
        padding = refractionAmount
    }

    val radii = roundedRectCornerRadii() ?: return

    val dispersionEnabled = chromaticAberration > 0f
    val shaderString =
        if (dispersionEnabled) ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER
        else ROUNDED_RECT_REFRACTION_SHADER
    val key = if (dispersionEnabled) "LiquidGlassLensDispersion" else "LiquidGlassLens"

    val sf = downscaleFactor.coerceAtLeast(1).toFloat()
    val scaledSizeW = size.width / sf
    val scaledSizeH = size.height / sf
    val scaledPadding = padding / sf
    val scaledRefractionHeight = refractionHeight / sf
    val scaledRefractionAmount = refractionAmount / sf
    val scaledRadii = FloatArray(radii.size) { radii[it] / sf }

    runtimeShaderEffect(
        key = key,
        shaderString = shaderString,
        uniformShaderName = "content"
    ) {
        setFloatUniform("size", scaledSizeW, scaledSizeH)
        setFloatUniform("offset", -scaledPadding, -scaledPadding)
        setFloatUniform("cornerRadii", scaledRadii)
        setFloatUniform("refractionHeight", scaledRefractionHeight)
        setFloatUniform("refractionAmount", -scaledRefractionAmount)
        setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
        if (dispersionEnabled) {
            setFloatUniform("chromaticAberration", chromaticAberration)
        }
    }
}

private fun BackdropEffectScope.roundedRectCornerRadii(): FloatArray? {
    val cornerShape = shape as? CornerBasedShape ?: return null
    val sizePx = size
    val maxRadius = sizePx.minDimension / 2f
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val topLeft = if (isLtr) cornerShape.topStart.toPx(sizePx, this) else cornerShape.topEnd.toPx(sizePx, this)
    val topRight = if (isLtr) cornerShape.topEnd.toPx(sizePx, this) else cornerShape.topStart.toPx(sizePx, this)
    val bottomRight = if (isLtr) cornerShape.bottomEnd.toPx(sizePx, this) else cornerShape.bottomStart.toPx(sizePx, this)
    val bottomLeft = if (isLtr) cornerShape.bottomStart.toPx(sizePx, this) else cornerShape.bottomEnd.toPx(sizePx, this)
    return floatArrayOf(
        topLeft.fastCoerceAtMost(maxRadius),
        topRight.fastCoerceAtMost(maxRadius),
        bottomRight.fastCoerceAtMost(maxRadius),
        bottomLeft.fastCoerceAtMost(maxRadius)
    )
}

private const val ROUNDED_RECT_SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}
"""

private const val ROUNDED_RECT_REFRACTION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}
"""

private const val ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;

    half4 color = half4(0.0);

    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;

    return color;
}
"""