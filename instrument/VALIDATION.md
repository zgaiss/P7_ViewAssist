# 本次集成的离线验证记录

## 验证结果

最终完整构建命令：

```bash
python3 instrument/build_local.py --native-abi-confirmed --android
```

**退出码0；最终源文件与构建清单的所有hash再次核对通过。没有部署、安装或访问车机/仪表。**

| 项目 | 结果及范围 |
|---|---|
| Java协议 | PASS；含AVCC长度256/300/511等歧义回归、严格NAL和控制包分类 |
| Java→C | PASS；实际Java生成的60-byte Hello由C解析 |
| C协议 | PASS；令牌、crop/packet边界、非阻塞读写deadline、EOF |
| H.264预检 | UBSan PASS；10000增量AU、5000随机输入、畸形/变更SPS/PPS/B/多picture拒绝 |
| 实际离线码流 | 独立decoder测试：30个1280×720 Baseline无B的x264 AU预检PASS，不是硬件解码测试 |
| RGB565转换 | UBSan PASS；颜色、LE、stride、crop、奇数chroma、fit黑边、720p上界、alias/溢出 |
| 进程恢复模拟 | 5项HOST-STUB PASS：默认ABI门关闭、拒错令牌、EOF、decoder阻塞先恢复、父死亡恢复 |
| ARMEL完整链接 | PASS；ELF32 ARM soft-float、`/lib/ld-linux.so.3`、依赖原车libvpu/glibc；最高GLIBC需求2.17 |
| Android APK构建 | PASS；官方平台stub编译、D8生成classes.dex、资源齐全；未签名、未安装 |
| 源码检查 | git diff --check PASS；静态复查后修复Surface提前释放、迟到旧设备回调、编码错误无local-only回退等路径 |

ASan本机runtime在main前初始化卡住，不计为通过。HOST-STUB的decoder/display完全替身，不调用SDK/fb/VPU，**模拟restore成功不证明物理仪表恢复成功**。静态复查不是Android HAL实际运行证明。

## 最终完整构建产物

- [机器可读清单（含完整源文件hash、产物hash、外部头hash）](build/local-mqhuiy_3/manifest.json)
- [完整命令与测试输出日志](build/local-mqhuiy_3/commands.log)
- [ARM ELF/动态依赖报告](build/local-mqhuiy_3/receiver-elf.txt)
- [ARMEL实验接收器：只构建未执行](build/local-mqhuiy_3/p7-ic-receiver-armel)
- [Android实验APK：未签名，不是可直接安装交付包](build/local-mqhuiy_3/P7_ViewAssist-instrument-experimental-unsigned.apk)
- [5项本机模拟进程恢复结果](build/guardian-host-sw7mez67/report.json)
- [decoder独立离线验证结果](build/decoder/offline-validation.json)

接收器 SHA256：

```text
b59a4053212db9e20197fda71b0cb78c8c52c878964a65bf9aba60e2be555f81
```

未签名APK SHA256：

```text
8ca179294413e836e9206cfe044777aa26e20b76f8bb1ec8fa663016274e63e7
```

独立decoder记录对应此前object构建，最终完整链接以本页最新manifest为准；最终wrapper还增加firmware2.3.10严格版本门。构建目录仅本机保留，被gitignore排除，换机器需重新构建；SDK/原车库没有打包入仓库。

## 仍未完成的实车验证

- 1280×720连续增量硬件解码，跨多GOP和长时运行。
- 车机编码Surface组合、快速切换方向和local-only回退。
- 左右原始镜头裁切标定、物理小窗刷新/撕裂、CPU、热量和端到端延迟。
- 实际断网/SDK阻塞/信号退出下的FG恢复。
- **FG disable `(0,0)`瞬态遮挡风险尚未解决，维持默认关闭、驻车实验定位。**

完整协议、构建依赖、运行限制与后续验收见[集成说明](README.md)。
