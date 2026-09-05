package com.tungsten.fclauncher.utils;

import android.content.Context;
import java.io.File;

/**
 * Shim: 原为 FCLauncher 的全局路径表。
 * 星云更新器 vendored fclcore 时用我们自己的目录结构初始化（App 启动时调用 loadPaths）。
 */
public class FCLPath {
    public static String NATIVE_LIB_DIR;
    public static String LOG_DIR;
    public static String CACHE_DIR;
    public static String RUNTIME_DIR;
    public static String MOD_RUNTIME_DIR;
    public static String JAVA_8_PATH;
    public static String JAVA_17_PATH;
    public static String JAVA_21_PATH;
    public static String JAVA_25_PATH;
    public static String JAVA_PATH;
    public static String JNA_PATH;
    public static String LWJGL_DIR;
    public static String CACIOCAVALLO_8_DIR;
    public static String CACIOCAVALLO_17_DIR;
    public static String FILES_DIR;
    public static String PLUGIN_DIR;
    public static String BACKGROUND_DIR;
    public static String SKIN_DIR;
    public static String CONTROLLER_DIR;
    public static String SHARE_DIR;
    public static String PRIVATE_COMMON_DIR;
    public static String SHARED_COMMON_DIR;
    public static String AUTHLIB_INJECTOR_PATH;
    public static String LIB_PATCHER_PATH;
    public static String MIO_LAUNCH_WRAPPER;
    public static String LT_BACKGROUND_PATH;

    private static boolean initialized = false;

    public static synchronized void loadPaths(Context ctx) {
        if (initialized) return;
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File root = new File(base, "fclcore").getAbsoluteFile();
        LOG_DIR = init(new File(root, "log"));
        CACHE_DIR = init(new File(ctx.getCacheDir(), "fclcore"));
        RUNTIME_DIR = init(new File(root, "runtime"));
        MOD_RUNTIME_DIR = init(new File(root, "mod_runtime"));
        JAVA_PATH = init(new File(root, "java"));
        JAVA_8_PATH = init(new File(JAVA_PATH, "8"));
        JAVA_17_PATH = init(new File(JAVA_PATH, "17"));
        JAVA_21_PATH = init(new File(JAVA_PATH, "21"));
        JAVA_25_PATH = init(new File(JAVA_PATH, "25"));
        JNA_PATH = init(new File(root, "jna"));
        LWJGL_DIR = init(new File(root, "lwjgl"));
        CACIOCAVALLO_8_DIR = init(new File(root, "cacio8"));
        CACIOCAVALLO_17_DIR = init(new File(root, "cacio17"));
        FILES_DIR = init(new File(root, "files"));
        PLUGIN_DIR = init(new File(root, "plugin"));
        BACKGROUND_DIR = init(new File(root, "background"));
        SKIN_DIR = init(new File(root, "skin"));
        CONTROLLER_DIR = init(new File(root, "controller"));
        SHARE_DIR = init(new File(root, "share"));
        PRIVATE_COMMON_DIR = init(new File(root, "private_common"));
        SHARED_COMMON_DIR = init(new File(root, "shared_common"));
        AUTHLIB_INJECTOR_PATH = init(new File(root, "authlib-injector.jar"));
        LIB_PATCHER_PATH = init(new File(root, "lib-patcher"));
        MIO_LAUNCH_WRAPPER = init(new File(root, "mio-wrapper"));
        LT_BACKGROUND_PATH = init(new File(root, "lt_background"));
        initialized = true;
    }

    private static String init(File f) {
        if (!f.exists()) f.mkdirs();
        return f.getAbsolutePath();
    }
}
