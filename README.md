# OZJFLASH — 安卓内置终端

把 **Termux 上游终端内核**（`terminal-emulator` + `terminal-view`）直接编进 App，配合内嵌的 Termux bootstrap，
装上就是一台能跑 `bash` 的 Linux 终端，不需要先装 Termux、不需要 root。

上一版自研的精简 VT 渲染 + 手写会话层在真机打开即闪退，已整体废弃；现在终端显示、按键、PTY 全部走 Termux 官方代码。

## 组成

| 组件 | 说明 |
|---|---|
| `terminal-emulator/` | 上游 `terminal-emulator` 模块（`com.termux.terminal.*`）：完整 VT100/xterm 仿真、颜色、SGR、滚屏、转录、Sixel 解析 |
| `terminal-emulator/src/main/jni/termux.c` | PTY 与子进程管理（`openpty`/`fork`/`execvp`/`TIOCSWINSZ`），**由 NDK 现场编译成 `libtermux.so`**，不再依赖预编译 .so |
| `terminal-view/` | 上游 `terminal-view` 模块（`com.termux.view.TerminalView`）：把仿真屏幕画出来，处理软键盘 `InputConnection`、硬件键、滑动手势缩放、文本选择复制 |
| `libtermux-bootstrap.so` | 29MB 的“伪 .so”：`.rodata` 内嵌完整 Termux bootstrap zip（3490 项，含 bash/apt/pkg），导出 `TermuxInstaller.getZip()` |
| `app/.../term/TermuxBootstrap.kt` | 首次启动解压 bootstrap 到 `filesDir/usr`：按 `SYMLINKS.txt`（`target←link`）重建 1146 个符号链接、补执行位、**把脚本/配置里写死的 `/data/data/com.termux/files/usr` 全量改成本 App 的真实 prefix** |
| `app/.../MainActivity.kt` | 极薄的宿主层：解压环境 → `TerminalSession` 起 `bash -l` → 挂到 `TerminalView`，加一排功能键（ESC/TAB/CTRL/ALT/方向/^C/^D/^L/^Z/PASTE） |

## 关键设计点

- **targetSdk = 28（刻意）**：Android 10+ 对 `targetSdk >= 29` 的应用禁止 `execve` 应用私有目录里的文件。
  终端必须留在 28 才能执行解压出来的 bootstrap 二进制（Termux 官方也是这么做的）。
- **包名随意，不依赖 `/data/data/com.termux`**：
  - 动态库：bootstrap 里 ELF 的 `DT_RUNPATH` 写死 Termux 路径，用 `LD_LIBRARY_PATH=$PREFIX/lib` 覆盖（`LD_LIBRARY_PATH` 优先于 `RUNPATH`）。
  - 脚本/配置：解压后扫描文本文件（前 8KB 无 NUL 即视为文本），把旧 prefix 字符串替换成真实路径 —— shebang、`etc/apt/*.conf` 都靠这一步才可用。
  - terminfo：`TERMINFO=$PREFIX/share/terminfo`。
- **首次启动解压约 29MB**（几十秒），完成写 `.ozj-ready` 标记，之后秒开。
- **出错不闪退**：解压失败、找不到 bash、会话启动异常都会把异常栈直接打印在界面上，可长按复制。

## 安装使用

1. 从 Release 下载 `OZJFLASH-debug.apk` 安装（arm64-v8a，Android 7.0+）。
2. 首次打开等环境解压完成，出现 `bash 已就绪`。
3. 点屏幕呼出键盘，输入 `ls`、`pwd`、`echo hi`、`uname -a` 等。
4. 双指缩放可调字号；底部功能键补上手机键盘没有的 ESC/TAB/CTRL/ALT/方向键。
5. `exit` 退出会话，右上角 `↻` 重开。

## 构建

GitHub Actions 自动构建：JDK 21 + Android SDK 37 + **NDK 27.0.12077973**（`libtermux.so` 由 `termux.c` 编译）。

```sh
./gradlew assembleDebug   # 产物 app/build/outputs/apk/debug/app-debug.apk
```

本地构建需要 Android SDK platform 37 与 NDK 27；`libtermux-bootstrap.so` 已放在 `app/src/main/jniLibs/arm64-v8a/`。

## 已知限制

- 只支持 arm64-v8a（bootstrap 是 aarch64）。
- bootstrap 里的 `apt/dpkg` 配置已改路径，但部分二进制内部仍硬编码 Termux 路径，个别包安装/升级可能失败；基础工具链（bash、coreutils、git、python 等已内置的）正常。
- 没有实现 Termux 的 `termux-api`、会话多开、`extra-keys` 配置文件等外围功能。
