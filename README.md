# Owner Suite — 设备所有者占位套件

两个应用，一个目的：**让任何应用都无法抢占你手机的「设备所有者 / 配置文件所有者」席位。**

| 模块 | 应用名 | 包名 | 体积 | 角色 |
|---|---|---|---|---|
| `guard/` | 所有者占位器 | `com.guard.ownerguard` | ~3 MB | **锁**：长期常驻，占住 owner 槽位 |
| `activator/` | 占位激活器 | `com.guard.activator` | ~21 MB | **钥匙**：通过本机 ADB 帮助完成占位，用完可卸 |

---

## 为什么需要它

Android 的 Device Owner / Profile Owner 是最高管理权限。一旦被恶意或流氓 App 取得，它可以：

- 禁止卸载应用、禁止恢复出厂、禁用安全模式
- 挂起任意应用、隐藏图标、锁定屏幕
- 修改系统设置、限制网络

而获取它的门槛并不高：**只要能开无线调试，任意 App 就能通过 adb 把自己设成 owner**（市面上已有商业 App 这么做，并在应用商店上架）。

`guard` 的作用是**提前占住这个席位**，让后来者永远设不上。

---

## 原理（已实测验证）

### 1. 两个槽位互斥，且不对称

```
PO 已占用 → dpm set-device-owner  报错 "the user already has a profile owner"
DO 已占用 → dpm set-profile-owner 仍然被允许（同一用户不互斥）
```

**所以占 PO 是更强的一把锁**：它能挡住 DO，而 DO 挡不住 PO。

### 2. 非 testOnly 的 owner，adb 也摘不掉

```
dpm remove-active-admin  → 要求 admin 声明 android:testOnly="true"
pm uninstall             → DELETE_FAILED_DEVICE_POLICY_MANAGER
pm disable-user          → 能禁用进程，但 owner 身份与槽位保留
```

`guard` 刻意**不声明 testOnly**，所以只能由它自己（输入 PIN）解除。

### 3. activator 如何绕过「设备不能有账号」的限制

AOSP 规定：**设备上存在账号时不允许设置 owner**（报 `already some accounts on the device`）。

activator 的做法（与商业管控软件同源，但不删除任何账号）：

```
dumpsys account  → 找出"真正持有账号"的包
pm disable-user  → 临时禁用这些包的账号提供者（账号暂时不可见）
等 6 秒          → 让系统的账号兼容性缓存重算
dpm set-profile-owner <guard 的 admin>
pm enable        → 立即恢复全部包
```

**全程不删账号、不注销登录**。实测：11 个账号在操作前后数量不变。

---

## 目录结构

```
owner-suite/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── guard/                          # 占位器
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/guard/ownerguard/
│       │   ├── MainActivity.kt              # 状态面板 / PIN / 禁止安装 / DO 说明
│       │   ├── DeviceOwnerAdminReceiver.kt  # 占位用的 admin 组件（不做任何事）
│       │   └── BootReceiver.kt              # 开机自检：占位是否还在
│       └── res/...
└── activator/                      # 激活器
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/guard/activator/
            ├── MainActivity.kt              # UI + 激活流程编排
            ├── AdbSession.kt                # ADB 会话（超时/重连/容错读流）
            ├── AdbKit.kt                    # 单条命令执行 + 配对
            └── Identity.kt                  # 软件 RSA 密钥 + 自签证书（PKCS12 持久化）
```

---

## 构建

```powershell
$env:ANDROID_HOME = "E:\Android\Sdk"
$env:GRADLE_USER_HOME = "E:\ssss\tools\android\gradle-home"
& "E:\ssss\tools\android\gradle-8.14.3\bin\gradle.bat" -p E:\ssss\clone\owner-suite assembleDebug
```

产物：

```
guard\build\outputs\apk\debug\guard-debug.apk
activator\build\outputs\apk\debug\activator-debug.apk
```

依赖：AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35 / JDK 17。
activator 额外依赖 libadb-android 3.1.1（JitPack）、Conscrypt 2.5.2、BouncyCastle 1.78.1。

---

## 使用流程

### 第一步：装 guard，占住 PO

```bash
adb install guard-debug.apk
adb shell dpm set-profile-owner com.guard.ownerguard/.DeviceOwnerAdminReceiver
adb shell dpm list-owners      # 期望看到 ProfileOwner
```

> 有账号时这条命令会失败。解决办法二选一：
> - 手动退出全部账号后再执行（干净）；
> - 用 activator 自动完成（见下）。

装好后**立刻**打开应用设置移除 PIN —— 这是唯一的出口。

### 第二步（可选）：用 activator 自动占位

1. 打开无线调试
2. 点「使用配对码配对设备」，拿到 6 位码
3. 打开 activator，端口自动发现，只填配对码
4. 点「一键配对并占位」

完成后 activator 可以卸载。

---

## 权限清单

| 应用 | 权限 | 用途 |
|---|---|---|
| guard | `RECEIVE_BOOT_COMPLETED` | 开机自检占位是否还在 |
| guard | 无 INTERNET / 无存储 / 无定位 / 无联系人 | 架构上无法外传数据 |
| activator | `INTERNET` | 连接本机回环 `127.0.0.1` 的 adbd |

---

## 已知限制

- **更新 guard 会清掉占位**：重装 APK 后必须重新执行 `set-profile-owner`；
- **恢复出厂会清掉占位**：需要重新设置；
- **activation 期间禁用账号提供者约 30 秒**：期间相关 App 可能短暂异常；
- **guard 的 PIN 丢失且无法解除**：只能恢复出厂（所以务必设置并记住）。

详见 `docs/RISKS.md`。

**已知设计取舍（PIN 可被覆盖）**：`docs/SECURITY-TRADEOFF.md`

---

## 实测验证

真实对抗结果（含日志原文与截图）：**`docs/VALIDATION.md`**

> 一台装有商业管控软件的手机，在部署本占位器后，
> 该软件激活失败并报："已有其他应用被设为配置管理器应用，请先检查并移除。"


