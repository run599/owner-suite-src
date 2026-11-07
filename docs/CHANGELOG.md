# 变更记录

## v1.1.0

guard：
- 新增开机自检（BootReceiver），占位丢失时下次打开提示
- 新增「移除 PIN」强制设置
- 新增「禁止安装新应用」开关（默认关，带风险提示）
- 新增「解除占位」二次确认
- 新增 adb 命令通道（onNewIntent 修复）

activator：
- mDNS 自动发现连接/配对端口（只需输入配对码）
- 改用软件 RSA + 自签证书（修复 AndroidKeyStore NoPadding 限制）
- 引入 conscrypt-android（修复 Android 16 缺少 exportKeyingMaterial）
- 每条命令 15 秒超时 + 自动重连重试
- 只禁用"真正持有账号"的包（9 个，不再碰 GMS/微软/淘宝）
- 系统包黑名单 + 通用词过滤（防止误判 com.oplus.account）
- 禁用清单持久化 + 启动自检补恢复
- 跳过本来就是禁用状态的包（不改变用户原有设置）

## v1.0.0
- 初版：占位 + 通过无线调试设置 Profile Owner
