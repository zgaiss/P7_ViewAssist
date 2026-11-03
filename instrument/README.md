# 仪表原生 VPU + 小窗投屏：实验集成

## 当前结论与边界

**源码集成 + 离线验证已实现；不是已通过实车验收的功能。默认关闭，无自动部署/开机启动。**

这版复用本地独立实验中真正成功的原车路径：`libvpu.so.4` 5.4.16 → `/dev/mxc_vpu` → firmware 2.3.10，以及 fb1 的 560×315 RGB565 前景小窗。没有改成 FFmpeg/V4L2，没有使用另一个仓库的 zlib 全屏 RGB565 协议。

既有证据只证明两项分开的实验：320×240 Baseline 的 30 帧 VPU 输出与软件逐帧 MD5 一致；560×315 已预解码图像的 10 秒/300 次前景提交成功。**不证明这版 1280×720 增量码流、连续跨 GOP、持续解码→显示、CPU/延迟或物理刷新已实测通过。**

> **尚未消除的行车阻断风险：** BSP 的 FG disable 路径会先把位置改为 `(0,0)`，再关闭并恢复位置；无法排除短暂遮挡左上原车信息。守护进程不能消除这个内核行为，也无法保证在自身死亡、ioctl 卡死、整机掉电时恢复。因此只允许将此代码作为后续另行授权的驻车工程实验，不得宣称行车安全或直接启用路测。

## 这次接入的链路

```text
Camera2 原始1280×720（不是 TextureView 变换结果）
 → MediaCodec AVC Baseline / 无B / 15fps / 2Mbps
 → P7IC0001 TCP：令牌 + 方向 + 显式crop + 完整AU
 → 原生H.264预检 → 原车VPU增量ring/rollback模式
 → 同步取得I420 → 显式crop/等比fit/黑边 → RGB565LE
 → 有限时完整帧pipe → 独立setsid显示守护进程
 → fb1 560×315（不写fb0，不改alpha/key）
```

- 左窗：`(80,224)`；右窗：`(1280,224)`，基于 1920×720 仪表坐标。一次一个 side，切换使用新连接，仪表端串行处理。
- 每个 RGB 完整帧 `560*315*2=352800` 字节；CPU 拷贝后才释放 VPU display flag，不将 VPU DMA buffer 交给显示进程。
- 仪表前景只在收到完整首帧且备份/基线检查成功后开启。后续帧读取有 1000ms 完整帧期限，写 pipe 有 350ms 期限。父退出/EOF/没有新完整帧会触发守护清理。不是物理屏幕刷新同步保证，可能撕裂。
- 主进程只在硬件 idle + OutputInfo 配对后访问输出。busy timeout/所有权不确定时保留可能仍在 DMA 的内存，不尝试 reset，不重试新 session。SDK 自身内部可能 reset、busywait；wrapper 不能禁止或中断它。显示 guardian 不依赖 VPU 调用返回。
- 显示层使用合作式 owner lock、只读原车 BG/DP 状态、独占创建备份、ARM32 ABI 断言、变更前后检查。只接受此前实验的 fb1 空闲 baseline，不对未知仪表布局降级尝试。
- 关闭后核对原几何、原像素内容、blank、DP、BG 参数；DMA 地址/容量及 `var.activate` 可能不同，不能写成“逐字节恢复全部状态”。备份留在仪表私有 session 目录，不自动删除。

## Android 端

主界面新增“仪表硬解投屏（实验设置，默认关闭）”：

1. 输入仪表 IPv4 和端口（默认 `172.20.1.40:9000`）。
2. 填写与接收器一致的 64 hex 字符随机令牌（32 bytes），这是明文共享认证，**不是加密或抗重放**；只应使用隔离的可信车内链路。不要把令牌、签名密钥写入仓库或日志。
3. 左右分别设置原始视频坐标的 `x,y,w,h`，范围在 1280×720 内。默认 `0,0,1280,720` 是全帧调试画面。
4. 明确勾选风险确认后启用，下一次新建浮窗相机会话使用；取消启用会结束网络投屏并尝试重建 local-only 预览。

