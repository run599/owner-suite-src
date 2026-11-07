# 安全取舍说明：PIN 可以被覆盖

> 这是一份**有意保留**的设计取舍，不是疏漏。
> 使用前请务必读完，并明确自己属于哪一种使用场景。

---

## 一、当前行为（漏洞描述）

`guard` 的 `setPin()` **不校验旧 PIN**：

```kotlin
private fun setPin() {
    askPin("设置移除 PIN") { pin ->
        if (pin.length < 4) { toast("PIN 至少 4 位"); return@askPin }
        prefs.edit().putString(KEY_PIN_HASH, sha256(pin)).apply()   // 直接覆盖，不验证旧值
        toast("PIN 已设置，请记牢")
    }
}
```

### 后果

任何**能物理接触到手机**的人，可以：

```
① 打开「所有者占位器」
② 点「设置/修改移除 PIN」→ 随便设一个新 PIN（无需旧 PIN）
③ 输入新 PIN → 解除占位
④ 卸载应用
```

**也就是说：PIN 只防"误操作"，不防"有意夺取"。**

而 adb 无法做到这一点（见 `VALIDATION.md` 第十节：六种删除手法全部失败），
所以这个漏洞的入口是**物理接触**。

---

## 二、为什么故意不修

因为修了之后会引入一个更现实的风险：**永久锁死**。

`guard` 一旦成为 Profile Owner：

- 无法卸载（`DELETE_FAILED_DEVICE_POLICY_MANAGER`）
- 无法停用（`Cannot disable a protected package`）
- 无法摘除 admin（非 testOnly）
- PIN 是唯一出口

如果加上"修改 PIN 必须验证旧 PIN"，那么**忘记 PIN = 只能恢复出厂**。

对一个"日常使用、偶尔需要解除"的用户来说，这个代价比漏洞本身更大。

---

## 三、两种使用姿势（请对号入座）

### 姿势 A：我还需要能解除占位

**保持现状，不要改代码。**

- PIN 会被记住，随时能解除
- 接受"物理接触者可绕过"这个前提
- 适合：自己用、需要偶尔调整、设备不离身

### 姿势 B：我永远不打算用 PO/DO，只求它们不被滥用

**建议自行修补代码，并设置一个自己都记不住的 PIN。**

修补后：

```
PIN 一旦设置 → 无法修改 → 无法解除占位
→ PO 槽位被永久占用
→ 任何软件（包括监管类、恶意类）永远拿不到 DO/PO
→ 想解除只能恢复出厂
```

**这等价于把 PO 槽位焊死**。对于"我这辈子都不需要设备所有者权限"的用户，
这是最彻底的做法——**让这个权限在系统里永远不存在**。

⚠️ 前提：你已经确认自己**永远不需要**以下功能：

- 企业/学校 MDM 注册
- OPPO 儿童空间等依赖 DO 的功能
- 任何需要设备所有者的自动化工具

---

## 四、如何修补（姿势 B 用）

把 `guard/src/main/java/com/guard/ownerguard/MainActivity.kt` 里的 `setPin()` 改成：

```kotlin
private fun setPin() {
    val saved = prefs.getString(KEY_PIN_HASH, null)

    // 已设置过 PIN：必须先验证旧 PIN 才能改
    if (saved != null) {
        askPin("输入当前 PIN") { old ->
            if (sha256(old) != saved) { toast("PIN 错误"); return@askPin }
            askNewPinAndSave()
        }
        return
    }
    askNewPinAndSave()
}

private fun askNewPinAndSave() {
    askPin("设置移除 PIN") { pin ->
        if (pin.length < 4) { toast("PIN 至少 4 位"); return@askPin }
        prefs.edit().putString(KEY_PIN_HASH, sha256(pin)).apply()
        toast("PIN 已设置。忘记它将只能恢复出厂。")
        refresh()
    }
}
```

同时建议把「解除占位」按钮的二次确认文案改成更醒目的警告。

---

## 五、两种姿势的风险对照

| | 姿势 A（不改） | 姿势 B（修补） |
|---|---|---|
| 忘记 PIN | 可重新设一个再解除 | **只能恢复出厂** |
| 物理接触者 | 可改 PIN 绕过 | 无法绕过 |
| adb 攻击 | 无法删除 | 无法删除 |
| 适合 | 日常使用、需要可控 | 永久封死权限 |

---

## 六、一句话总结

> **这个 PIN 不是用来防"别人"的，是用来防"你自己手滑"的。**
> **真正的防护是"adb 删不掉它"，那部分没有任何漏洞。**
> **要不要把最后这道门也焊死，取决于你打算不打算再进这道门。**
