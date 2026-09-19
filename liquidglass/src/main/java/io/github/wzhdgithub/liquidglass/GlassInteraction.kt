package io.github.wzhdgithub.liquidglass

// 本文件移植自 tiann/KernelSU 的 manager 端（component/miuix/animation 与
// modifier/DragGestureInspector），KernelSU 以 GPL-3.0-or-later 分发；
// 其交互设计源自 compose-miuix-ui（Miuix）官方示例 LiquidGlassNavigationBar
// 与 Kyant0/AndroidLiquidGlass（二者均为 Apache-2.0）。
// 因此本文件随本仓库一并按 GPL-3.0-or-later 分发，详见根目录 LICENSE 与 NOTICE.md。
// 仅供 API 33+ 的液态玻璃底栏使用（RuntimeShader 依赖）。

import android.annotation.SuppressLint
import android.graphics.RuntimeShader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * 底栏拖拽手势：与 Compose 自带的 detectDragGestures 不同，这里在 Initial pass 上
 * 监听且不消费事件，因此底栏整体拖拽与子项点击可以同时工作。
 */
private suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Main,
        )
        onDragStart(down)
        onDrag(down, Offset.Zero)
        val upEvent = drag(
            pointerId = down.id,
            onDrag = { onDrag(it, it.positionChangeIgnoreConsumed()) }
        )
        if (upEvent != null) onDragEnd(upEvent) else onDragCancel()
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) {
        return null
    }
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.changedToUpIgnoreConsumed()) {
            return change
        }
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            val hasDragged = dragEvent.previousPosition != dragEvent.position
            if (hasDragged) {
                return dragEvent
            }
        }
    }
}

/**
 * 底栏拖拽动画：按住缩放、拖动切换、松手弹簧归位，并暴露速度/按压缩放进度
 * 供玻璃层（layerBlock 形变、透镜放大、高光）使用。
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val canDrag: (Offset) -> Boolean = { true },
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDragCancelled: DampedDragAnimation.() -> Unit = onDragStopped,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit
) {

    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(0.85f, 900f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    private var pressJob: Job? = null
    private var releaseJob: Job? = null

    private val velocityTracker = VelocityTracker()
    private val startMark = TimeSource.Monotonic.markNow()

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    /**
     * 拖动形变量（0=恰好停在某个 Tab 中心，1=正处于两个 Tab 之间）：
     * 直接由动画中的 value 推导，故松手吸附时随位置自然回落，无需额外动画与状态。
     */
    val dragFlow: Float
        get() {
            val v = valueAnimation.value
            return (abs(v - v.roundToInt()) * 2f).coerceIn(0f, 1f)
        }

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragCancelled()
                release()
            }
        ) { change, dragAmount ->
            val position = change.position
            val previousPosition = change.previousPosition

            val isInside = canDrag(position)
            val wasInside = canDrag(previousPosition)

            if (isInside && wasInside) {
                onDrag(size, dragAmount)
            }
        }
    }

    fun press() {
        releaseJob?.cancel()
        pressJob?.cancel()
        velocityTracker.resetTracking()
        pressJob = animationScope.launch {
            // 形变带过渡：放大镜在约 200ms 内弹入（不做瞬发）；松手仍走弹簧回弹
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        pressJob?.cancel()
        releaseJob?.cancel()
        releaseJob = animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }.first { abs(it - valueAnimation.targetValue) < threshold }
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            startMark.elapsedNow().inWholeMilliseconds,
            Offset(value, 0f)
        )
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
        val targetVelocity = velocityTracker.calculateVelocity().x / span
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            velocityAnimation.snapTo(targetVelocity)
        }
    }
}

/**
 * 按压光斑：以手指位置为中心的加色高光，模拟玻璃被按住时的散射光。
 */
@SuppressLint("NewApi")
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {

    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    private val offset: Offset get() = positionAnimation.value - startPosition

    @SuppressLint("LanguageInspection")
    private val shader = RuntimeShader(
        """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""
    )

    val modifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgressAnimation.value
            if (progress > 0f) {
                drawRect(
                    Color.White.copy(0.06f * progress),
                    blendMode = BlendMode.Plus
                )
                shader.apply {
                    val position = position(size, positionAnimation.value)
                    setFloatUniform("size", size.width, size.height)
                    setColorUniform("color", Color.White.copy(0.12f * progress).toArgb())
                    setFloatUniform("radius", size.minDimension * 1.2f)
                    setFloatUniform(
                        "position",
                        position.x.fastCoerceIn(0f, size.width),
                        position.y.fastCoerceIn(0f, size.height)
                    )
                }
                drawRect(
                    ShaderBrush(shader),
                    blendMode = BlendMode.Plus
                )
            }

            drawContent()
        }

    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            inspectDragGestures(
                onDragStart = { down ->
                    startPosition = down.position
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                        launch { positionAnimation.snapTo(startPosition) }
                    }
                },
                onDragEnd = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
                onDragCancel = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                }
            ) { change, _ ->
                animationScope.launch { positionAnimation.snapTo(change.position) }
            }
        }
}