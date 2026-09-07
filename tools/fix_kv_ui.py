# -*- coding: utf-8 -*-
# 修复 PerformanceDashboardFragment：import 行 + 加 AILogger import + TAG 常量
import io, sys
path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\performance\PerformanceDashboardFragment.java"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

def rep(old, new, label):
    global src
    c = src.count(old)
    if c != 1:
        print("[FAIL] %s: count=%d" % (label, c)); sys.exit(1)
    src = src.replace(old, new, 1)
    print("[OK] %s" % label)

# 1) 修复被合并的 import 行 + 补 AILogger
rep(
'''import android.content.Intent;
import android.os.Bundle;import android.os.Handler;
import android.os.Looper;''',
'''import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;''',
'fix bundle import')

rep(
'''import com.oilquiz.app.ui.activity.ModelSelectorActivity;

import java.util.ArrayList;''',
'''import com.oilquiz.app.ui.activity.ModelSelectorActivity;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;''',
'add AILogger import')

# 2) 加 TAG 常量
rep(
'''public class PerformanceDashboardFragment extends Fragment {

    private TextView tpsValue;''',
'''public class PerformanceDashboardFragment extends Fragment {

    private static final String TAG = "PerformanceDashboard";

    private TextView tpsValue;''',
'add TAG')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("FRAGMENT FIX OK")
