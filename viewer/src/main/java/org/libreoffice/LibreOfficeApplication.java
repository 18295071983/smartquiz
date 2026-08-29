/*
 *
 *  * This file is part of the LibreOffice project.
 *  *
 *  * This Source Code Form is subject to the terms of the Mozilla Public
 *  * License, v. 2.0. If a copy of the MPL was not distributed with this
 *  * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

package org.libreoffice;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

public class LibreOfficeApplication extends Application {

    private static Handler mainHandler;

    public LibreOfficeApplication() {
    }

    /** 由查看器 Activity 在 onCreate 主线程调用，确保 mainHandler 已初始化。 */
    public static void init() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
    }

    public static Handler getMainHandler() {
        return mainHandler;
    }
}
