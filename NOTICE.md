# NOTICE / 第三方声明

本项目（LiquidGlassBar：Jetpack Compose 液态玻璃底栏）以 **GPL-3.0-or-later** 发布，
完整许可全文见根目录 [LICENSE](LICENSE)。

Copyright (C) 2026 wzhdgithub

下列第三方代码**实际用于**本项目的底栏实现，特此保留其版权与许可声明。
仅"浏览研究过、最终没有采用"的项目不列入本文件。

---

## 1. tiann / KernelSU — 直接来源

- 仓库：https://github.com/tiann/KernelSU
- 作者：weishu（tiann）与 KernelSU 贡献者
- 许可：**GPL-3.0-or-later**
  （其根目录 LICENSE 为 GPLv3 全文；README 声明 `kernel/` 目录为 GPL-2.0-only，其余部分为 GPL-3.0-or-later）
- 本项目实际使用的内容：
  - `liquidglass/src/main/java/io/github/wzhdgithub/liquidglass/InnerShadow.kt`
    —— 移植自其 `manager/app/src/main/java/me/weishu/kernelsu/ui/component/liquid/InnerShadow.kt`
  - `liquidglass/src/main/java/io/github/wzhdgithub/liquidglass/GlassInteraction.kt`
    —— 移植自其 `manager/.../ui/component/miuix/animation/` 与 `manager/.../ui/component/miuix/modifier/DragGestureInspector.kt`
    （`DampedDragAnimation`、`InteractiveHighlight`、`inspectDragGestures`）
  - `liquidglass/src/main/java/io/github/wzhdgithub/liquidglass/GlassBottomBar.kt` 中的折射 / 色散 AGSL 着色器
    —— 移植自其 `manager/.../ui/component/liquid/Lens.kt`
  - 交互方案（按下放大镜、拖动切换 Tab、惯性吸附、越界橡皮筋、重力方向高光）参照其 FloatingBottomBar 实现路径
- 使用方式：**移植 / 修改后使用**——去掉了长按阈值与震动反馈，重写了手势层（顶层覆盖层）与全部手感参数，
  详见各文件头部注释与 `GlassBottomBar.kt` 顶部参数区

## 2. Kyant0 / AndroidLiquidGlass — 上游作者

- 仓库：https://github.com/Kyant0/AndroidLiquidGlass
- 作者：Kyant
- 许可：**Apache License 2.0**，`Copyright 2025 Kyant`
- 本项目实际使用的内容（经 KernelSU 镜像而来，故在此保留其版权声明）：
  - 圆角矩形折射与色散 AGSL 着色器
    （其 `backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt` 中的
    `RoundedRectRefractionShaderString` / `RoundedRectRefractionWithDispersionShaderString`）
  - `DampedDragAnimation`、`InteractiveHighlight` 与 `LiquidBottomTabs` 交互设计

## 3. compose-miuix-ui / miuix（Miuix）

- 仓库：https://github.com/compose-miuix-ui/miuix
- 许可：**Apache License 2.0**（其 Maven POM 声明 "The Apache Software License, Version 2.0"）
- 本项目实际使用的内容：
  - **作为依赖使用**：`top.yukonga.miuix.kmp:miuix-blur-android:0.9.3`
    —— 提供 `drawBackdrop` / `layerBackdrop` / `blur` / `colorControls` / `runtimeShaderEffect` /
    `Highlight`（BloomStroke）/ `rememberDeviceTilt` 等底层能力，是本库能实现真实模糊与折射的基础
  - 其官方示例 `LiquidGlassNavigationBar` 为底栏交互的来源之一
    （KernelSU 的 `liquid/*` 文件头注明 "Mirrored from compose-miuix-ui example"）

---

## 关于本仓库自有代码

除上述明确标注的文件外，本仓库的其余部分（演示 App、公开 API 的整理与文档）为原创，
同样以 GPL-3.0-or-later 发布。

## 依赖清单（均为 Apache-2.0）

| 组件 | 许可 |
|---|---|
| AndroidX / Jetpack Compose（BOM 2026.05.01） | Apache-2.0 |
| AndroidX Core KTX / Activity Compose | Apache-2.0 |
| Miuix miuix-blur-android 0.9.3 | Apache-2.0 |