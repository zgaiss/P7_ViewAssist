# 相机与 GL 中转生命周期修复说明

本改动以 v1.70 / a07f726 为基线。它修复源码中可确认的生命周期缺口，
不是对某次现场黑屏根因的证明，也没有完成目标车机的驱动或长时验证。
应先以 draft PR 审查，完成真实 Android 编译和隔离台架验证后再决定是否合并或发布。

## 设计边界

- Camera2 回调绑定打开请求、设备和会话的身份；停止或超时后到达的回调只能处理自身资源。
- `setRepeatingRequest` 正常返回后才提交有效会话与目标签名。配置成功不表示画面已经显示。
- 进程内使用一个相机 owner 线程，未知 open/close 仍占用预约，不能用 timeout 当作关闭确认。
- 相机会话持有 relay 输入 lease，直到匹配会话或设备关闭确认才释放。
- 输出在自己的线程初始化、绘制和收尾。初始化与正常退休不再阻塞 input actor 等待输出。
- `eglSwapBuffers` 返回 false 时立即读取 EGL error，不刷新成功时间。旧 output 的统计不写入新同 key output。
- 进程资源上限为 8 个输出 bundle、2 个输入 root/share group。初始化、运行、退休和隔离资源都计数；
  只有 native 清理确认且线程退出被观察后才减账。上限约束分配数量，不是驱动内存字节的硬上限。
- 同一 SurfaceTexture/借用 Surface 的旧生产者未完成退休时，不重新连接另一个生产者。
- TextureView destroyed 返回 false 后，应用才取得最终释放责任；仅 detach 不等于取得纹理所有权。
- 编码器 Surface/codec 的所有释放入口都经过生产者退出屏障，阻塞的旧编码器不能被新实例覆盖。
- 不使用 `eglTerminate` 强制清理共享默认 display，不用 TTL 抹掉未确认回收的资源。

## 有意改变的恢复行为

自动恢复共享同一个有限预算，成功回调或经过一段时间不会自动清零。
每个明确启用周期最多 5 次自动恢复，open retry 最多 3 次、configure retry 最多 2 次、
整 relay 重建最多 1 次，单 key 的纹理替换最多 2 次/30 秒。
同一已计费恢复操作所需的关闭与重开不重复计费。正常用户切换目标不算自动恢复，仍受资源上限约束。

只有明确停用再启用、且旧资源/lease 均确认关闭后，才允许新恢复预算。
资源仍未知或预算耗尽时明确停止该路径，遮掉旧视频；恢复不能重新创建已被用户、R 挡或双闪抑制的窗口。

移除旧的自动 direct/Camera1 降级活动路径。原 direct collector 可能包含多路目标，不能将其称作可靠的“单窗兜底”。
移除 relay 自行循环 revive，避免在统一预算之外再次分配。保留显式 `baseInterval` 接口，
以及每个输出自身的有界慢 swap 节流策略；节流不等待其他输出、不分配替代资源。
恢复行为的变化仍需要台架比较，不能视为已经完成性能调优。

画面裁切、几何设置、信号解析和仪表网络协议/目的地址不在本次重设计范围内。
未提高版本号，未生成或发布可安装版本，未连接车辆。

## 尚未解决的渲染风险

当前仍由输入 context 更新 OES 纹理，输出 context 共享采样。`glFlush` 和每路 busy 标志
不足以证明 GPU 读取完成后才更新同一共享图像。本次不宣称解决该同步风险。

后续应单独评估：输入 context 将 OES 复制到有限 2D/FBO 槽；使用 producer-ready 与
consumer-done fence；所有读者完成后才复用槽；槽耗尽只丢帧，不能无限排队或覆写未完成的图像。
需要目标驱动能力检查、异常 fence 测试、内存/带宽测量，以及带帧号视频源的显示验证。

## 验证

可复现命令及每种检查的边界见 [tests/README.md](../tests/README.md)。

- Host tests 只覆盖应用实际调用的纯 Java 状态/准入/预算策略，不模拟 Camera2、EGL 或 MediaCodec。
- Java syntax check 只解析源码，不替代 Android API 类型检查。
- 完整资源和 API 编译需要真实 Android SDK platform 34/build-tools。
- EGL 故障注入、SurfaceTexture 真实所有权、目标 GPU 同步和长时耐久必须在隔离台架验证。

不要在驾驶中诱发故障。swap 成功、输入帧增加和 TextureView updated 都不是物理屏幕画面新鲜度的完整证明。
