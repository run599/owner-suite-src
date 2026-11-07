# 风险与恢复

## 使用者必须知道的三件事

1. **更新 guard 会清掉占位**
   安装新版 APK 会强制停止进程并移除 owner 注册。更新后必须重新执行：
   ```bash
   adb shell dpm set-profile-owner com.guard.ownerguard/.DeviceOwnerAdminReceiver
   ```

2. **PIN 丢了只能恢复出厂**
   `guard` 不是 testOnly，adb 无法移除它的 owner 身份。PIN 是唯一出口。

3. **激活过程中不要清后台**
   进程被杀会导致"禁用已生效、恢复未执行"。虽然已加入持久化 + 启动自检恢复，
   但最稳妥是在它走完"⑦ 恢复"再切走。

## activator 中断后的恢复

禁用清单写在 `SharedPreferences("activator")` 的 `disabled_pending` 里。
下次打开 activator 会自动补恢复（需要无线调试开启）。

手工兜底（电脑）：

```bash
adb shell pm enable <包名>
```

查看还有哪些禁用：

```bash
adb shell pm list packages -d
```

## 已知的"改不动"状态

极少数情况下，包会处于 `enabled=2` 且 `pm enable` 报
`SecurityException: Shell cannot change component state for null`。

这通常是"用户空间卸载 + 应用禁用"双重操作留下的损坏状态。
**唯一可靠修复：从应用商店覆盖安装该应用。**

## 不要做的事

- 不要对系统包执行 `pm enable/disable`；
- 不要在 activator 运行时安装/更新 APK（会杀进程）；
- 不要把 `guard` 改成 testOnly（那样 adb 就能摘掉它）；
- 不要在未备份的情况下对主力机做实验。
