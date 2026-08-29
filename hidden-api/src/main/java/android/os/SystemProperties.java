package android.os;

/**
 * {@code android.os.SystemProperties} 的桩。方法体不会被执行。
 *
 * <p>本模块只能覆盖<b>整体 @hide 的类</b>。公开类上的隐藏成员（如
 * {@code PowerManager#goToSleep}）会和 android.jar 撞车，那种情况仍需反射。
 */
public class SystemProperties {

    public static String get(String key) {
        throw new UnsupportedOperationException("stub");
    }

    public static String get(String key, String def) {
        throw new UnsupportedOperationException("stub");
    }

    public static int getInt(String key, int def) {
        throw new UnsupportedOperationException("stub");
    }

    public static long getLong(String key, long def) {
        throw new UnsupportedOperationException("stub");
    }

    public static boolean getBoolean(String key, boolean def) {
        throw new UnsupportedOperationException("stub");
    }

    /**
     * 写入需要调用方通过 SELinux 的 set_prop 检查，普通应用 UID 一律失败。
     * 只在宿主进程里（system UID）尝试，且必须当成可能失败来处理。
     */
    public static void set(String key, String val) {
        throw new UnsupportedOperationException("stub");
    }
}
