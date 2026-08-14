# 项目交接总览

## 当前基线

- 产品：离线资产盘点 Android App
- 当前版本：`1.1.0-beta34`，versionCode `134`
- 数据库：SQLite，schema version `12`
- 最低系统：Android 10（API 29）
- 当前开发分支：`agent/app-icon-beta26`
- 当前稳定提交：以 Git 仓库最新提交为准
- 主要语言：简体中文

## 产品目标

让盘点人员以尽量少的操作完成公司资产盘点：准备公司、区域、台账和任务，扫描区域及资产二维码，处理异常与重复，多设备离线汇总，导出 Excel，并能安全删除、备份和恢复数据。

## 当前主流程

1. 顶部选择或新建公司。
2. 准备区域、台账和盘点任务。
3. 扫描区域二维码进入区域，再连续扫描资产二维码。
4. 查看进度、缺失、新增、异常和跨区域重复，并完成必要处理。
5. 通过二维码面对面传输，或通过 `.invtask` / `.invresult` 文件协作。
6. 完成任务、导出 Excel、归档；历史任务仍可查看、导出或重新开启。

## 已实现能力

- 多公司共存；公司间允许相同资产编号。
- 多个进行中任务共存，但当前公司页面只展示本公司任务。
- Excel 台账导入、异常行人工处理、台账查看。
- 区域和资产二维码生成、批量打印。
- 连续扫码、区域自动归类、扫码时间、盘点人员、语音播报、手电筒。
- 台账外资产、异常照片、撤销/删除审计、跨区域重复处理。
- 离线任务包、结果包、二维码面对面传输和文件分享。
- Excel 结果导出、任务完成/归档/重新开启。
- `.invbackup` 完整备份、预览、分享、删除、全量恢复、公司或任务选择性恢复。
- NIIMBOT B3S_P 与 T40×15 标签打印。

## 主要代码位置

- `InventoryUi.kt`：页面、导航与主要交互。
- `InventoryDatabase.kt`：数据库结构、迁移和业务查询。
- `QrPayload.kt`：二维码识别规则。
- `MainActivity.kt`：中文语音引擎初始化与播报。
- `OfflineTaskPackage.kt` / `OfflineResultPackage.kt`：离线数据包。
- `LocalPackageTransfer.kt`：面对面二维码传输。
- `InventoryBackup.kt` / `SelectiveRestore.kt`：备份恢复。
- `NiimbotPrinter.kt`：打印机发现、配对、连接与打印。

## 当前已知问题

- 部分一加手机在面对面传输二维码页面连续多轮断开、重连 Wi-Fi 后，第二次断网状态可能不会立即刷新。当前不阻断主要流程，已暂缓。
- 自动测试覆盖较少，目前仅有二维码格式单元测试；数据库迁移、备份恢复、任务包导入仍需补自动化测试。
- `InventoryUi.kt` 仍保留一个未调用的旧备份页面实现 `BackupScreenLegacy`，后续确认新备份页面稳定后可清理。

## 构建与真机安装

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r "app\build\outputs\apk\debug\app-debug.apk"
```

## 继续开发

开始任何修改前阅读 `AGENTS.md`，再按需求读取 `docs/` 中对应规则。开发完成后同步更新本文件、相关规则、验收记录与版本号。