**必须先校准：** camera0 可能是 2×2 四路鱼眼拼接。不能猜测某象限等于左/右，也不能以为会自动照搬浮窗的自由平移/缩放。左右裁切由显式设置决定；默认全帧不是已校准的侧后方画面。

- 单边 LEFT/RIGHT 会话才投屏；MANUAL 不自动归类侧别。原有 R 档/双闪/手动关闭等门控保留。
- `AVCProfileBaseline`、`max-bframes=0` 请求不代表每个设备编码器都会服从；仪表预检拒绝 High/B/interlace 等不支持码流，实际编码器与多 Surface 组合仍需验证。
- 持有 3 个 AU 的有界队列；溢出清掉 GOP、请求新 IDR，不随意丢一个 P 后继续发后续 P。过旧 AU/写超时结束连接。
- 断网后仍 dequeue 编码器，防止 encoder Surface 反压共享 Camera2。**没有自动重连**，需收窗重开/切换方向重新开始。
- 切换/停用先逻辑 retire；编码器在引用它的 CameraDevice `onClosed` 后才释放。设备/会话回调 generation 检查拒绝迟到会话；编码错误或新增 Surface 组合失败尝试一次 local-only 回退。此为源码防护，不是 HAL 压测通过的声明。

## 协议 P7IC0001

不兼容原仓库仅 `u32 length + Annex-B` 的老接收端，也不兼容 instrument-projection 的 zlib RGB565 协议。全部数值为 big-endian。

### Hello（60 bytes）

| offset | 长度 | 内容 |
|---|---:|---|
| 0 | 8 | ASCII `P7IC0001` |
| 8 | 32 | 二进制共享令牌（不是64字节hex文本） |
| 40 | 4 | side：1=左，2=右 |
| 44 | 16 | crop x,y,w,h，各u32 |

接收器校验成功回 1 byte `0x01`，失败直接断开。ACK 只是认证/协议接受，**不是 VPU/显示硬件初始化成功**。

### 视频包

`u32 type=1 | u32 AU_length | u64 pts_us | AU bytes`

- 1..1048576 bytes，完整单 picture Annex-B AU，PTS 非负且同一 session 严格递增。
- 首 AU 必须包含 SPS/PPS 和 IDR；随后允许同一 SPS/PPS 重复，动态改变序列直接拒绝。禁止 B、隔行、非 Baseline/非8bit420、超过1280×720、单AU多 picture。
- Java 严格归一化完整4-byte AVCC或Annex-B，不以起始3字节猜格式；AVCC NAL 长度256/300/511等有回归。未知非控制输出不静默当作空帧丢弃。
- 原车 SDK 5.4.16 的 `bitstreamMode=1` 经源码确认是 **rollback**，不是从其他 VPU 版本搬来的 PIC_END 枚举。每 AU 附合法 AUD 边界，不使用 fake EOS。持续增量行为仍待设备验证。末尾不 drain，最后少量延迟帧可能丢弃。

## 离线构建和测试

脚本是本机验证入口，无 ADB、RPC 或设备操作：

```bash
# 默认构建硬件调用门关闭的 ARMEL 接收器，并运行纯本机测试
python3 instrument/build_local.py

# 显式确认外部ABI，构建实验硬件调用版本 + 未签名APK（仍不部署）
python3 instrument/build_local.py --native-abi-confirmed --android

# 模拟decoder/display的本机进程测试，绝不调用真实设备
python3 instrument/tests/test_receiver_offline.py
```

参数 `--experiment`、`--ndk-bin`、`--java-home`、`--android-tools`、`--android-jar` 可覆盖本地路径。首次完整构建需要：

