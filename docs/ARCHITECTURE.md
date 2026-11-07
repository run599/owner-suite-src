# 架构说明

## 一、Android 的两个 owner 槽位

| 槽位 | 获得方式 | 能力 |
|---|---|---|
| Device Owner (DO) | adb `dpm set-device-owner` / 开机向导 | 全套 DPM API |
| Profile Owner (PO) | adb `dpm set-profile-owner` / 工作资料配置 | 大部分 DPM API（本用户内） |

两者在**同一用户**上的关系（AOSP `DevicePolicyManagerService` 源码 + 本机实测）：

```
enforceCanSetProfileOwnerLocked:
    if (hasDeviceOwner() && deviceOwnerUserId != userId) throw
    → DO 在同一用户时，PO 仍可设置

checkDeviceOwnerProvisioningPreConditionLocked:
    if (owners.hasProfileOwner(userId)) return 2（拒绝）
    → PO 已存在时，DO 一律拒绝
```

**结论：PO 占位的防御力 > DO 占位。**

## 二、占位为什么有效

1. DO/PO 是**单例**：同一用户只能有一个；
2. 非 `testOnly` 的 admin，`dpm remove-active-admin` 会拒绝；
3. DO/PO 的包无法被 `pm uninstall`（`DELETE_FAILED_DEVICE_POLICY_MANAGER`）；
4. 因此后来者无论如何都无法取得同一槽位。

## 三、账号门槛与绕过

AOSP 要求设备无账号才能设置 owner：

```
Not allowed to set the device owner because there are already some accounts on the device.
```

activator 的绕过路径（不删账号）：

```
1) dumpsys account
     → 解析 "Account {name=..., type=T}" 得到真实存在的账号类型
     → 解析 "AuthenticatorDescription {type=T}, ComponentInfo{PKG/...}" 得到类型→包名映射
2) 只禁用这些 PKG（跳过已禁用的、跳过系统包黑名单）
3) 等待 6 秒（AccountManagerService 的兼容性缓存重算）
4) dpm set-profile-owner
5) 恢复全部 PKG（独立新连接 + 每包重试）
```

### 为什么禁用提供者能过检查

`userHasIncompatibleAccounts` 会对每个账号调用 `hasFeatures(account, FEATURE_ALLOW)`。
提供者被禁用后该调用失败，账号不再被判定为"不兼容"，于是检查通过。

**账号数据本身没有变化**，只是短暂不可见。

## 四、activator 的 ADB 实现

```
无线调试开启
  → mDNS 发现 _adb-tls-connect._tcp（连接端口）与 _adb-tls-pairing._tcp（配对端口）
  → PairingConnectionCtx 配对（把我们的公钥登记到 adbd）
  → AdbConnection.open("shell:<cmd>") 执行命令
```

### 踩过的坑（都在代码里修了）

| 坑 | 现象 | 修法 |
|---|---|---|
| AndroidKeyStore 禁止 NoPadding | `InvalidAlgorithmParameterException: Randomized encryption required` | 改用软件 RSA + BouncyCastle 自签证书 |
| 配对端口不监听回环 | `ECONNREFUSED 127.0.0.1:<pairPort>` | 用 mDNS 发现到的 host |
| 系统 Conscrypt 缺 `exportKeyingMaterial` | `NoSuchMethodException` | 引入 `conscrypt-android`，走"自定义 Conscrypt"分支 |
| 流读到 CLSE 抛异常 | `IOException: Stream closed` | 把 IOException 当 EOF |
| 命令无超时 | 界面永久卡住 | 每条 15 秒超时 + 重连重试 |
| 进程被杀导致不恢复 | 包留在禁用态 | 禁用清单持久化 + 启动自检补恢复 |
| 误判已禁用包 | 会把用户自己禁用的应用"恢复" | 先查 `pm list packages -d`，已禁用则跳过 |

## 五、安全边界（代码级）

`guard`：

- Manifest 无 INTERNET / 存储 / 定位 / 联系人；
- `device_admin.xml` 的 `uses-policies` 为空 —— 不申请任何设备管理策略；
- 不调用 `addUserRestriction`（除用户主动开启的"禁止安装"）；
- 不联网、不采集、不上传。

`activator`：

- 只禁用"真正持有账号"的包，且跳过系统包黑名单（`com.oplus.*`、`com.heytap.*`、GMS 等）；
- **永不执行 uninstall**；
- 每条命令有超时，恢复用独立连接 + 重试。
