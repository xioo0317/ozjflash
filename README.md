# OZJFLASH — 安卓内置终端

用 `libtermux.so`（Termux 官方 PTY JNI 库）+ 内嵌 Termux bootstrap 实现的**独立终端 App**，不需要装 Termux，装上就能用 bash。

## 它做了什么

| 组件 | 说明 |
|---|---|
| `libtermux.so` | Termux 的 PTY 子进程库（`com.termux.terminal.JNI`），负责 fork/exec、pty 分配、窗口尺寸、等待退出 |
| `libtermux-bootstrap.so` | 29MB 的“伪 .so”：`.rodata` 里内嵌完整 Termux bootstrap zip，导出 `TermuxInstaller.getZip()` 返回原始字节 |
| `term/Bootstrap.kt` | 首次启动把 zip 解到 `filesDir/usr`，按 `SYMLINKS.txt`（`target←link` 格式）重建 1146 个符号链接，给 ELF/bin 目录补执行位 |
| `term/TerminalSession.kt` | `JNI.createSubprocess` 起 `bash -l`，主端 fd 包成 `ParcelFileDescriptor` 读写；读线程增量 UTF-8 解码（跨包不切断多字节） |
| `term/TerminalScreen.kt` | 精简 VT 模型：维护行×列文本+光标，处理 CR/LF/BS/TAB、CSI（A/B/C/D/H/f/J/K/P/X/@/L/M/S/T/G/d）、OSC 吞掉、ESC 7/8 存光标；SGR 颜色忽略 |
| `MainActivity.kt` | Compose 界面：输出区（等宽、自动滚底）+ 输入行 + 功能键（ESC/TAB/^C/^D/^Z/^L/方向键/清屏/粘贴）；窗口尺寸变化自动 `TIOCSWINSZ` |

## 关键设计点

- **targetSdk = 28（刻意）**：Android 10+ 对 `targetSdk>=29` 的应用禁止 `execve` 私有目录文件，终端类应用必须留在 28 才能执行解压出来的 bootstrap 二进制（Termux 同样如此）。
- **不依赖 `/data/data/com.termux`**：bootstrap 二进制里 RUNPATH 写死了 Termux 路径，用 `LD_LIBRARY_PATH=$PREFIX/lib` 覆盖（优先于 RUNPATH）解决；terminfo 用 `TERMINFO` 指到我们自己的目录。
- **包名任意**：环境在应用私有目录 `files/usr`，不冲突、不需要 root。
- 首次启动解压约 29MB，进度条显示；之后秒开。

## 安装使用

1. 从 Release 下载 `OZJFLASH-debug.apk` 安装（arm64-v8a，Android 7.0+）。
2. 首次打开等进度条走完（解压 Termux 环境）。
3. 输入命令回车即可，例如 `ls`、`pwd`、`echo hi`、`cat /proc/cpuinfo`。
4. 方向键 / ^C / ^D / TAB 补全都可用；`pkg`/`apt` 可跑但受权限限制（非 root、私有目录），核心 busybox 工具链完整。

## 构建

GitHub Actions 自动构建（JDK 21 + Android SDK 37 + NDK 无关，.so 直接打进 jniLibs）：

```sh
./gradlew assembleDebug   # 产物 app/build/outputs/apk/debug/app-debug.apk
```

本地需要 Android SDK（platform 37）。`libtermux*.so` 已放在 `app/src/main/jniLibs/arm64-v8a/`。

## 已知限制

- 只支持 arm64-v8a（两个 .so 都是 aarch64）。
- 颜色/光标形状不渲染（SGR 忽略），全屏 TUI（vim/htop）能用但显示是纯文本，光标定位依赖 CSI 支持度。
- `apt install` 需要网络与可写 prefix，能装但部分包脚本假定 Termux 包名，可能失败。
- targetSdk 28 意味着没有 Android 13+ 的运行时通知权限等新特性（本项目用不到）。
