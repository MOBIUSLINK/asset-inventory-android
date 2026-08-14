# 资产盘点 Android App

一款面向离线资产盘点场景的 Android 应用，支持多公司与多任务、二维码连续扫描、语音播报、异常处理、离线任务包/结果包协作、Excel 导入导出、完整备份，以及 NIIMBOT B3S_P 标签打印。

## 环境

- Android Studio
- JDK 17
- Android SDK 29 或更高版本

项目当前版本：`1.1.0-beta34`（versionCode 134，数据库版本 12）

继续开发前请先阅读 [PROJECT.md](PROJECT.md) 和 [AGENTS.md](AGENTS.md)。产品规则、数据关系、状态、导航、决策与验收记录保存在 `docs/`。

## 构建

在项目根目录执行：

```powershell
.\gradlew.bat assembleDebug
```

## 文件格式

- `.invtask`：盘点任务包
- `.invresult`：盘点结果包
- `.invbackup`：完整备份文件

## 第三方 SDK

`app/libs` 中包含用于 NIIMBOT B3S_P 打印机适配的厂商 SDK 二进制文件。这些文件不是本项目原创内容，其版权、许可及使用条件归对应厂商所有。使用或再分发前，请自行确认已获得必要授权。

## 当前已知问题

- 部分一加手机在附近传输二维码页面连续执行多轮 Wi-Fi 断开与重连时，第二次断网后界面状态可能不会立即刷新。文件分享和主要盘点流程不受影响。
