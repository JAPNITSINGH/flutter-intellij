/*
 * Copyright 2016 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.actions;

import com.intellij.openapi.actionSystem.ActionPlaces;

/**
 * A keystroke or toolbar invoked {@link ReloadFlutterApp} action.
 */
public class ReloadFlutterAppRetarget extends FlutterRetargetAppAction {
  public ReloadFlutterAppRetarget() {
    super(ReloadFlutterApp.ID,
          ReloadFlutterApp.TEXT,
          ReloadFlutterApp.DESCRIPTION,
          ActionPlaces.MAIN_TOOLBAR,
          ActionPlaces.NAVIGATION_BAR_TOOLBAR,
          ActionPlaces.MAIN_MENU);
  }
}
