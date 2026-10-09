# 2.1.1-1 修复说明

基于上游 `ba3e27b25f3857d624ac12b1a7979956424f1d96`（2.1.1）。

- `GUIManager.isPhantomControlInventory` 使用 `getHolder(false)` 判断菜单身份，避免普通方块容器点击时为身份判断创建物品快照。保持原 GUIHolder 类型判断、按钮和点击取消规则。
- `PhantomSpawnListener.onPhantomPreSpawn` 同时检查当前 `phantomcontrol.use` 权限和玩家保存的开关；权限在线撤销后，旧的关闭设置不再阻止幻翼生成。不修改权限默认值或权限组，也不删除已存在的幻翼。
- 没有增加调度、缓存或持久化格式。已有事件上下文直接完成检查。

## 证据与验证

生产 1.6.5 JAR 的 `GUIManager.onInventoryClick:151` 和作者 2.1.1 JAR 都调用无参 `Inventory.getHolder()`。提供的 Shiroha `26.2-DEV-a8f1d54` 字节码确认路径为 `getHolder -> getOwner(true) -> getState(true) -> createSnapshot -> saveWithFullMetadata/loadStatic`。`getHolder(false)` 对方块容器传递 false，对插件自定义库存仍返回原 Holder。

[Paper Inventory API](https://jd.papermc.io/paper/1.20.1/org/bukkit/inventory/Inventory.html#getHolder(boolean)) 也明确说明布尔参数控制方块实体快照。

定向测试在修改前失败 6 项：5 项检出无参 Holder 调用，1 项检出无权限时仍阻止生成。修改后通过。测试不复现原现场物品，也不证明告警中的整个 5.34 秒全部由该调用消耗。

构建：JDK 25，Maven 3.9.11，Java release 17，沿用 Paper API 1.20.1-R0.1-SNAPSHOT。

```powershell
mvn -B -ntp clean verify
# 使用自己的旧配置包进行额外数据验证；仅在临时目录读写副本：
mvn -B -ntp clean verify '-Dphantomcontrol.upgradeZip=绝对路径/旧配置.zip'
```

测试包含容器排除、GUI 身份、下方背包点击取消、会员按钮、权限/开关/世界的 8 种生成组合、描述文件解析、旧配置迁移、玩家数据读写。默认测试资源来自 1.6.5 JAR，玩家 UUID 为虚构值。真实服主配置与玩家数据不包含在本仓库或源码包中。

自动化测试和核心字节码核对不等于启动核心或玩家实机验证。本次未启动生产服或隔离服，未做全插件 Folia 审计。

## 从 1.6.5 升级

上游 2.1.1 已将定时清零“距上次睡眠时间”改为幻翼预生成事件拦截，不再定时清零该统计。`/pc` 和 `phantomcontrol.use` 保留；已有 `playerdata.yml` 的 UUID -> 布尔值格式保留。旧 `settings.task` 参数保留在旧配置中但新版不再使用；旧配置和消息文件自动补项到版本 3。界面信息按钮从原第 15 格移到第 5 格（代码槽号 14 -> 4）。上游还加入公共 API/事件、异步数据处理和配置重载改进。

会员仍需自行选择关闭幻翼；仅授予权限不等于自动关闭。所有版本的 `phantomcontrol.use` 均为 `default: true`：只给会员显式授权，不能证明普通玩家没有权限。会员专用须在权限插件里明确拒绝普通组、允许会员组，并核验最终权限。本修复不代改权限配置。

生产提供的配置使用 flatfile。新版单条变更先进入内存数据，定期保存/正常停服写盘；当前自动保存周期 300 秒。异常进程退出不能保证保存最近一次写盘后的修改。保留完整旧数据目录以供回滚。

升级时完整停服，备份旧 JAR 和整个 `plugins/PhantomControl`，移出旧 JAR，只放入一个修复版，保留原数据目录后启动。不要同时放多个版本，不用热卸载替代完整重启。回滚时停服并还原配套 JAR/目录备份。
