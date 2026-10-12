/**
 * MultiAudio —— 用 root 调用 Android 12+ "组合音频设备路由" API，
 * 让同一路媒体音频同时输出到多个设备（例：蓝牙 A2DP + USB 声卡）。
 *
 * 修复记录 v2：
 *   app_process 的 main 线程默认没有 Looper，ActivityThread.systemMain() 会抛
 *   "Can't create handler inside thread ... that has not called Looper.prepare()"。
 *   v2 先反射调用 Looper.prepare()，并增加 IAudioService(binder) 降级通道，
 *   即使拿不到 ActivityThread 的系统 Context 也能工作。
 *
 * 用法（root shell）:
 *   app_process -Djava.class.path=/data/local/tmp/MultiAudio.dex /system/bin \
 *       --nice-name=multiaudio MultiAudio list
 *   ... MultiAudio set   <strategyId> <type:address> [<type:address> ...]
 *   ... MultiAudio clear <strategyId>
 */
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

public class MultiAudio {

    /** 逻辑层版本号 —— App 会核对这个值，确保部署到手机上的不是旧 dex */
    static final int DEX_VER = 22;

    static Object am;                                  // AudioManager (可能为 null)
    static Object iaudioService;                       // IAudioService (降级通道)
    static Class<?> adaClass;                          // android.media.AudioDeviceAttributes
    static Class<?> sysCls;                            // android.media.AudioSystem

