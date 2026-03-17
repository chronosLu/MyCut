# MyCut

MyCut 是一个 Android 悬浮截图与贴图工具，支持快速截屏、框选裁剪、悬浮贴图、日志查看与导出。

## 功能概览

- 悬浮球双击触发截屏
- MediaProjection 截屏授权与会话复用
- 选区裁剪后生成可拖拽/缩放的悬浮贴图
- 长按贴图复制到剪贴板
- 调试日志查看、导出、清空
- 设置面板支持：
  - 悬浮截图边框开关
  - 悬浮球大小调节
  - 背景图选择/清除

## 开发环境

- Android Gradle Plugin `8.5.2`
- Kotlin `1.9.24`
- JDK `17`
- minSdk `29` / targetSdk `35`

## 本地构建

```bash
./gradlew assembleDebug
```

构建产物：

- `app/build/outputs/apk/debug/app-debug.apk`

## 仓库结构

- `app/src/main/java/com/mycut/app`：核心业务代码
- `app/src/main/res`：布局、图标、样式与文案
- `app/src/main/AndroidManifest.xml`：组件与权限声明

## 说明

- 本仓库默认忽略本地构建输出、IDE 配置及作业目录 `work/`。
