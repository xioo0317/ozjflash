package com.termux.app;

import android.system.ErrnoException;
import android.system.Os;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * bootstrap 是按 Termux 官方包名(com.termux)编译的，本 App 包名不同，所以解压后必须做一次修补：
 *
 * 1. 脚本 shebang、etc/apt/*.conf、dpkg 配置等文本文件里写死的
 *    /data/data/com.termux/files/usr 全部替换成本 App 的真实 prefix。
 * 2. 少数符号链接的目标是绝对路径(如 share/pacman/keyrings/*.gpg)，重建成本 App 路径。
 * 3. 删掉 bin/login —— 它内部写死了 Termux 路径，上游 TermuxSession 会优先挑 login 当登录 shell，
 *    删掉之后会回退到 bin/bash。
 *
 * ELF 二进制里也写死了路径，但长度不同无法原地改写，它们靠 LD_LIBRARY_PATH 解决(见 TermuxShellEnvironment)。
 */
public class PrefixFixup {

    private static final String LOG_TAG = "PrefixFixup";

    private static final String OLD_PREFIX = "/data/data/com.termux/files/usr";
    private static final String OLD_HOME = "/data/data/com.termux/files/home";

    private static final long MAX_TEXT_FILE_SIZE = 4L * 1024 * 1024;
    private static final int BINARY_PROBE_SIZE = 8192;

    public static void apply(File prefixDir) {
        byte[] oldPrefix = OLD_PREFIX.getBytes();
        byte[] newPrefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH.getBytes();
        byte[] oldHome = OLD_HOME.getBytes();
        byte[] newHome = TermuxConstants.TERMUX_HOME_DIR_PATH.getBytes();

        long start = System.currentTimeMillis();
        walk(prefixDir, oldPrefix, newPrefix, oldHome, newHome);

        // login 内部写死了 Termux 路径，删掉让上游回退到 bash
        File login = new File(prefixDir, "bin/login");
        if (login.exists() && !login.delete())
            Logger.logWarn(LOG_TAG, "Failed to delete \"" + login.getAbsolutePath() + "\"");

        Logger.logInfo(LOG_TAG, "Prefix fixup finished in " + (System.currentTimeMillis() - start) + "ms");
    }

    private static void walk(File dir, byte[] oldPrefix, byte[] newPrefix, byte[] oldHome, byte[] newHome) {
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File child : children) {
            String path = child.getAbsolutePath();

            // 符号链接：readlink 成功说明是链接，不跟随、不递归
            String linkTarget = null;
            try {
                linkTarget = Os.readlink(path);
            } catch (ErrnoException e) {
                // 不是符号链接
            }
            if (linkTarget != null) {
                if (linkTarget.startsWith(OLD_PREFIX)) {
                    String newTarget = TermuxConstants.TERMUX_PREFIX_DIR_PATH + linkTarget.substring(OLD_PREFIX.length());
                    try {
                        Os.remove(path);
                        Os.symlink(newTarget, path);
                    } catch (Exception e) {
                        Logger.logWarn(LOG_TAG, "Failed to recreate symlink \"" + path + "\": " + e.getMessage());
                    }
                }
                continue;
            }

            if (child.isDirectory()) {
                walk(child, oldPrefix, newPrefix, oldHome, newHome);
                continue;
            }

            if (!child.isFile() || child.length() == 0 || child.length() > MAX_TEXT_FILE_SIZE) continue;

            byte[] content;
            try {
                content = readFile(child);
            } catch (IOException e) {
                continue;
            }
            if (content == null || content.length == 0) continue;

            int probeLength = Math.min(content.length, BINARY_PROBE_SIZE);
            boolean binary = false;
            for (int i = 0; i < probeLength; i++) {
                if (content[i] == 0) {
                    binary = true;
                    break;
                }
            }
            if (binary) continue;

            if (!contains(content, oldPrefix) && !contains(content, oldHome)) continue;

            byte[] replaced = replace(content, oldPrefix, newPrefix);
            replaced = replace(replaced, oldHome, newHome);

            try (FileOutputStream out = new FileOutputStream(child)) {
                out.write(replaced);
            } catch (IOException e) {
                Logger.logWarn(LOG_TAG, "Failed to patch \"" + path + "\": " + e.getMessage());
            }
        }
    }

    private static byte[] readFile(File file) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(file.length(), 65536));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) return false;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static byte[] replace(byte[] src, byte[] from, byte[] to) {
        if (from.length == 0) return src;
        ByteArrayOutputStream out = new ByteArrayOutputStream(src.length + 1024);
        int i = 0;
        while (i < src.length) {
            boolean matched = i + from.length <= src.length;
            if (matched) {
                for (int j = 0; j < from.length; j++) {
                    if (src[i + j] != from[j]) {
                        matched = false;
                        break;
                    }
                }
            }
            if (matched) {
                out.write(to, 0, to.length);
                i += from.length;
            } else {
                out.write(src[i]);
                i++;
            }
        }
        return out.toByteArray();
    }
}
