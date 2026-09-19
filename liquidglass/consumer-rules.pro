# 本库不需要额外的混淆规则：
# - 全部实现都是 Compose 直接调用的 Kotlin 代码，无反射、无资源反射查找
# - miuix-blur 的 AGSL 着色器以字符串常量内联在代码中（runtimeShaderEffect），不经反射加载
# 保持该文件存在是为了 consumerProguardFiles 有一个明确的落点，便于以后按需补充。