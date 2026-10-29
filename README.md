# OZJFLASH — 安卓内置终端（Termux 上游 UI 版）

把 **Termux 上游整套 App**（UI + 终端内核 + 服务 + 设置 + 文件选择器）编进 OZJFLASH，
配合内嵌的 Termux bootstrap，装上就是一台能跑 `bash` / `apt` 的 Linux 终端，不需要先装 Termux、不需要 root。

当前版本 = 上游 `app` + `termux-shared` + `terminal-emulator` + `terminal-view` 四个模块原样 vendor，
只改了三件事：应用名/包名、bootstrap 解压后的路径改写、动态库搜索路径。

## 组成

| 组件 | 说明 |
|---|---|
| `app/` | 上游 `app` 模块：`TermuxActivity`（抽屉 + 会话标签 + 功能键栏 + 键盘）、`TermuxService`（前台服务持有会话）、设置页、`RunCommandService`、文件选择器 |
| `termux-shared/` | 上游 `termux-shared`：`TermuxConstants`、`TermuxShellEnvironment`、会话/命令/错误报告/文件工具、`local-socket` JNI |
| `terminal-emulator/` | 上游 VT 仿真（`com.termux.terminal.*`）+ `src/main/jni/termux.c`（PTY/fork/execvp/TIOCSWINSZ），**由 NDK 现场编译成 `libtermux.so`** |
| `terminal-view/` | 上游 `TermuxView`：渲染屏幕、软键盘 `InputConnection`、硬件键、双指缩放、文本选择复制 |
| `app/src/main/jniLibs/arm64-v8a/libtermux-bootstrap.so` | 29MB 的“伪 .so”：`.rodata` 内嵌完整 Termux bootstrap zip（3490 项，含 bash/apt/pkg），导出 `TermuxInstaller.getZip()` |
| `app/.../PrefixFixup.java` | 解压后把 bootstrap 里写死的 `/data/data/com.termux/...` 全量改成本 App 的真实路径，并删掉 `bin/login`（其内部硬编码 Termux 路径，会让登录 shell 起不来） |

## 关键设计点

- **targetSdk = 28（刻意）**：Android 10+ 对 `targetSdk >= 29` 的应用禁止 `execve` 应用私有目录里的文件。
  终端必须留在 28 才能执行解压出来的 bootstrap 二进制（Termux 官方也是这么做的）。
- **包名不是 `com.termux`，靠三处补丁对齐**：
  1. `TermuxConstants.TERMUX_PACKAGE_NAME` / `TERMUX_APP_NAME` → 决定 `files/usr`、`files/home` 等全部路径；
  2. `PrefixFixup`：解压后扫描 prefix 下所有文件（前 8KB 无 NUL 视为文本），替换旧 prefix 字符串；符号链接按目标重建（18 条绝对路径链接）；
  3. `TermuxShellEnvironment` 里把 `PATH` + `LD_LIBRARY_PATH=$PREFIX/lib` 显式写入子进程环境 —— bootstrap 里 ELF 的 `DT_RUNPATH` 仍指向 Termux，`LD_LIBRARY_PATH` 优先级高于 `RUNPATH`，能把动态库解析救回来。
- **删掉 `android:sharedUserId`**：上游声明 `sharedUserId="${TERMUX_PACKAGE_NAME}"`，换包名会导致覆盖安装失败，直接去掉（数据目录仍是 `/data/data/<applicationId>`）。
- **首次启动解压约 29MB**（几十秒，界面有进度），完成后秒开。

## 安装使用

1. 从 Release 下载 `OZJFLASH-debug.apk` 安装（arm64-v8a，Android 7.0+）。
   **若装过旧版（自研 UI 那版）请先卸载** —— 签名不同，直接覆盖会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
2. 首次打开等待 bootstrap 解压。
3. 点屏幕呼出键盘输入命令；左滑抽屉切会话、进设置；底部功能键栏可切换 `ESC/TAB/CTRL/ALT/方向键`。
4. 长按屏幕 = 文本选择/复制；`exit` 关闭会话，`+` 新开。

## 构建

GitHub Actions 自动构建：JDK 21 + Android SDK 37 + **NDK 27.0.12077973**（`libtermux.so` 与 `liblocal-socket.so` 都是现场编译）。

```sh
./gradlew assembleDebug   # 产物 app/build/outputs/apk/debug/app-debug.apk
```

签名用仓库里上游自带的 `app/testkey_untrusted.jks`（固定 key），所以后续版本可以直接覆盖安装。

## 已知限制

- 只支持 arm64-v8a（bootstrap 是 aarch64）。
- bootstrap 里的 `apt/dpkg` 配置已改路径，但个别二进制内部仍硬编码 Termux 路径，装包/升级可能失败；内置工具链（bash、coreutils、git、python 等）正常。
- 没有 `termux-api`（未打包 `com.termux.api` 插件）；`RUN_COMMAND` 权限、`com.termux.permission.RUN_COMMAND` 等跨应用接口仍按上游声明存在，但外部 App 用不到。
