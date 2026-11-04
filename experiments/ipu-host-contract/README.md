# P7 仪表 VPU→IPU 视频路径候选

2026-11-04。**这是可执行的主机契约候选，不是已能在仪表播放的播放器。** 未连接车机/仪表，未打开设备、运行真实 VPU/IPU、改显示模式或部署。目标入口 `ic_ipu_open_target()` 无条件返回 `ENOTSUP`；没有真实 IPU 后端。测试后端仅在 `IC_IPU_HOST_CONTRACT_TEST` 编译下可用，生成标识图案，不解码 H.264，也不做真实色彩转换。

## 为什么值得走这条路

现有工程已经传 H.264 并调用原车 VPU 硬解；CPU RGB565 转换是硬解后的显示适配，不是网络在发一张张 RGB 图片。选择 RGB565 是复用已实验的 fb1 小窗，以及完整帧 pipe 与独立 guardian 的故障隔离。它不是 i.MX6 只能显示 RGB 的硬件限制。

NXP 原厂说明将 VPU 解码、IPU IC 缩放/色转、DP 合成区分开，DP 可以接 YUV；直接 bypass 路径不做缩放。因此可优先探索“VPU→IPU硬件缩放/色转→现有小窗”，之后才考虑 YUV overlay/完整零拷贝。

**不宣称 CPU 转换已确定是瓶颈。** 需要目标上的解码、staging、IPU、pipe、显示校验与扫描时间，才能比较收益。这个原型增加了一次完整 I420 staging，避免借用 decoder DMA，但并不是零拷贝，且未测性能。

## 本候选实际做了什么

`src/ic_ipu_candidate.c` 提供与现有 `ic_frame_callback` 匹配的回调实现：

1. 校验输入几何与布局，逐行把借用的 VPU I420 planes 复制到自己持有的连续 I420 staging。
2. 形成独立于 kernel ABI 的任务描述，调用注入后端的 `check`，拒绝任何未经预期的参数/地址调整。
3. 调用明确的输入同步、queue、输出同步；只有确认全部完成后才读取 RGB 输出。
4. 把独立 RGB 输出逐行装入 560×315 RGB565 黑底帧，再交完整帧发布回调。这里不含 CPU YUV→RGB 逐像素换算。
5. queue 返回结果未知或输出同步失败时，禁止再次提交并保留对象及两个缓冲，不假装已经停止 DMA，不 reset，不尝试复用/释放。

现有 guardian 的打开/备份/恢复/读帧逻辑**没有修改**。独立 IPU 工作器输出完整 RGB 后仍可使用现有 350ms pipe 写期限；guardian 保持1000ms完整帧读期限。不要把阻塞的 IPU ioctl 放进 guardian。此处回调是同步的，不是新增的异步队列，也不会把 IPU 指针传给 guardian。

`tests/test_pipe_bridge.c` 实际链接了拷贝且记录 SHA256 的生产 `ic_protocol.c`，通过真实 Linux pipe 把 352800 字节发给独立子进程校验。它没有调用 `ic_display_frame`，更没有实际屏幕扫描。先前 guardian 的物理安全问题不因这个测试而消失。

## 刻意收窄的几何

参考 BSP 会把普通格式 crop 宽高向下对齐到8。原560×315不能原样承诺。候选只接受8像素对齐的输入尺寸/裁切，且保留精确源宽高比；找不到8像素对齐的等比例目标时拒绝。

- 1280×720全幅：IPU目标512×288，装入560×315时左24、上13。
- 320×240全幅：IPU目标416×312，左72、上1。
- 输出缓冲本身只有对齐后的有效矩形；CPU只做RGB行复制及黑边填充。
- 因此本候选显示有效区小于现有软件转换，不应无提示替换正式显示。尚未决定真实BSP下的最终padding/裁切方案。

## 复现及当前结果

运行 `sh reproduce.sh`，需要本环境已有 C 编译器、nm、POSIX pipe/fork。无需网络、设备、SDK或新增软件。

- 默认构建通过，符号检查不含测试 opener，也不引用 ioctl/open/mmap/VPU/CPU色转函数。
- warnings-as-errors + UBSan通过；生产协议原有 array/pointer 声明诊断使用已记录的 `-Wno-array-parameter`，未改生产协议逻辑。
- 测试覆盖对齐/边界拒绝、16:9与4:3装箱、目标入口拒绝、不同stride的I420快照、CHECK拒绝/回写、输入/输出同步失败、明确idle失败、未知完成隔离/禁止重试、发布失败和第二次分配失败。
- fake queue 主动覆盖借用源，再检查 owned staging仍正确，证明生命周期脱离；输出是合成0x5a图案，**不证明色彩准确或硬件处理完成**。
- 未知完成场景在子进程中验证不free、不重试后 `_exit`，刻意保留的内存不进行退出时泄漏评判。这不是目标内核会安全回收DMA的证据。
- 独立审查发现并修复了输入源与staging别名导致的memcpy重叠，详见独立审查报告。