- ARMv7 **ARMEL soft-float** glibc 头/CRT，不是 Android Bionic、不是 armhf。
- 原车取回库与 MANIFEST 的 SHA256 完整性核对；runtime glibc2.19，库的 `2.13` 文件名不是运行时版本结论。
- 外部官方 VPU SDK **5.4.16** 头文件（已核对 commit `a51ae713b266b63c44c046031d51e87706b5c1f2`），未复制进仓库：该来源含专有/保密声明，使用和再分发权限须另行核实。
- 本机默认外部实验目录是兄弟工程 `ic-vdec-probe-20261102/vendor/`，原车库/SDK/固件均不随本项目提交或分发。
- NDK clang 只作为交叉编译器，产物 ELF32 ARM，interpreter `/lib/ld-linux.so.3`、依赖 `libvpu.so.4` 和 glibc，未链接 FFmpeg/Android 运行库。
- Android 为手工 aapt2 → Java11 → D8 → unsigned APK。使用官方平台存根 android.jar（本机37.0）编译；manifest min29/target34 不变。**APK 未签名、未 zipalign，不可直接当安装交付使用**；没有擅自生成替代原作者的签名或覆盖现有 App。

输出使用新的 `instrument/build/local-*/`，含命令日志、源文件hash、ELF依赖、接收器、未签名APK及 manifest，不覆盖之前结果；该目录已加入 gitignore。

测试范围：Java协议及AVCC歧义、Java→C握手兼容、C令牌/越界/读写deadline、纯C H.264预检（增量AU/畸形/fuzz/真实离线Baseline）、RGB565颜色/stride/crop/黑边/边界，以及 host-stub EOF/worker阻塞/父死亡恢复路径。Host UBSan 通过；本机 ASan 在进入 main 前初始化卡住，**未记为ASan通过**。模拟恢复不证明真 fb ioctl 恢复。

## 接收器操作契约（只描述，未执行）

没有启停脚本写入车机，没有自启单元。将来只有在用户单独授权驻车测试后才可准备部署。接收器需显式绑定 IPv4，不接受 `0.0.0.0`，端口1024..65535，运行总时限1..600秒；不因连接反复自动延长。

原生二进制的参数顺序：

```text
p7-ic-receiver-armel --parked-experimental-ack BIND_IPV4 PORT TOKEN_FILE SECONDS --accept-fg-disable-origin-risk
```

令牌文件为64hex文本（可尾随一个换行），属于当前运行用户、普通非符号链接、权限0600。仪表状态根固定 `/tmp/p7-viewassist`，须当前用户0700；进程创建私有 session 并保留原始备份。硬件调用编译门默认关闭；即使开启也必须提供两项运行时风险确认参数。

- 正常停止使用 SIGTERM/SIGINT，等待 guardian 日志 `RESTORE_OK` 和父进程成功退出，不要将“父已经不在”当作已恢复。
- decoder open/feed/close 失败，或 guardian 恢复失败/5秒内未确认：整个服务退出、不接受后续连接。SDK 自身若卡死，父不一定能响应信号，但 guardian 仍独立按帧超时恢复；若 guardian 自身也卡住则不保证。
- 不可对可能仍在 DMA 的 VPU 进程随意强杀/手工 free/reset；不可盲目杀掉正在恢复的 guardian。Host 模拟测试中的强杀仅针对完全不触及设备的 stub，不是仪表处置方案。

## 还必须做的驻车验收（本次未做）

1. 单独审计/解决 FG disable `(0,0)` 瞬态；未解决前不能转行车使用。
2. 实际车机编码器输出检查（Baseline、无B、完整AU、15fps实际速率）、左右原始源crop标定、多Surface能力及 local-only fallback。
3. 仪表 1280×720 持续增量跨多个GOP，码流断流/损坏/重复IDR/方向快速切换，逐帧与软件对照。
4. VPU→转换→pipe→fb实际耗时、CPU/内存、温度、物理刷新/撕裂、长时运行，不拿预解码缓存播放代表实时硬解性能。
5. 断网、编码错误、解码阻塞、父退出、正常SIGTERM等故障下的独立恢复；核对FG几何/原像素/blank/DP与BG保持。guardian死亡/ioctl卡死须另设故障处置，不能从host stub推断硬件保证。

实现细节另见 [decoder说明](native/DECODER_NOTES.md)。
