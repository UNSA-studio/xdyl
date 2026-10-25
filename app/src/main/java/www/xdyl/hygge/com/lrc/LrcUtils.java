/*
 * Copyright (C) 2017 wangchenyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package www.xdyl.hygge.com.lrc;

import android.animation.ValueAnimator;
import android.os.Build;
import android.text.TextUtils;
import android.text.format.DateUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具类
 */
class LrcUtils {
    private static final Pattern PATTERN_LINE = Pattern.compile("((\\[\\d\\d:\\d\\d\\.\\d{2,3}\\])+)(.+)");
    /** 字级时间标签 <mm:ss.xx> */
    private static final Pattern PATTERN_WORD_TIME = Pattern.compile("<(\\d{1,2}):(\\d{1,2})[.:](\\d{1,3})>");
    private static final Pattern PATTERN_TIME = Pattern.compile("\\[(\\d\\d):(\\d\\d)\\.(\\d{2,3})\\]");
    /** 中文（用于判断“翻译行”） */
    private static final Pattern PATTERN_CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    /**
     * 从文件解析双语歌词
     */
    static List<LrcEntry> parseLrc(File[] lrcFiles) {
        if (lrcFiles == null || lrcFiles.length != 2 || lrcFiles[0] == null) {
            return null;
        }

        File mainLrcFile = lrcFiles[0];
        File secondLrcFile = lrcFiles[1];
        List<LrcEntry> mainEntryList = parseLrc(mainLrcFile);
        List<LrcEntry> secondEntryList = parseLrc(secondLrcFile);

        if (mainEntryList != null && secondEntryList != null) {
            for (LrcEntry mainEntry : mainEntryList) {
                for (LrcEntry secondEntry : secondEntryList) {
                    if (mainEntry.getTime() == secondEntry.getTime()) {
                        mainEntry.setSecondText(secondEntry.getText());
                    }
                }
            }
        }
        return mainEntryList;
    }

