package com.tungsten.fcl.nebula;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tungsten.fclcore.mod.curse.CurseAddon;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CurseForge 无 key 镜像解析（cfwidget + forgecdn 直链）。
 *
 * 当官方 api.curseforge.com 不可用（未配置 CURSE_API_KEY）时，
 * 用 cfwidget.com 查询文件真实文件名，并通过 CurseAddon.LatestFile
 * 自带的 forgecdn 直链规则（files/id/1000/id%1000/name）下载。
 */
public final class NebulaCfMirror {

    private NebulaCfMirror() {
    }

    /** 用 cfwidget 解析单个文件；失败返回 null。 */
    public static CurseAddon.LatestFile resolveFile(String projectId, String fileId) {
        try {
            int fileIdInt = Integer.parseInt(fileId.trim());
            int projectIdInt = Integer.parseInt(projectId.trim());
            JsonObject root = fetchJson("https://api.cfwidget.com/" + projectIdInt);
            JsonArray files = root.getAsJsonArray("files");
            if (files == null) return null;
            for (JsonElement el : files) {
                JsonObject f = el.getAsJsonObject();
                if (!f.has("id")) continue;
                if (f.get("id").getAsInt() != fileIdInt) continue;
                String name = null;
                if (f.has("name")) name = f.get("name").getAsString();
                if (name == null || name.isEmpty()) {
                    if (f.has("display")) name = f.get("display").getAsString();
                }
                if (name == null || name.isEmpty()) return null;
                int size = f.has("filesize") ? f.get("filesize").getAsInt() : 0;
                List<String> versions = new ArrayList<>();
                if (f.has("version")) {
                    String v = f.get("version").getAsString();
                    if (v != null && !v.isEmpty()) versions.add(v);
                }
                return new CurseAddon.LatestFile(
                        fileIdInt, 432, projectIdInt, true,
                        name,
                        name,
                        1, 4,
                        Collections.emptyList(),
                        Instant.now(),
                        size,
                        0,
                        null,
                        versions,
                        Collections.emptyList(),
                        0, false, 0L);
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    private static JsonObject fetchJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NebulaUpdater");
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } finally {
            conn.disconnect();
        }
    }
}