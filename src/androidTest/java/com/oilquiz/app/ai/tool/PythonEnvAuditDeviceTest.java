package com.oilquiz.app.ai.tool;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.oilquiz.app.ai.python.PythonToolManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;

/**
 * 内置 Python 环境审计（2026-09-27）：
 * 手机端 AI 在会话里说「内置 Python 不是完整 Linux / 装不出完全体」——本用例实测到底缺什么，
 * 用数据说话：标准库模块可导入性、平台能力（fork/multiprocessing/tkinter）、已打包的三方库。
 */
@RunWith(AndroidJUnit4.class)
public class PythonEnvAuditDeviceTest {

    private static final String CODE =
        "import sys, platform, importlib.util, json, os\n" +
        "std = ['ssl','_ssl','sqlite3','_sqlite3','lzma','_lzma','bz2','_bz2','zlib','_zlib','ctypes','_ctypes',\n" +
        "       'multiprocessing','tkinter','curses','readline','termios','fcntl','crypt','pwd','grp','resource',\n" +
        "       'mmap','select','socket','ssl','hashlib','hmac','zlib','decimal','fractions','statistics','asyncio',\n" +
        "       'concurrent.futures','venv','ensurepip','distutils','setuptools','pip','uuid','secrets','base64',\n" +
        "       'csv','json','xml.etree.ElementTree','html.parser','urllib.request','http.client','email','zipfile',\n" +
        "       'tarfile','gzip','sqlite3','logging','argparse','typing','dataclasses','enum','unittest','pdb']\n" +
        "std = sorted(set(std))\n" +
        "ok, bad = [], []\n" +
        "for m in std:\n" +
        "    try:\n" +
        "        s = importlib.util.find_spec(m)\n" +
        "        (ok if s is not None else bad).append(m)\n" +
        "    except Exception:\n" +
        "        bad.append(m)\n" +
        "third = ['numpy','pandas','matplotlib','PIL','lxml','cryptography','requests','bs4','docx','pptx','pypdf',\n" +
        "         'openpyxl','yaml','tabulate','dateutil','chardet','xlrd','reportlab','simplejson','regex','jieba']\n" +
        "tok, tbad = [], []\n" +
        "for m in third:\n" +
        "    try:\n" +
        "        (tok if importlib.util.find_spec(m) is not None else tbad).append(m)\n" +
        "    except Exception:\n" +
        "        tbad.append(m)\n" +
        "caps = {}\n" +
        "caps['fork'] = hasattr(os, 'fork')\n" +
        "caps['execv'] = hasattr(os, 'execv')\n" +
        "caps['spawn'] = hasattr(os, 'spawnv')\n" +
        "caps['getuid'] = hasattr(os, 'getuid')\n" +
        "try:\n" +
        "    import multiprocessing as mp; caps['mp_methods'] = mp.get_all_start_methods()\n" +
        "except Exception as e:\n" +
        "    caps['mp_methods'] = 'ERR ' + str(e)\n" +
        "try:\n" +
        "    import sysconfig; caps['py_ver'] = sys.version.split()[0]; caps['platform'] = sys.platform\n" +
        "    caps['stdlib_dir'] = sysconfig.get_paths().get('stdlib','?')\n" +
        "    caps['purelib'] = sysconfig.get_paths().get('purelib','?')\n" +
        "except Exception as e:\n" +
        "    caps['err'] = str(e)\n" +
        "try:\n" +
        "    import pip; caps['pip'] = getattr(pip,'__version__','?')\n" +
        "except Exception:\n" +
        "    caps['pip'] = 'missing'\n" +
        "print('AUDIT_JSON=' + json.dumps({'caps':caps,'std_ok':len(ok),'std_missing':bad,'third_ok':len(tok),'third_missing':tbad}, ensure_ascii=False))\n";

    @Test
    public void auditBuiltinPython() {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PythonToolManager ptm = PythonToolManager.getInstance(c);
        ptm.initialize();
        PythonToolManager.ExecutionResult r = ptm.executeCode(CODE, new HashMap<String, Object>());
        System.out.println("[EXP] audit success=" + r.success + " error=" + r.error);
        System.out.println("[EXP] stdout=" + r.stdout);
        System.out.println("[EXP] stderr=" + r.stderr);
        org.junit.Assert.assertTrue("审计脚本应执行成功: " + r.error + r.stderr, r.success);
        String out = String.valueOf(r.stdout);
        // 关键能力回归：这些是 App 现有功能真正依赖的（HTTPS/压缩/SQLite/原生调用/fork 子进程），
        // 将来升 Chaquopy 若把它们弄丢，这条会立刻红。
        for (String must : new String[]{"ssl", "sqlite3", "lzma", "bz2", "zlib",
                "ctypes", "socket", "asyncio", "distutils", "setuptools"}) {
            int i = out.indexOf("std_missing");
            String missing = i >= 0 ? out.substring(i) : out;
            org.junit.Assert.assertFalse("关键标准库模块不应缺失: " + must + " | " + missing,
                    missing.contains(must));
        }
        org.junit.Assert.assertTrue("应有 fork（multiprocessing 依赖）: " + out, out.contains("\"fork\": true"));
        org.junit.Assert.assertTrue("三方库应全部可导入: " + out, out.contains("\"third_missing\": []"));
    }
}
