/*
 * Nebula vendored shim — 原类在 FCL 上游 game 包内（GPL-3.0, FCL-Team/FoldCraftLauncher）。
 * 供 DefaultGameRepository 在版本刷新完成后 fireEvent 使用。
 */
package com.tungsten.fclcore.game;

import com.tungsten.fclcore.event.Event;

public class RefreshedVersionsEvent extends Event {
    public RefreshedVersionsEvent(DefaultGameRepository source) {
        super(source);
    }
}
