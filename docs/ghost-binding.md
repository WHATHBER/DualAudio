# 排查记录：开关显示「开」却怎么都关不掉 —— 幽灵绑定

> 本文记录 v1.0 → v1.1 之间一次完整的问题定位过程。
> 结论是：**残留的音频设备绑定挂在了非媒体策略上，且绑定状态在不同进程间读出的结果不一致**。
>
> 记录下来不只是为了存档 —— 更重要的是，最后一次定位成功靠的不是"更聪明地读代码"，
> 而是**先让程序把现场条件打印出来**。这个顺序值得记住。

---

## 一、现象

在**未连接任何蓝牙耳机 / USB 声卡**的情况下：

- 打开 App，「双路输出」开关显示为**已开启**
- 点击关闭，日志只报一句 `[失败] 关闭失败`
- 反复点，始终关不掉

App 内日志：

```
01:36:47  [ok] 逻辑层 v21 已就绪
01:36:49  > 关闭双路输出
01:36:50  [失败] 关闭失败
```

---

## 二、排查过程（以及被推翻的三个假设）

整个过程经历了几轮"假设 → 验证 → 推翻"，记录下来比只写结论更有价值。

### 假设 1：关闭时抛了异常，异常被吞掉了

**依据**：`core/MultiAudio.java` 里 `clear()` 的主路径异常被静默吞掉：

```java
} catch (Throwable ignore) {   // ← 异常在这里消失
}
```

接着走反射兜底，兜底再失败就会冒到 `main()`：

```java
} catch (Throwable t) {
    System.out.println("[ERROR] " + t);   // 只有 ERROR，没有 res=ok
    System.exit(1);
}
```

上层判定逻辑要求 `autoclear` 返回 `ok` **且** `status` 的 `dual` 为 `0`，两者缺一即判失败。
异常路径下 `res=ok` 根本不会打印，于是"关闭失败"。

**结论**：这是**真实缺陷**（根因丢失），但**不是本次现象的原因**。
加了诊断输出后真机验证，发现实际返回的是 `res=ok` —— 根本没抛异常。

> ✅ 保留修复：`clear()` 不再静默吞异常；`autoclear` 恒返回 `res=ok`，
> 异常原因写进 `msg`，避免整个进程以"没有 `res=`"的方式退出导致根因丢失。

### 假设 2：清除接口调用成功了，但没真正生效

**依据**：真机日志显示接口报告成功，状态却没变：

```
[diag] autoclear res=ok · 原始输出: 已清除绑定 | res=ok
[diag] status dual=1
```

于是改进方向是"换一条通道"：绑定是用 `IAudioService.setPreferredDevicesForStrategy`
建立的（该通道已证明可用），那就用**同一条通道传空列表**来解除，而不是用
`removePreferredDevicesForStrategy`。

```java
// 首选：与「绑定」完全相同的通道，只是传入空列表
findMethod(ias.getClass(), "setPreferredDevicesForStrategy", 2)
        .invoke(ias, sid, new ArrayList<Object>());
```

**结论**：方向对了一半。改动本身是合理的（幂等、复用已验证通道），但
**单独做这一步并不能解决问题** —— 因为清的策略根本就是错的（见下）。

### 假设 3：绑定落在了非媒体策略上，而两个命令的判定口径不一致

这一轮的关键是**先补诊断、再下结论**。加上启动环境快照和设备/策略全貌输出后，
真机日志里出现了两组互相矛盾的数据：

```
[snapshot] dexver=24 | dual=1 | btdev=0 | usbdev=0
           bind=strategyId=5 有效设备数=2 含蓝牙=true 含USB=true
           列表=[type:bt_a2dp addr:XX:XX:XX:XX:E5:10, type:usb_headset addr:card=1;device=0]
           devs=1:24031PN0DC / 2:24031PN0DC / 18:24031PN0DC
```

```
[list] [strategy] id=6 name=STRATEGY_DTMF  media?=false
[list] preferred = [type:bt_a2dp …, type:usb_headset …]        ← 绑定在这里
[list] >> [strategy] id=5 name=STRATEGY_MEDIA media?=true
[list] preferred = []                                          ← 媒体策略是空的
```

三件事同时成立：

1. **绑定在 `STRATEGY_DTMF`（id=6）上，不在媒体策略（id=5）上**
2. `devs=` 里只有听筒/扬声器/电话 —— **蓝牙和 USB 都不在线**，这是**幽灵绑定**
3. `status` 和 `list` 对**同一个 strategyId=5** 给出了相反结论

---

## 三、根因

三个问题叠加，才造成"显示开着、又关不掉"这个现象。

### ① 判定口径不一致（最隐蔽）

`status()` 用 `supportsMedia()` 逐个试探：

```java
for (Object s : productStrategies()) {
    if (!supportsMedia(s)) continue;   // ← 实测会命中不止一条策略
    ...
    break;                              // ← 先命中谁就用谁的绑定
}
```

而 `list()` 用的是**精确比对**（`sid == mediaId`）。
遍历顺序上 **id=6 排在 id=5 前面**，于是 `status` 先读到 DTMF 上那份残留绑定 → `dual=1`；
`list` 则正确地标记 `media?=false`。