    /**
     * 从文件解析歌词
     */
    private static List<LrcEntry> parseLrc(File lrcFile) {
        if (lrcFile == null || !lrcFile.exists()) {
            return null;
        }

        List<LrcEntry> entryList = new ArrayList<>();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(lrcFile), "utf-8"));
            String line;
            while ((line = br.readLine()) != null) {
                List<LrcEntry> list = parseLine(line);
                if (list != null && !list.isEmpty()) {
                    entryList.addAll(list);
                }
            }
            br.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        Collections.sort(entryList);
        return entryList;
    }

    /**
     * 从文本解析双语歌词
     */
    static List<LrcEntry> parseLrc(String[] lrcTexts) {
        if (lrcTexts == null || lrcTexts.length != 2 || TextUtils.isEmpty(lrcTexts[0])) {
            return null;
        }

        String mainLrcText = lrcTexts[0];
        String secondLrcText = lrcTexts[1];
        List<LrcEntry> mainEntryList = parseLrc(mainLrcText);
        List<LrcEntry> secondEntryList = parseLrc(secondLrcText);

        if (mainEntryList != null && secondEntryList != null) {
            for (LrcEntry mainEntry : mainEntryList) {
                for (LrcEntry secondEntry : secondEntryList) {
                    if (mainEntry.getTime() == secondEntry.getTime()) {
                        mainEntry.setSecondText(secondEntry.getText());
                    }
                }
            }
        }
        return mainEntryList;
    }

    /**
     * 从文本解析歌词
     */
    private static List<LrcEntry> parseLrc(String lrcText) {
        if (TextUtils.isEmpty(lrcText)) {
            return null;
        }

        if (lrcText.startsWith("\uFEFF")) {
            lrcText = lrcText.replace("\uFEFF", "");
        }

        List<LrcEntry> entryList = new ArrayList<>();
        String[] array = lrcText.split("\\n");
        for (String line : array) {
            List<LrcEntry> list = parseLine(line);
            if (list != null && !list.isEmpty()) {
                entryList.addAll(list);
            }
        }

        // 单文件双语：相邻的「原文 + 翻译」两行 → 第二行作为翻译。
        // 注意：QQ音乐/网易云的双语 LRC，中文翻译的时间戳常常和原文不完全相等（按中文演唱起始单独标了），
        // 所以不能只认时间戳完全相等，否则翻译会被当成独立的歌词行、污染“下一句开始时间”的预估。
        if (entryList.size() > 1) {
            List<LrcEntry> merged = new ArrayList<>();
            for (LrcEntry e : entryList) {
                LrcEntry prev = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (prev != null && TextUtils.isEmpty(prev.getSecondText()) && isTranslationPair(prev, e)) {
                    prev.setSecondText(e.getText());
                    if (prev.getEndTime() <= prev.getTime()) prev.setEndTime(e.getEndTime());
                } else {
                    merged.add(e);
                }
            }
            entryList = merged;
        }

        Collections.sort(entryList);
        return entryList;
    }

    /**
     * 判断 prev 与 cur 是否构成「原文 + 翻译」配对。
     * 条件：时间接近（翻译在原文之后 0~1 秒内）、且一个是中文一个是非中文。
     */
    private static boolean isTranslationPair(LrcEntry prev, LrcEntry cur) {
        long diff = cur.getTime() - prev.getTime();
        if (diff < 0 || diff > 1000) {
            return false;
        }
        boolean prevCn = PATTERN_CJK.matcher(prev.getText()).find();
        boolean curCn = PATTERN_CJK.matcher(cur.getText()).find();
        return prevCn != curCn;
    }

    /**
     * 获取网络文本，需要在工作线程中执行
     */
    static String getContentFromNetwork(String url, String charset) {
        String lrcText = null;
        try {
            URL _url = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) _url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() == 200) {
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    bos.write(buffer, 0, len);
                }
                is.close();
                bos.close();
                lrcText = bos.toString(charset);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return lrcText;
    }

    /**
     * 解析一行歌词
     */
    private static List<LrcEntry> parseLine(String line) {
        if (TextUtils.isEmpty(line)) {
            return null;
        }

        line = line.trim();
        // [00:17.65]让我掉下眼泪的
        Matcher lineMatcher = PATTERN_LINE.matcher(line);
        if (!lineMatcher.matches()) {
            return null;
        }

        String times = lineMatcher.group(1);
        String rawText = lineMatcher.group(3);

        // 剥离字级标签 <mm:ss.xx>，同时记录其时间与对应文本位置
        StringBuilder cleaned = new StringBuilder();
        java.util.List<Long> wTimes = new java.util.ArrayList<>();
        java.util.List<Integer> wIndices = new java.util.ArrayList<>();
        int ci = 0;
        while (ci < rawText.length()) {
            char ch = rawText.charAt(ci);
            if (ch == '<') {
                int close = rawText.indexOf('>', ci);
                if (close > ci) {
                    String inner = rawText.substring(ci + 1, close);
                    long t = parseWordTime(inner);
                    if (t >= 0) {
                        // 标签直接移除（原文本里的空格/换行全部保留）
                        wTimes.add(t);
                        wIndices.add(cleaned.length());
                        ci = close + 1;
                        continue;
                    }
                }
            }
            cleaned.append(ch);
            ci++;
        }
        String text = cleaned.toString();

        // 自定义格式：[开始]正文[结束] —— 末尾的时间标签作为本行结束时间
        long endTime = -1L;
        {
            Matcher tailM = PATTERN_TIME.matcher(text);
            int tailStart = -1;
            String tailStr = null;
            while (tailM.find()) {
                if (text.substring(tailM.end()).trim().isEmpty()) {
                    tailStart = tailM.start();
                    tailStr = tailM.group();
                }
            }
            if (tailStart >= 0 && tailStr != null) {
                Matcher one = PATTERN_TIME.matcher(tailStr);
                if (one.find()) {
                    try {
                        long mm = Long.parseLong(one.group(1));
                        long ss = Long.parseLong(one.group(2));
                        String ms = one.group(3);
                        long msv = Long.parseLong(ms);
                        if (ms.length() == 2) msv *= 10;
                        endTime = mm * DateUtils.MINUTE_IN_MILLIS + ss * DateUtils.SECOND_IN_MILLIS + msv;
                    } catch (Exception ignored) {
                    }
                }
                text = text.substring(0, tailStart).trim();
            }
        }

        List<LrcEntry> entryList = new ArrayList<>();

        // [00:17.65]
        Matcher timeMatcher = PATTERN_TIME.matcher(times);
        while (timeMatcher.find()) {
            long min = Long.parseLong(timeMatcher.group(1));
            long sec = Long.parseLong(timeMatcher.group(2));
            String milString = timeMatcher.group(3);
            long mil = Long.parseLong(milString);
            // 如果毫秒是两位数，需要乘以10
            if (milString.length() == 2) {
                mil = mil * 10;
            }
            long time = min * DateUtils.MINUTE_IN_MILLIS + sec * DateUtils.SECOND_IN_MILLIS + mil;
            LrcEntry entry = new LrcEntry(time, text);
            entry.setEndTime(endTime);
            if (!wTimes.isEmpty()) {
                long[] wt = new long[wTimes.size()];
                for (int k = 0; k < wt.length; k++) wt[k] = wTimes.get(k);
                int[] wi = new int[wIndices.size()];
                for (int k = 0; k < wi.length; k++) wi[k] = wIndices.get(k);
                entry.setWordTimes(wt);
                entry.setWordIndices(wi);
            }
            entryList.add(entry);
        }
        return entryList;
    }

    /** 解析 "mm:ss.xx" 为毫秒，不合法返回 -1 */
    private static long parseWordTime(String s) {
        try {
            String[] parts = s.trim().split(":");
            if (parts.length != 2) return -1;
            long min = Long.parseLong(parts[0].trim());
            String secPart = parts[1].trim().replace(':', '.');
            double secVal = Double.parseDouble(secPart);
            return min * 60000L + (long) (secVal * 1000);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 转为[分:秒]
     */
    static String formatTime(long milli) {
        int m = (int) (milli / DateUtils.MINUTE_IN_MILLIS);
        int s = (int) ((milli / DateUtils.SECOND_IN_MILLIS) % 60);
        String mm = String.format(Locale.getDefault(), "%02d", m);
        String ss = String.format(Locale.getDefault(), "%02d", s);
        return mm + ":" + ss;
    }

    /**
     * 强制开启动画
     * Android 10 以后无法使用
     */
    static void resetDurationScale() {
        try {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                Field mField = ValueAnimator.class.getDeclaredField("sDurationScale");
                mField.setAccessible(true);
                mField.setFloat(null, 1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