    public static void main(String[] args) {
        try {
            exemptHiddenApi();
            adaClass = Class.forName("android.media.AudioDeviceAttributes");
            sysCls = Class.forName("android.media.AudioSystem");
            resolveAudioManager();

            if (args.length == 0 || "list".equals(args[0])) {
                list();
            } else if ("clear".equals(args[0])) {
                clear(Integer.parseInt(args[1]));
            } else if ("auto".equals(args[0])) {
                List<Object> devs = new ArrayList<Object>();
                if (args.length == 1) {
                    // 先诊断缺什么，再挑选设备
                    boolean hasBt = false, hasUsb = false;
                    if (am != null) {
                        Object[] all = (Object[]) findMethod(am.getClass(), "getDevices", 1)
                                .invoke(am, 2);
                        for (Object d : all) {
                            int t = num(call(d, "getType"));
                            if (isBtOut(t)) hasBt = true;
                            else if (isUsbOut(t)) hasUsb = true;
                        }
                    }
                    if (!hasBt && !hasUsb) {
                        fail("no-device", "蓝牙与 USB 声卡均未就绪");
                        return;
                    }
                    if (!hasBt) {
                        fail("no-bt", "蓝牙未连接");
                        return;
                    }
                    if (!hasUsb) {
                        fail("no-usb", "USB 声卡未插入");
                        return;
                    }
                    devs.addAll(autoPickDevices());
                } else {
                    for (int i = 1; i < args.length; i++) {
                        String[] kv = args[i].split(":", 2);
                        devs.add(newAda(Integer.parseInt(kv[0]), kv.length > 1 ? kv[1] : ""));
                    }
                }
                if (devs.size() < 2) {
                    fail("few-device", "未检测到足够的输出设备");
                    return;
                }
                int sid = findMediaStrategyId();
                if (sid < 0) {
                    fail("no-strategy", "未识别到媒体策略");
                    return;
                }
                Object r = trySet(sid, devs);
                if (!Boolean.TRUE.equals(r)) {
                    fail("bind-failed", "双设备绑定被系统拒绝");
                    return;
                }
                System.out.println("res=ok");
                System.out.println("msg=已开启双路输出，请暂停音乐后重新播放");
            } else if ("autoclear".equals(args[0])) {
                int sid = findMediaStrategyId();
                if (sid < 0) {
                    fail("no-strategy", "未识别到媒体策略");
                    return;
                }
                try {
                    clear(sid);
                } catch (Throwable t) {
                    // 清除接口失败不一定是真失败：也可能本来就没有绑定。
                    // 「是否已经退出双路」由 status 的 dual 判定，这里只负责把原因说出来，
                    // 不再让异常冒到 main 导致整个进程以无 res= 的方式退出。
                    System.out.println("msg=清除绑定接口异常：" + t);
                }
                System.out.println("res=ok");
            } else if ("status".equals(args[0])) {
                // status fix：顺带确保蓝牙绝对音量处于开启状态。
                // 该开关一旦被关闭，手机侧音量就传不到耳机（音量键也会失效）。
                if (args.length > 1 && "fix".equals(args[1])) ensureBtAbsoluteVolume();
                status();
            } else if ("sysapi".equals(args[0])) {
                sysApi();
            } else if ("alsa".equals(args[0])) {
                alsaCmd(args);
            } else if ("vol".equals(args[0])) {
                if (args.length == 1) {
                    volumeList();
                } else if (args.length >= 3) {
                    volumeSet(args[1], Integer.parseInt(args[2]));
                } else {
                    System.out.println("usage: vol | vol <usb|bt|speaker> <0-100>");
                }
            } else if ("set".equals(args[0])) {
                int sid = Integer.parseInt(args[1]);
                List<Object> devs = new ArrayList<Object>();
                for (int i = 2; i < args.length; i++) {
                    String[] kv = args[i].split(":", 2);
                    devs.add(newAda(Integer.parseInt(kv[0]), kv.length > 1 ? kv[1] : ""));
                }
                set(sid, devs);
            } else {
                System.out.println("usage: MultiAudio list | auto <type:address> [...] "
                        + "| set <strategyId> <type:address> [...] | clear <strategyId>");
            }
        } catch (Throwable t) {
            System.out.println("[ERROR] " + t);
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
        System.out.flush();
        System.exit(0);
    }

    /* ---------------- 获取 AudioManager ---------------- */
    static void resolveAudioManager() {
        try {
            // 关键修复：app_process 主线程没有 Looper
            try {
                Class.forName("android.os.Looper").getMethod("prepare").invoke(null);
            } catch (Throwable t) {
                System.out.println("[warn] Looper.prepare failed: " + t);
            }
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method sm = at.getDeclaredMethod("systemMain");
            sm.setAccessible(true);
            Object atObj = sm.invoke(null);
            Method gsc = at.getDeclaredMethod("getSystemContext");
            gsc.setAccessible(true);
            Object ctx = gsc.invoke(atObj);
            am = ctx.getClass().getMethod("getSystemService", String.class).invoke(ctx, "audio");
        } catch (Throwable t) {
            am = null;
        }
    }

    /* ---------------- 降级通道：IAudioService binder ---------------- */
    static Object iaudio() throws Exception {
        if (iaudioService != null) return iaudioService;
        Class<?> smc = Class.forName("android.os.ServiceManager");
        Object binder = smc.getMethod("getService", String.class).invoke(null, "audio");
        if (binder == null) throw new IllegalStateException("cannot get 'audio' service from ServiceManager");
        Class<?> stub = Class.forName("android.media.IAudioService$Stub");
        Class<?> ibinder = Class.forName("android.os.IBinder");
        iaudioService = stub.getMethod("asInterface", ibinder).invoke(null, binder);
        return iaudioService;
    }

    /* ---------------- list ---------------- */
    static void list() throws Exception {
        System.out.println("=== OUTPUT DEVICES ===");
        boolean printed = false;

        if (am != null) {
            try {
                Object[] outs = (Object[]) findMethod(am.getClass(), "getDevices", 1).invoke(am, 2);
                for (Object d : outs) {
                    int type = num(call(d, "getType"));
                    System.out.printf("[dev] id=%s type=%d(%s) name=%s address=%s%n",
                            str(call(d, "getId")), type, typeName(type),
                            str(call(d, "getProductName")), str(call(d, "getAddress")));
                }
                printed = true;
            } catch (Throwable t) {
                System.out.println("[warn] AudioManager.getDevices failed: " + t);
            }
        }

        if (!printed) {
            Object ias = iaudio();
            Object attrs = mediaAttributes();
            List<?> devs = (List<?>) findMethod(ias.getClass(), "getDevicesForAttributes", 2)
                    .invoke(ias, attrs, false);
            for (Object d : devs) {
                int type = num(call(d, "getType"));
                System.out.printf("[dev] type=%d(%s) name=%s address=%s%n",
                        type, typeName(type), str(call(d, "getName")), str(call(d, "getAddress")));
            }
        }

        System.out.println("=== PRODUCT STRATEGIES ===");
        int mediaId = findMediaStrategyId();
        if (mediaId < 0) {
            System.out.println("[warn] 未能自动识别媒体策略，请手动用 set <id> ...");
        }
        for (Object s : productStrategies()) {
            int sid = num(call(s, "getId"));
            boolean isMedia = (sid == mediaId);
            String sname = "";
            try {
                sname = str(call(s, "getName"));
            } catch (Throwable ignore) {
            }
            System.out.printf("%s[strategy] id=%d name=%s media?=%s%s%n",
                    (isMedia ? ">> " : "   "), sid, sname, supportsMedia(s),
                    (isMedia ? "   <<< 用这条" : ""));
            System.out.println("            attr      = " + str(call(s, "getAudioAttributes")));
            System.out.println("            full      = " + s);
            try {
                Object pd = findMethod(am.getClass(), "getPreferredDevicesForStrategy", 1)
                        .invoke(am, s);
                System.out.println("            preferred = " + pd);
            } catch (Throwable ignore) {
            }
        }
    }

    /* ---------------- set / clear ---------------- */
    static void set(int sid, List<Object> devs) throws Exception {
        Object r = trySet(sid, devs);
        System.out.println(Boolean.TRUE.equals(r) ? "已绑定" : "绑定失败");
    }

    /** 真正的设置动作；返回 API 的原始返回值（boolean 或 int），失败返回 null */
    static Object trySet(int sid, List<Object> devs) {
        Object strategy = tryFindStrategy(sid);
        if (am != null && strategy != null) {
            try {
                return findMethod(am.getClass(), "setPreferredDevicesForStrategy", 2)
                        .invoke(am, strategy, devs);
            } catch (Throwable ignore) {
            }
        }
        try {
            Object ias = iaudio();
            return findMethod(ias.getClass(), "setPreferredDevicesForStrategy", 2)
                    .invoke(ias, Integer.valueOf(sid), devs);
        } catch (Throwable t) {
            return null;
        }
    }

    static void clear(int sid) throws Exception {
        Object strategy = tryFindStrategy(sid);
        Throwable mainErr = null;
        if (am != null && strategy != null) {
            try {
                findMethod(am.getClass(), "removePreferredDeviceForStrategy", 1)
                        .invoke(am, strategy);
                System.out.println("已清除绑定");
                return;
            } catch (Throwable t) {
                // 注意：这里以前是静默吞掉异常，导致失败原因彻底丢失，
                // 上层只能看到一个笼统的「关闭失败」。必须把原因带出去。
                mainErr = t;
            }
        }
        Object ias = iaudio();
        try {
            findMethod(ias.getClass(), "removePreferredDeviceForStrategy", 1)
                    .invoke(ias, Integer.valueOf(sid));
            System.out.println("已清除绑定");
        } catch (Throwable t) {
            System.out.println("[warn] 清除绑定失败 · AudioManager 路径: " + mainErr);
            System.out.println("[warn] 清除绑定失败 · IAudioService 路径: " + t);
            throw t;
        }
    }

    /* ---------------- 工具 ---------------- */
    static List<?> productStrategies() throws Exception {
        // 优先走 AudioManager 实例方法（已确认可用），完全绕开类名/包名问题
        if (am != null) {
            try {
                Object r = findMethod(am.getClass(), "getAudioProductStrategies", 0).invoke(am);
                if (r instanceof List && !((List<?>) r).isEmpty()) return (List<?>) r;
            } catch (Throwable ignore) {
            }
        }
        // 兜底：AOSP 真实包名是 android.media.audiopolicy，两个都试
        for (String cn : new String[]{
                "android.media.audiopolicy.AudioProductStrategy",
                "android.media.AudioProductStrategy"}) {
            try {
                Class<?> c = Class.forName(cn);
                Object r = findMethod(c, "getAudioProductStrategies", 0).invoke(null);
                if (r instanceof List && !((List<?>) r).isEmpty()) return (List<?>) r;
            } catch (Throwable ignore) {
            }
        }
        throw new NoSuchMethodException("getAudioProductStrategies not available on this build");
    }

    static Object tryFindStrategy(int id) {
        try {
            for (Object s : productStrategies()) {
                if (num(call(s, "getId")) == id) return s;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    static Object findStrategy(int id) throws Exception {
        Object s = tryFindStrategy(id);
        if (s == null) {
            throw new IllegalArgumentException("strategy id " + id + " not found, run 'list' first");
        }
        return s;
    }

    static Object mediaAttributes() throws Exception {
        return attributesWithUsage(1); // USAGE_MEDIA
    }

    static Object attributesWithUsage(int usage) throws Exception {
        Class<?> abb = Class.forName("android.media.AudioAttributes$Builder");
        Object b = abb.getDeclaredConstructor().newInstance();
        abb.getMethod("setUsage", int.class).invoke(b, Integer.valueOf(usage));
        return abb.getMethod("build").invoke(b);
    }

    /** 某个 strategy 是否处理 USAGE_MEDIA */
    static boolean supportsMedia(Object strategy) {
        // 判据1：supportsAudioAttributes(USAGE_MEDIA)
        try {
            Object r = findMethod(strategy.getClass(), "supportsAudioAttributes", 1)
                    .invoke(strategy, mediaAttributes());
            if (Boolean.TRUE.equals(r)) return true;
        } catch (Throwable ignore) {
        }
        // 判据2：该策略对 USAGE_MEDIA 能给出有效 volume group（说明它确实处理 media）
        try {
            Object vg = findMethod(strategy.getClass(), "getVolumeGroupIdForAudioAttributes", 1)
                    .invoke(strategy, mediaAttributes());
            if (num(vg) >= 0) return true;
        } catch (Throwable ignore) {
        }
        return false;
    }

    /** 自动找出媒体策略的 id，找不到返回 -1（绝不瞎猜，避免误绑到通话策略） */
    static int findMediaStrategyId() {
        try {
            for (Object s : productStrategies()) {
                if (supportsMedia(s)) return num(call(s, "getId"));
            }
        } catch (Throwable ignore) {
        }
        return -1;
    }

    static Object newAda(int type, String addr) throws Exception {
        try {
            Constructor<?> c = adaClass.getDeclaredConstructor(int.class, String.class);
            c.setAccessible(true);
            return c.newInstance(Integer.valueOf(type), addr);
        } catch (NoSuchMethodException e) {
            // Android 13+ 的 4 参构造 (role, type, address, name)
            for (Constructor<?> c : adaClass.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 4 && p[0] == int.class && p[1] == int.class
                        && p[2] == String.class && p[3] == String.class) {
                    c.setAccessible(true);
                    return c.newInstance(Integer.valueOf(1) /* ROLE_OUTPUT */,
                            Integer.valueOf(type), addr, "");
                }
            }
            throw e;
        }
    }

    static Method findMethod(Class<?> cls, String name, int argc) throws Exception {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argc) return m;
        }
        throw new NoSuchMethodException(cls.getName() + "#" + name + "/" + argc);
    }

    static Object call(Object target, String name, Object... args) throws Exception {
        return findMethod(target.getClass(), name, args.length).invoke(target, args);
    }

    /** 自动挑选：一个蓝牙 A2DP + 一个 USB 声卡/耳机 */
    static List<Object> autoPickDevices() throws Exception {
        List<Object> out = new ArrayList<Object>();
        if (am == null) return out;
        Object[] devs = (Object[]) findMethod(am.getClass(), "getDevices", 1).invoke(am, 2);
        Object bt = null, usb = null;
        for (Object d : devs) {
            int t = num(call(d, "getType"));
            if (isBtOut(t) && bt == null) {
                bt = adaFromInfo(d);
            } else if (isUsbOut(t) && usb == null) {
                usb = adaFromInfo(d);
            }
        }
        if (bt != null) out.add(bt);
        if (usb != null) out.add(usb);
        return out;
    }

    /** 用 AudioDeviceInfo 直接构造 AudioDeviceAttributes（最保真，自带 role/name/address） */
    static Object adaFromInfo(Object info) throws Exception {
        try {
            Class<?> infoCls = Class.forName("android.media.AudioDeviceInfo");
            Constructor<?> c = adaClass.getDeclaredConstructor(infoCls);
            c.setAccessible(true);
            return c.newInstance(info);
        } catch (Throwable t) {
            return newAda(num(call(info, "getType")), str(call(info, "getAddress")));
        }
    }

    /* ---------------- 按设备独立控制音量 ---------------- */
    static void volumeList() throws Exception {
        Object[] devs = (Object[]) findMethod(am.getClass(), "getDevices", 1).invoke(am, 2);
        int max = streamMax();
        System.out.println("[vol] STREAM_MUSIC max index = " + max);
        for (Object d : devs) {
            int t = num(call(d, "getType"));
            if (t <= 0) continue;
            int nd = nativeDevice(t);
            int idx = readIndex(nd);
            System.out.printf("[vol] type=%d(%s) name=%s  native=0x%x  index=%d%n",
                    t, typeName(t), str(call(d, "getProductName")), nd, idx);
        }
    }

    static int readIndex(int nativeDev) {
        try {
            return num(findMethod(sysCls, "getStreamVolumeIndex", 2).invoke(null, 3, nativeDev));
        } catch (Throwable ignore) {
        }
        try {
            return num(findMethod(sysCls, "getStreamVolumeIndex", 3).invoke(null, 3, nativeDev, -1));
        } catch (Throwable ignore) {
        }
        return -1;
    }

    static void volumeSet(String target, int pct) throws Exception {
        Object info = findDevAttr(target);
        if (info == null) {
            System.out.println("没有找到对应设备");
            return;
        }
        String nm = str(call(info, "getProductName"));
        int t = num(call(info, "getType"));
        int nd = nativeDevice(t);

        int max = streamMax();
        int idx = (int) Math.round(pct * max / 100.0);
        if (idx < 0) idx = 0;
        if (idx > max) idx = max;

        if (isBtOut(t)) {
            // 蓝牙必须开启 AVRCP 绝对音量。
            // AudioService 对 A2DP 设备把 setDeviceVolumeBehavior 解释为
            // "是否启用绝对音量同步"：传入非 ABSOLUTE 的值会把它关掉，
            // 之后手机侧音量就再也同步不到耳机（表现为"音量调不动"）。
            setDevVolumeBehavior(info,
                    volumeBehaviorConst("DEVICE_VOLUME_BEHAVIOR_ABSOLUTE", 4));
        }

        int before = readIndex(nd);

        // 优先用"按设备设置音量"接口：最精确，不依赖"谁是主设备"的判定
        boolean direct = setDeviceIndex(idx, nd);
        if (!direct) {
            if (isBtOut(t)) {
                globalVolume(pct);              // 双路绑定时主设备即蓝牙，等同音量键
            } else {
                // USB 声卡：独占 -> 设置 -> 恢复双路（它需要成为主设备才受控）
                int sid = findMediaStrategyId();
                List<Object> both = autoPickDevices();
                if (sid >= 0 && !both.isEmpty()) {
                    List<Object> one = new ArrayList<Object>();
                    one.add(adaFromInfo(info));
                    trySet(sid, one);
                    Thread.sleep(500);
                    globalVolume(pct);
                    Thread.sleep(300);
                    trySet(sid, both);
                }
            }
        }

        if (!isBtOut(t)) {
            System.out.println(nm + " 音量已设为 " + pct);
            return;
        }

        // 蓝牙：采样确认。系统可能在约 1 秒后按多设备规则重算一遍音量，
        // 只读一次会误判为成功，所以取两个时间点比对。
        Thread.sleep(800);
        int mid = readIndex(nd);
        Thread.sleep(1200);
        int end = readIndex(nd);

        int tol = Math.max(2, max / 10);
        if (end == mid && Math.abs(end - idx) <= tol) {
            System.out.println(nm + " 音量 " + before + " → " + end
                    + "（上限 " + max + "）");
            return;
        }

        // 值被改回或未生效 —— 改走耳机侧音量通道，它不受系统重算影响
        String addr = str(call(info, "getAddress"));
        if (avrcpSetVolume(addr, pct)) {
            Thread.sleep(900);
            System.out.println(nm + " 已设为 " + pct + "%（耳机音量通道，当前 "
                    + readIndex(nd) + "/" + max + "）");
        } else if (end != before) {
            System.out.println(nm + " 音量 " + before + " → " + mid
                    + "，约 1 秒后被系统改回 " + end);
        } else {
            System.out.println(nm + " 音量未能改变（耳机不支持音量同步）");
        }
    }

    /** 调用"按设备设置音量"接口，成功返回 true */
    static boolean setDeviceIndex(int idx, int nativeDev) {
        for (String mn : new String[]{"setStreamVolumeIndexAS", "setStreamVolumeIndex"}) {
            try {
                findMethod(sysCls, mn, 3).invoke(null, 3, idx, nativeDev);
                return true;
            } catch (Throwable ignore) {
            }
        }
        return false;
    }

    /**
     * 直接设置蓝牙耳机自身音量（AVRCP 绝对音量通道）。
     * 走 IBluetooth#getA2dpService() -> IBluetoothA2dp#setAbsoluteVolume()，
     * 绕开 AudioService 的音量数据库，因而不受多设备音量重算影响。
     */
    static boolean avrcpSetVolume(String addr, int pct) {
        try {
            if (addr == null || addr.length() == 0 || addr.startsWith("00:00")) return false;
            Class<?> baCls = Class.forName("android.bluetooth.BluetoothAdapter");
            Object adapter = baCls.getMethod("getDefaultAdapter").invoke(null);
            if (adapter == null) return false;
            Object dev = baCls.getMethod("getRemoteDevice", String.class).invoke(adapter, addr);

            Class<?> ib = Class.forName("android.os.IBinder");
            Object binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "bluetooth_manager");
            if (binder == null) return false;

            Object iBt = Class.forName("android.bluetooth.IBluetooth$Stub")
                    .getMethod("asInterface", ib).invoke(null, binder);
            Object a2dpBinder = findMethod(iBt.getClass(), "getA2dpService", 0).invoke(iBt);
            if (a2dpBinder == null) return false;

            Object a2dp = Class.forName("android.bluetooth.IBluetoothA2dp$Stub")
                    .getMethod("asInterface", ib).invoke(null, a2dpBinder);
            int v = (int) Math.round(pct * 127.0 / 100.0);
            if (v < 0) v = 0;
            if (v > 127) v = 127;
            findMethod(a2dp.getClass(), "setAbsoluteVolume", 2)
                    .invoke(a2dp, dev, Integer.valueOf(v));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 反射读取 AudioManager 里的音量行为常量，避免硬编码取值出错 */
    static int volumeBehaviorConst(String name, int fallback) {
        try {
            return Class.forName("android.media.AudioManager")
                    .getField(name).getInt(null);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static Object findDevAttr(String target) throws Exception {
        Object[] devs = (Object[]) findMethod(am.getClass(), "getDevices", 1).invoke(am, 2);
        for (Object d : devs) {
            if (targetMatches(target, num(call(d, "getType")))) return d;
        }
        return null;
    }

    /** 确保蓝牙的 AVRCP 绝对音量处于开启状态（关闭后手机侧音量无法传给耳机） */
    static void ensureBtAbsoluteVolume() {
        try {
            int abs = volumeBehaviorConst("DEVICE_VOLUME_BEHAVIOR_ABSOLUTE", 4);
            Object[] devs = (Object[]) findMethod(am.getClass(), "getDevices", 1).invoke(am, 2);
            for (Object d : devs) {
                if (isBtOut(num(call(d, "getType")))) setDevVolumeBehavior(d, abs);
            }
        } catch (Throwable ignore) {
        }
    }

    static void setDevVolumeBehavior(Object info, int behavior) {
        try {
            Object ada = adaFromInfo(info);
            findMethod(am.getClass(), "setDeviceVolumeBehavior", 2)
                    .invoke(am, ada, Integer.valueOf(behavior));
        } catch (Throwable ignore) {
        }
    }

    static void globalVolume(int pct) {
        int max = streamMax();
        int idx = (int) Math.round(pct * max / 100.0);
        if (idx < 0) idx = 0;
        if (idx > max) idx = max;
        try {
            findMethod(am.getClass(), "setStreamVolume", 3)
                    .invoke(am, 3, Integer.valueOf(idx), Integer.valueOf(0));
        } catch (Throwable ignore) {
        }
    }

    /* ---------------- 供 App 读取的简洁状态 ---------------- */
    static void status() {
        System.out.println("dexver=" + DEX_VER);
        int dual = 0;
        try {
            for (Object s : productStrategies()) {
                if (!supportsMedia(s)) continue;
                Object pd = findMethod(am.getClass(), "getPreferredDevicesForStrategy", 1)
                        .invoke(am, s);
                // 用设备类型精确判定，不用字符串匹配 ——
                // BLE 耳机的类型名不含 "bt_a2dp"，字符串匹配会把已绑定误判为未绑定
                boolean hb = false, hu = false;
                int known = 0;
                if (pd instanceof java.util.List) {
                    for (Object a : (java.util.List<?>) pd) {
                        int t = num(call(a, "getType"));
                        if (t < 0) continue;
                        known++;
                        if (isBtOut(t)) hb = true;
                        else if (isUsbOut(t)) hu = true;
                    }
                }
                if (known == 0) {          // 取不到类型时退回字符串匹配
                    String d = String.valueOf(pd);
                    hb = d.contains("bt_a2dp") || d.contains("ble_");
                    hu = d.contains("usb_");
                }
                dual = (hb && hu) ? 1 : 0;
                break;
            }
        } catch (Throwable ignore) {
        }
        System.out.println("dual=" + dual);

        try {
            String out = execCapture("tinymix -D 1 'PCM Playback Volume'");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(":\\s*(\\d+)").matcher(out);
            System.out.println("usb=" + (m.find() ? m.group(1) : "-1"));
        } catch (Throwable t) {
            System.out.println("usb=-1");
        }

        try {
            int max = streamMax();
            int idx = readIndex(nativeDevice(8));
            System.out.println("bt=" + ((idx >= 0 && max > 0) ? (idx * 100 / max) : -1));
        } catch (Throwable t) {
            System.out.println("bt=-1");
        }

        // 设备在线状态 + 设备名
        try {
            boolean hasBt = false, hasUsb = false;
            String btName = "", usbName = "";
            Object[] devs = (Object[]) findMethod(am.getClass(), "getDevices", 1).invoke(am, 2);
            for (Object d : devs) {
                int t = num(call(d, "getType"));
                String nm = str(call(d, "getProductName"));
                if (isBtOut(t)) {
                    hasBt = true;
                    if (btName.length() == 0) btName = nm;
                } else if (isUsbOut(t)) {
                    hasUsb = true;
                    if (usbName.length() == 0) usbName = nm;
                }
            }
            System.out.println("btdev=" + (hasBt ? 1 : 0));
            System.out.println("usbdev=" + (hasUsb ? 1 : 0));
            System.out.println("btname=" + btName);
            System.out.println("usbname=" + usbName);
        } catch (Throwable t) {
            System.out.println("btdev=0");
            System.out.println("usbdev=0");
            System.out.println("btname=");
            System.out.println("usbname=");
        }
    }

    static String execCapture(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            r.close();
            p.waitFor();
        } catch (Throwable ignore) {
        }
        return sb.toString();
    }

    static void sysApi() {
        System.out.println("=== AudioSystem: volume-related methods ===");
        try {
            for (Method m : sysCls.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("volume")) System.out.println("  " + m);
            }
        } catch (Throwable t) {
            System.out.println("[warn] " + t);
        }
        System.out.println("=== AudioManager: volume-related methods ===");
        try {
            for (Method m : am.getClass().getMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("volume")) System.out.println("  " + m);
            }
        } catch (Throwable t) {
            System.out.println("[warn] " + t);
        }
    }

    static void alsaCmd(String[] args) {
        String[] cands = {"/system/bin/tinymix", "/vendor/bin/tinymix",
                "/system_ext/bin/tinymix", "/data/local/tmp/tinymix"};
        String bin = null;
        for (String c : cands) {
            if (new File(c).exists()) { bin = c; break; }
        }
        System.out.println("[alsa] tinymix = " + (bin == null ? "未找到" : bin));
        if (bin == null) return;
        StringBuilder cmd = new StringBuilder(bin);
        if (args.length == 1) {
            cmd.append(" -D 1");            // 默认看 USB 声卡(card=1) 的 mixer 控件
        } else {
            for (int i = 1; i < args.length; i++) {
                cmd.append(" '").append(args[i]).append("'");
            }
        }
        exec(cmd.toString());
    }

    static void exec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) System.out.println(line);
            r.close();
            p.waitFor();
        } catch (Throwable t) {
            System.out.println("[warn] exec failed: " + t);
        }
    }

    static boolean targetMatches(String target, int type) {
        if ("bt".equals(target)) return type == 8 || type == 7;
        if ("usb".equals(target)) return type == 22 || type == 11 || type == 12;
        if ("speaker".equals(target)) return type == 2;
        if ("earpiece".equals(target)) return type == 1;
        if ("wired".equals(target)) return type == 3 || type == 4;
        return false;
    }

    static int streamMax() {
        try {
            int m = num(findMethod(am.getClass(), "getStreamMaxVolume", 1).invoke(am, 3));
            return m > 0 ? m : 15;
        } catch (Throwable t) {
            return 15;
        }
    }

    /** AudioDeviceInfo.TYPE_* -> audio_devices_t (0x..) */
    static int nativeDevice(int infoType) {
        switch (infoType) {
            case 1:  return 0x1;          // EARPIECE
            case 2:  return 0x2;          // SPEAKER
            case 3:  return 0x4;          // WIRED_HEADSET
            case 4:  return 0x8;          // WIRED_HEADPHONE
            case 5:  return 0x20;         // LINE_ANALOG
            case 7:  return 0x10;         // BLUETOOTH_SCO
            case 8:  return 0x80;         // BLUETOOTH_A2DP
            case 9:  return 0x100;        // HDMI
            case 10: return 0x400;        // HDMI_ARC
            case 11: return 0x4000;       // USB_DEVICE
            case 12: return 0x2000;       // USB_ACCESSORY
            case 22: return 0x4000000;    // USB_HEADSET
            case 23: return 0x8000000;    // HEARING_AID
            case 26: return 0x40000000;   // BLE_HEADSET
            case 27: return 0x20000000;   // BLE_SPEAKER
            default: return 0;
        }
    }

    static String safeName(Object strategy) {
        try {
            String n = str(call(strategy, "getName"));
            if (n != null && !"null".equals(n) && !n.isEmpty()) return n;
        } catch (Throwable ignore) {
        }
        return "";
    }

    static int num(Object o) {
        return (o instanceof Number) ? ((Number) o).intValue() : -1;
    }

    /** 失败输出：机器可读的 res 码 + 给用户看的中文说明 */
    static void fail(String code, String msg) {
        System.out.println("res=" + code);
        System.out.println("msg=" + msg);
    }

    static String str(Object o) {
        return o == null ? "null" : String.valueOf(o);
    }

    static void exemptHiddenApi() {
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vm.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object rt = getRuntime.invoke(null);
            Method setEx = vm.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setEx.setAccessible(true);
            setEx.invoke(rt, new Object[]{new String[]{"L"}});
        } catch (Throwable ignore) {
        }
    }

    /** 是否为蓝牙输出设备（A2DP 与 BLE 耳机） */
    static boolean isBtOut(int t) {
        return t == 8 || t == 26 || t == 27;
    }

    /** 是否为 USB 声卡/耳机 */
    static boolean isUsbOut(int t) {
        return t == 22 || t == 11 || t == 12;
    }

    static String typeName(int t) {
        switch (t) {
            case 1:  return "EARPIECE";
            case 2:  return "SPEAKER";
            case 3:  return "WIRED_HEADSET";
            case 4:  return "WIRED_HEADPHONE";
            case 5:  return "LINE_ANALOG";
            case 6:  return "LINE_DIGITAL";
            case 7:  return "BLUETOOTH_SCO";
            case 8:  return "BLUETOOTH_A2DP";
            case 9:  return "HDMI";
            case 10: return "HDMI_ARC";
            case 11: return "USB_DEVICE";
            case 12: return "USB_ACCESSORY";
            case 13: return "DOCK";
            case 18: return "TELEPHONY";
            case 19: return "AUX_LINE";
            case 22: return "USB_HEADSET";
            case 23: return "HEARING_AID";
            case 24: return "SPEAKER_SAFE";
            case 25: return "REMOTE_SUBMIX";
            case 26: return "BLE_HEADSET";
            case 27: return "BLE_SPEAKER";
            case 28: return "ECHO_REFERENCE";
            case 29: return "HDMI_EARC";
            case 30: return "BLE_BROADCAST";
            default: return "TYPE_" + t;
        }
    }
}
