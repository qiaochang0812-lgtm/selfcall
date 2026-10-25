# NovaCustomerDialer（Nova 客户拨号助手）

面向 Android / 华为 nova 10 的客户回访队列原型。Kotlin + Jetpack Compose。

## 当前功能
- 从文件选择器导入 UTF-8 CSV（无需通讯录权限）
- 识别姓名、手机号、备注列（建议列顺序：姓名,手机号,备注）
- 按客户队列查看进度、点选切换客户
- 通过 Android `ACTION_DIAL` 打开系统电话界面，由用户确认拨号
- 手动记录已接通、未接听、稍后回访、拒绝
- 不请求网络、电话、通讯录权限；不自动直接拨出电话

## CSV 示例
```csv
姓名,手机号,备注
张三,13800138000,续约回访
李四,+8613900000000,新客户
```
保存为 UTF-8 CSV。导入时每一行应只有一位客户。

## GitHub Actions 编译
每次推送到 `main`/`master`，或手动运行 Actions 中的 **Build Android APK**，会执行 `assembleDebug` 并上传名为 `NovaCustomerDialer-debug` 的构建产物。

下载方式：GitHub 仓库 → Actions → 选择成功的工作流运行 → Artifacts → `NovaCustomerDialer-debug`。下载 ZIP 并解压，里面的 `app-debug.apk` 可用于测试安装。

## 本机编译
需要 JDK 17、Android SDK（API 35）和 Gradle 8.9：
```bash
gradle assembleDebug
```
输出路径：`app/build/outputs/apk/debug/app-debug.apk`

## 说明
- 当前是可编译原型，不是已验证可在所有 EMUI/HarmonyOS 版本运行的正式产品。
- 目前客户和联系状态只保存在应用运行内存中；关闭应用后会丢失。下一阶段建议加入 Room/本地持久化、重复号码检测、CSV 导出和回访日期。
- 本项目仅支持用户主动发起并确认的拨号流程。请仅联系有合理联系依据、允许联系的客户，并遵守当地电话营销和隐私法规。
