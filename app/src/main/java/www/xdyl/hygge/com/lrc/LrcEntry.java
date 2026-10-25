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

import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * 一行歌词实体
 */
class LrcEntry implements Comparable<LrcEntry> {
    private long time;
    /** 该行结束时间（毫秒），-1 表示未提供（自定义格式 [开始]正文[结束]） */
    private long endTime = -1L;
    private String text;
    private String secondText;
    private StaticLayout staticLayout;
    private StaticLayout secondLayout;

    /** 字级时间（毫秒），null = 该行无字级信息 */
    private long[] wordTimes;
    /** 每个字（段）在文本中的起始索引 */
    private int[] wordIndices;
    /**
     * 歌词距离视图顶部的距离
     */
    private float offset = Float.MIN_VALUE;
    public static final int GRAVITY_CENTER = 0;
    public static final int GRAVITY_LEFT = 1;
    public static final int GRAVITY_RIGHT = 2;

    long getEndTime() {
        return endTime;
    }

    void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    LrcEntry(long time, String text) {
        this.time = time;
        this.text = text;
    }

    LrcEntry(long time, String text, String secondText) {
        this.time = time;
        this.text = text;
        this.secondText = secondText;
    }

    void init(TextPaint paint, TextPaint secondPaint, int width, int gravity) {
        Layout.Alignment align;
        switch (gravity) {
            case GRAVITY_LEFT:
                align = Layout.Alignment.ALIGN_NORMAL;
                break;

            default:
            case GRAVITY_CENTER:
                align = Layout.Alignment.ALIGN_CENTER;
                break;

            case GRAVITY_RIGHT:
                align = Layout.Alignment.ALIGN_OPPOSITE;
                break;
        }
        staticLayout = new StaticLayout(text, paint, width, align, 1f, 0f, false);
        if (!TextUtils.isEmpty(secondText)) {
            secondLayout = new StaticLayout(secondText, secondPaint, width, align, 1f, 0f, false);
        } else {
            secondLayout = null;
        }

        offset = Float.MIN_VALUE;
    }

    long getTime() {
        return time;
    }

    StaticLayout getStaticLayout() {
        return staticLayout;
    }

    StaticLayout getSecondLayout() {
        return secondLayout;
    }

    int getHeight() {
        int h = 0;
        if (staticLayout != null) {
            h += staticLayout.getHeight();
        }
        if (secondLayout != null) {
            h += secondLayout.getHeight();
        }
        return h;
    }

    public long[] getWordTimes() {
        return wordTimes;
    }

    public void setWordTimes(long[] wordTimes) {
        this.wordTimes = wordTimes;
    }

    public int[] getWordIndices() {
        return wordIndices;
    }

    public void setWordIndices(int[] wordIndices) {
        this.wordIndices = wordIndices;
    }

    public float getOffset() {
        return offset;
    }

    public void setOffset(float offset) {
        this.offset = offset;
    }

    String getText() {
        return text;
    }


    String getSecondText() {
        return secondText;
    }

    void setSecondText(String secondText) {
        this.secondText = secondText;
    }

    @Override
    public int compareTo(LrcEntry entry) {
        if (entry == null) {
            return -1;
        }
        return (int) (time - entry.getTime());
    }
}
