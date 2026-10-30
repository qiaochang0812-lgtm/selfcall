# NovaCustomerDialer（Nova 客户拨号助手）

面向 Android / 华为 nova 10 的客户回访队列原型。Kotlin + Jetpack Compose。

## 当前功能
- 从文件选择器导入 UTF-8 CSV（无需通讯录权限）
- 识别姓名、手机号、备注列（建议列顺序：姓名,手机号,备注）
- 按客户队列查看进度、点选切换客户
- 系统默认、SIM1、SIM2 三种固定卡拨号方式；点击“一键拨打当前客户”直接发起通话
- 按拨号轮换模式：可为每张 SIM 设置连续拨出 1、2、3、5 或 10 次后自动换卡
- 轮换只在通话成功发起后计数，权限不足或 SIM 账户异常不会消耗轮换次数
- 设置页提供 SIM/Telecom 账户状态、权限说明、授权和系统设置入口
- 手动记录已接通、未接听、稍后回访、拒绝
- 不申请网络、通讯录、短信或定位权限

## Android 权限
- `CALL_PHONE`：用户点击拨号按钮后直接拨出电话，指定 SIM 时必需。
- `READ_PHONE_STATE`：读取活动 SIM 和通话账户，用于把 SIM1/SIM2 映射到 Android Telecom 账户，指定 SIM 或轮换时必需。
- `READ_CALL_LOG`：可选；用于匹配客户最近通话并生成拨出次数、接通次数和时长统计。

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
gradle -PstandaloneDebug=true assembleDebug
```
输出路径：`app/build/outputs/apk/debug/app-debug.apk`。`standaloneDebug` 会沿用正式 applicationId，便于在本机直接替换测试；不传该参数时调试包使用 `.debug` 后缀，可与现有版本共存。

## 说明
- 当前是可编译原型，不是已验证可在所有 EMUI/HarmonyOS 版本运行的正式产品。
- 双卡映射兼容账户 ID 将订阅 ID 编码在内、使用内部账户 ID，以及系统仅提供顺序的双卡实现；若仍无法映射，界面会明确提示具体失败的 SIM。
- 客户、标签、联系状态和拨号偏好保存在本机 SharedPreferences；卸载应用或清除应用数据后仍会丢失。下一阶段建议迁移到 Room 并增加自动备份。
- 本项目仅在用户主动点击拨号按钮后发起通话。请仅联系有合理联系依据、允许联系的客户，并遵守当地电话营销和隐私法规。
