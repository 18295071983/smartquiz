package com.oilquiz.app.ai.dsh;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * 最小 tar.gz 解包器（2026-11-19，方案 B 自带 DSH 运行时用）。
 *
 * <p>只依赖 JDK 的 GZIP + 手写 tar 头解析，不引入 commons-compress；符号链接/硬链接用
 * {@link Os#symlink}/{@link Os#link} 还原（Ubuntu rootfs 大量 usr-merge 链接，丢了会起不来）。</p>
 */
public final class DshTarGz {

    private DshTarGz() {
    }

    /** 解压 assets 里的 tar.gz 到 destRoot；entry 路径前再拼 prefix（可为空）。 */
    public static void extractAsset(Context c, String assetPath, File destRoot, String prefix)
            throws IOException {
        try (InputStream raw = c.getAssets().open(assetPath);
             InputStream in = new BufferedInputStream(new GZIPInputStream(raw), 1 << 16)) {
            extract(in, destRoot, prefix);
        }
    }

    private static void extract(InputStream in, File destRoot, String prefix) throws IOException {
        byte[] header = new byte[512];
        String longName = null;
        String longLink = null;
        String paxPath = null;
        String paxLink = null;
        while (true) {
            if (!readFully(in, header)) return;
            if (isZero(header)) return;              // 两个全零块 = 结束（这里一个就够）
            String name = readString(header, 0, 100);
            long size = parseOctal(header, 124, 12);
            int mode = (int) parseOctal(header, 100, 8);
            char type = (char) header[156];
            String link = readString(header, 157, 100);
            if (longName != null) { name = longName; longName = null; }
            if (longLink != null) { link = longLink; longLink = null; }
            if (paxPath != null) { name = paxPath; paxPath = null; }
            if (paxLink != null) { link = paxLink; paxLink = null; }

            long padded = (size + 511) / 512 * 512;
            if (type == 'L') {                        // GNU 长文件名
                byte[] b = new byte[(int) size];
                readFully(in, b);
                longName = new String(b, "UTF-8").replace("\0", "").trim();
                skipFully(in, padded - size);
                continue;
            }
            if (type == 'K') {
                byte[] b = new byte[(int) size];
                readFully(in, b);
                longLink = new String(b, "UTF-8").replace("\0", "").trim();
                skipFully(in, padded - size);
                continue;
            }
            if (type == 'x' || type == 'g') {         // pax 扩展头：解析 path= / linkpath=
                byte[] b = new byte[(int) size];
                readFully(in, b);
                String rec = new String(b, "UTF-8");
                for (String line : rec.split("\n")) {
                    int sp = line.indexOf(' ');
                    if (sp <= 0) continue;
                    String kv = line.substring(sp + 1);
                    if (kv.startsWith("path=")) paxPath = kv.substring(5);
                    else if (kv.startsWith("linkpath=")) paxLink = kv.substring(9);
                }
                skipFully(in, padded - size);
                continue;
            }

            String rel = normalize(name);
            if (rel.isEmpty() || rel.contains("..")) {
                skipFully(in, padded);
                continue;
            }
            File out = new File(destRoot, prefix.isEmpty() ? rel : prefix + "/" + rel);
            if (type == '5') {                        // dir
                if (out.exists() && !out.isDirectory()) deleteRecursive(out);
                out.mkdirs();
                chmod(out, mode);
                skipFully(in, padded);
            } else if (type == '2') {                 // symlink
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                deleteAny(out);
                try {
                    Os.symlink(link, out.getAbsolutePath());
                } catch (ErrnoException e) {
                    if (e.errno != OsConstants.EEXIST) throw new IOException(e);
                }
                skipFully(in, padded);
            } else if (type == '1') {                 // hardlink
                File target = new File(destRoot, prefix.isEmpty() ? normalize(link)
                        : prefix + "/" + normalize(link));
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                deleteAny(out);
                try {
                    Os.link(target.getAbsolutePath(), out.getAbsolutePath());
                } catch (ErrnoException e) {
                    copyFile(target, out);            // FUSE/权限不支持硬链接时退化为复制
                }
                skipFully(in, padded);
            } else if (type == '0' || type == 0 || type == '7') {  // 普通文件
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                // 同名目录先清掉（npm 树里存在 目录/软链 同名交替的条目）
                if (out.isDirectory()) deleteRecursive(out);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    copyN(in, fos, size);
                }
                chmod(out, mode);
                skipFully(in, padded - size);
            } else {
                skipFully(in, padded);
            }
        }
    }

    private static String normalize(String name) {
        String n = name;
        while (n.startsWith("./")) n = n.substring(2);
        if (n.equals(".") || n.equals("/")) return "";
        while (n.startsWith("/")) n = n.substring(1);
        while (n.endsWith("/")) n = n.substring(0, n.length() - 1);
        return n;
    }

    private static void chmod(File f, int mode) {
        try {
            int perm = mode & 0777;
            if (perm == 0) perm = 0644;
            Os.chmod(f.getAbsolutePath(), perm);
        } catch (Throwable ignored) {
        }
    }

    private static void deleteQuietly(File f) {
        try {
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteAny(File f) {
        try {
            if (f.isDirectory()) deleteRecursive(f);
            else deleteQuietly(f);
        } catch (Throwable ignored) {
        }
    }

    private static void deleteRecursive(File f) {
        try {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void copyN(InputStream in, FileOutputStream out, long n) throws IOException {
        byte[] buf = new byte[1 << 16];
        long left = n;
        while (left > 0) {
            int read = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (read < 0) throw new IOException("tar 提前结束");
            out.write(buf, 0, read);
            left -= read;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                skipped = 1;
            }
            left -= skipped;
        }
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) return off > 0 && off == buf.length;
            off += n;
        }
        return true;
    }

    private static boolean isZero(byte[] b) {
        for (byte v : b) if (v != 0) return false;
        return true;
    }

    private static String readString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] b, int off, int len) {
        long v = 0;
        int i = off;
        while (i < off + len && (b[i] == ' ' || b[i] == 0)) i++;
        for (; i < off + len; i++) {
            byte c = b[i];
            if (c < '0' || c > '7') break;
            v = (v << 3) + (c - '0');
        }
        return v;
    }
}