## 后端与调用方契约

- 单线程、单对象串行调用，不能在backend/publish回调中重入或改变allocation descriptor。allocate必须交付独立且唯一所有权的完整buffer；失败不开始DMA。
- 输入帧API没有backing allocation长度，因此可读内存范围仍依赖已验证decoder契约；整数边界与别名检查不等于能验证任意指针。
- “禁止重开”是session/process调用方义务。本模块会锁住失败对象，但host测试opener不会全局禁止创建另一个对象；不能把它说成进程级硬件熔断。
- queue必须区分成功完成、已确认idle的失败、完成状态未知；不能把一个通用errno或timeout映射为idle。真实后端的deadline、阻塞隔离与进程退出处置仍未实现。

## 真正接入前的阻塞

1. **目标 BSP 完整 ABI**：现有参考是 NXP `9f14414b...`，不是目标 `3.0.35-00054-g1733cc4` 已确认源码。136字节 task和 ioctl数值吻合不等于每个字段、行为、cache语义一致。
2. **DMA协议**：需核实目标分配/mmap/同步与地址范围。当前 backend descriptor不是kernel结构，不能cast后直接ioctl。VPU planes各自物理地址不能未经证明冒充IPU所需连续I420。
3. **完成/退出**：参考 `IPU_QUEUE_TASK` 阻塞等待，不是异步fence；超时并不自动证明DMA已停。引用驱动的file release会释放该fd的分配，故用户态“不free”也不足以证明进程退出安全。真实adapter必须先证实停止和退出行为。
4. **颜色/缩放**：验证BT.601 limited-range、色序、物理stride、crop、精确比例以及最后几行。主机marker测试不覆盖这些。
5. **资源共用**：IPU通道、DP/FG与原厂仪表活动是否冲突；保持原厂重要信息可见。不要以YUV支持就推断任意fb1配置可用。
6. **物理显示**：继续保留已有FG disable可能先移到(0,0)的风险、ioctl卡住/guardian死亡、撕裂与真实扫描限制。本候选不改变这些风险。
7. **目标构建**：没有ARM工具链/sysroot/官方SDK的本次构建证据。主机通过不是仪表可执行验收。

下一步仅应在获得准确BSP/已有离线kernel与库证据后写真实adapter，先离屏输出校验，再在另行授权的隔离驻车/台架环境做小窗试验；不要直接将IPU写入当前扫描buffer，不绕过现有默认关闭与恢复门控。

## 证据口径

现有工程README记载的是两项分开的历史实验：“320×240 Baseline **30帧** VPU输出与软件逐帧MD5一致”，以及“预解码图像10秒/300次前景提交”。这里没有新的原始设备日志复核；不能称为VPU实测30fps或持续硬解直显通过。`hardware_fixture/metadata.json` 中30/1只是样本帧率，不能作为实测帧率。

## 原厂来源

- [NXP i.MX6图像处理培训](https://community.nxp.com/pwmxy87654/attachments/pwmxy87654/nxp-training/91/1/FTF-CON-F0119.pdf)，页8、49–51、102–104：VPU/IC/DP职责、YUV/RGB、缩放和bypass限制。已通过web读取。
- [NXP固定参考ipu_device.c](https://github.com/nxp-imx/linux-imx/blob/9f14414b0250131300c2a9a59d349632a099dce8/drivers/mxc/ipu3/ipu_device.c)：`set_crop`、`ipu_queue_task`、`mxc_ipu_ioctl`、`mxc_ipu_release`。本次通过GitHub connector核对，blob `384feeb60db1c09bdb3c058089cbf25fdf089f21`。
- [NXP固定参考mxc_ipuv3_fb.c](https://github.com/nxp-imx/linux-imx/blob/9f14414b0250131300c2a9a59d349632a099dce8/drivers/video/mxc/mxc_ipuv3_fb.c)：`var.nonstd`、I420/NV12 buffer设置及pan路径。本次核对blob `4c98d51f13b0bc13045031a04e6fff278bb63f09`。仅参考BSP能力，不是目标功能实测。