**同一个概念在两处用了不同判据 —— 这是"日志自相矛盾"的根源，也是最耗时的部分。**
排查 bug 时真正难的常常不是 bug 本身，而是**不一致的信息在误导方向**。

### ② 清除只针对媒体策略

`clear()` 只清 id=5。置空返回 0（看起来成功），而真正绑着的 id=6 分毫未动。

### ③ 幽灵绑定 + 依赖读取结果做判断

绑定记录还在，设备早已拔掉。更麻烦的是：**同一策略的绑定状态在不同进程里读出来不一样**
—— `status` 进程读到 2 个设备，`list` 进程读到空列表。

而 `clear()` 当时的设计是「先读一遍看有没有绑定，再决定清不清」。
既然读取结果本身不可信，这个判断必然漏清 —— 读到空 → 跳过 → 绑定纹丝不动。

---

## 四、修复

### 1. `dual` 判定加入「设备必须在线」，并与 `list` 口径统一

```java
// status()：只认 id 恰好等于媒体策略 id 的那一条
int mediaId = findMediaStrategyId();
if (num(call(s, "getId")) != mediaId) continue;

// 幽灵绑定不算双路：绑定里躺着的都是已拔掉的设备，实际并没有双路输出
int dualFinal = (dualRaw && (!devEnumOk || (hasBt && hasUsb))) ? 1 : 0;
```

> 注意 `!devEnumOk` 这个分支：设备枚举**失败**时不能据此判否，
> 否则会把"枚举失败"误判成"没开启"，走向另一个错误。

### 2. 清除改为「无条件置空所有策略」

```java
// 刻意不做「先读一遍看有没有绑定再决定」——实测同一个策略在不同进程里
// 读出的绑定状态会不一致，靠读取结果做判断必然会漏清。
// 置空是幂等操作，全清一遍最可靠。
for (Object s : productStrategies()) {
    int id = num(call(s, "getId"));
    findMethod(ias.getClass(), "setPreferredDevicesForStrategy", 2)
            .invoke(ias, Integer.valueOf(id), new ArrayList<Object>());
}
```

**这一条是整轮排查的转折点：当"读到的状态"不可信时，改用幂等的无条件操作。**

### 3. 保留诊断能力

```java
// 启动时自动采集，不再依赖事后追问
[env]      品牌机型 / Android 版本与 API / 构建号 / ABI / su 路径 / 身份 / App 版本
[crash]    上一次崩溃记录
[snapshot] 完整 status（dual、设备在线情况、绑定判定依据、设备列表）
[list]     设备与产品策略全貌
```

`list` 中还额外列出所有仍留有绑定的策略，便于直接看出残留落在哪一条上。

---

## 五、可迁移的经验

1. **同一个判断，必须单点定义、处处复用。**
   `supportsMedia()` 与 `id == mediaId` 并存，就是矛盾日志的来源。

2. **别只在出错时记录，要在出事前把现场条件留档。**
   机型 / ROM / root 方式 / 设备连接情况这些前提条件，往往比错误信息本身更有价值。
   这次的定位成功，直接得益于提前加了 `[env]` 和 `[list]`。

3. **状态不可信时，改用幂等操作。**
   "先读再判断"的模式隐含假设"读是准的"。一旦这个假设不成立，就必须换成
   "无条件执行 + 幂等"，而不是继续在读取精度上打补丁。

4. **不要静默吞异常。**
   `catch (Throwable ignore) {}` 让第一轮的排查方向完全跑偏 —— 花了好几轮才发现
   问题根本不在异常路径上。

5. **控件是否可操作，必须与其操作前提绑定。**
   「双路输出」开关曾是界面上**唯一**不受设备状态约束的控件（USB 音量卡片和
   「重新应用」都会按设备在线情况折叠）。设备不齐却允许拨动开关，状态机就会进入
   脏状态。现已改为：蓝牙与声卡同时在线才允许**开启**；已开启时始终允许**关闭**
   （否则绑定清不掉）。

6. **别用体积判断 APK 版本。**
   中间几个版本产物都是 37512 bytes —— zipalign 对齐把差异吸收掉了。
   必须验 SHA-256 或搜索关键字符串。

---

## 六、涉及改动一览

| 文件 | 改动 |
|---|---|
| `core/MultiAudio.java` | `dual` 判定口径统一 + 幽灵绑定过滤；`clear()` 改无条件置空；不再吞异常；新增诊断输出 |
| `app/src/com/dualaudio/MainActivity.java` | 开关受设备状态约束 + 入口防御；关闭判定改以 `dual` 为准；启动环境快照与崩溃记录 |
| `DEX_VER` | 21 → **26**（两侧同步） |

---

## 附：复现与自检

```bash
# 查看全部输出设备
MultiAudio list

# 查看 status（含 dual 判定依据与残留绑定提示）
MultiAudio status

# 无条件清除所有策略上的绑定
MultiAudio autoclear
```

若 `status` 出现 `dual=1` 而 `btdev=0 / usbdev=0`，即为本文所述的幽灵绑定场景。
