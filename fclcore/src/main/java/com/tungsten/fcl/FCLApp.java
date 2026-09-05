package com.tungsten.fcl;

import android.content.Context;

/**
 * Shim: 原为 FCL Application。此处仅提供 getAppContext()。
 * 星云更新器启动时通过 init(context) 注入。
 */
public class FCLApp {
    private static volatile Context appContext;

    public static void init(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    public static Context getAppContext() {
        if (appContext == null)
            throw new IllegalStateException("FCLApp.init(context) 未调用（星云更新器集成层负责初始化）");
        return appContext;
    }
}
