package com.xiaopeng.qa.camera;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.graphics.Outline;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前台服务 + 系统悬浮窗（TYPE_APPLICATION_OVERLAY）+ Camera2/TextureView 实时预览。
 * v1.24：后台常驻架构。打灯联动开启（或手动浮窗）即启动本服务为前台服务并保持运行，
 * SignalMonitor 轮询移入服务内，收到转向信号由服务直接 show/hide/切换 overlay，
 * 彻底摆脱对 Activity 前台状态的依赖（Android 12+ 后台 startForegroundService 会被拒绝，
 * 服务提前在前台运行才能在其他应用上方弹窗）。
 */
public class CameraOverlayService extends Service implements SignalMonitor.Listener, SignalGate.Listener {

    private static final String TAG = "CameraAssist";
    private static final String CHANNEL_ID = "cameraassist_overlay";
    private static final int NOTIF_ID = 1;
    private static final String PREFS_NAME = "cameraassist";

    // 服务命令 action
    private static final String ACTION_START_LINK = "com.xiaopeng.qa.camera.START_LINK";
    private static final String ACTION_STOP_LINK = "com.xiaopeng.qa.camera.STOP_LINK";
    private static final String ACTION_SHOW_MANUAL = "com.xiaopeng.qa.camera.SHOW_MANUAL";
    private static final String ACTION_HIDE_MANUAL = "com.xiaopeng.qa.camera.HIDE_MANUAL";
    private static final String ACTION_HIDE_ALL = "com.xiaopeng.qa.camera.HIDE_ALL";
    // v1.30：浮窗大小调整与位置记录
    private static final String ACTION_RECORD_POS = "com.xiaopeng.qa.camera.RECORD_POS";
    private static final String ACTION_APPLY_SIZE = "com.xiaopeng.qa.camera.APPLY_SIZE";
    // v1.32：还原左右转向浮窗配置到默认
    private static final String ACTION_RESET_OVERLAY = "com.xiaopeng.qa.camera.RESET_OVERLAY";
    // v1.46：右浮窗对齐左浮窗（位置/大小/形状）
    private static final String ACTION_ALIGN_RIGHT_TO_LEFT = "com.xiaopeng.qa.camera.ALIGN_RIGHT_TO_LEFT";
    // v1.52：应用配置（BSD 提醒开关/焦点抑制开关/原厂相机包名），主界面开关改动后推送
    private static final String ACTION_APPLY_CONFIG = "com.xiaopeng.qa.camera.APPLY_CONFIG";
    // v1.54：BSD 常显窄条位置一键复位
    private static final String ACTION_RESET_BSD_POS = "com.xiaopeng.qa.camera.RESET_BSD_POS";
    // v1.54 beta4：BSD 预警浮窗自定义编辑 / 预警浮窗同步转向灯浮窗 / 单独关闭某侧预警浮窗
    private static final String ACTION_EDIT_BSD_WARNING = "com.xiaopeng.qa.camera.EDIT_BSD_WARNING";
    private static final String ACTION_SYNC_BSD_TO_TURN = "com.xiaopeng.qa.camera.SYNC_BSD_TO_TURN";
    private static final String ACTION_CLOSE_BSD_WINDOW = "com.xiaopeng.qa.camera.CLOSE_BSD_WINDOW";
    public static final String EXTRA_BSD_SIDE = "bsd_side";

    // ---- v1.33：画面与窗口解耦模型 ----
    /** 画面基准宽度 = 屏宽*0.4（即默认窗口宽，不随窗口变化），高度按 16:9。 */
    private static final float OVERLAY_DEF_W_RATIO = 0.4f;
    /** 双指画面缩放 cam_scale 范围（默认 1.0）。 */
    private static final float CAM_SCALE_MIN = 0.2f;
    private static final float CAM_SCALE_MAX = 4.0f;
    private static final float CAM_SCALE_DEFAULT = 1.0f;
    /** 窗口边缘拖动最小尺寸 = 屏宽/屏高 * 0.15。 */
    private static final float MIN_SIZE_RATIO = 0.15f;
    // ---- v1.51：位置自动吸附常量 ----
    /** 吸附阈值（dp）：靠得很近才吸，禁止远距离误吸。 */
    private static final int SNAP_THRESHOLD_DP = 20;
    /** 车机左侧竖向快捷边栏右缘吸附线 = 屏宽*0.08。 */
    private static final float LEFT_BAR_RATIO = 0.08f;
    /** 车机底部横向底栏上缘吸附线 = 屏高*0.92。 */
    private static final float BOTTOM_BAR_RATIO = 0.92f;
    /** 预览 buffer 尺寸（16:9，与画面基准同比例）。 */
    private static final int BUFFER_W = 1280;
    private static final int BUFFER_H = 720;
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";

    /** 联动是否开启（服务是否在轮询转向灯），供主界面读取刷新状态。 */
    private static final AtomicBoolean linkActive = new AtomicBoolean(false);
    /** 手动浮窗当前是否显示，供主界面读取刷新状态。 */
    private static final AtomicBoolean manualVisible = new AtomicBoolean(false);
    /** 服务侧运行日志（最近 50 行），供主界面定时拉取显示。 */
    private static final List<String> recentLogs =
            Collections.synchronizedList(new ArrayList<String>());

    private WindowManager wm;
    private View overlayView;
    private TextureView textureView;
    private TextView labelView;
    // ---- v1.38：相机输出 buffer 尺寸固定为 1280x720。注意 onSurfaceTextureAvailable 的 width/height
    // 是 TextureView 视图尺寸（随浮窗窗口变化），不是相机输出 buffer 尺寸，严禁写入 bufferW/H——
    // 若误用作渲染 scale 分母，拖浮窗边框改窗口形状时画面比例会跟着窗口变形。----
    private int bufferW = BUFFER_W;
    private int bufferH = BUFFER_H;

    // ---- v1.57 beta3：仪表 H.264 投影（ICLinkProjector）----
    private final ICLinkProjector icProjector = new ICLinkProjector();
    private boolean iclProjectionEnabled = true;
    private String iclIp = ICLinkProjector.DEFAULT_HOST;
    private int iclPort = ICLinkProjector.DEFAULT_PORT;

    // ---- v1.33：编辑模式（LEFT/RIGHT 转向浮窗；MANUAL 手动浮窗保持 v1.31 可拖动现状）----
    /** 当前浮窗是否处于编辑模式（金色描边 + 四边边缘手柄 + 单指拖动/双指缩放画面）。 */
    private boolean editMode = false;
    /** 当前方向画面缩放 camScale（0.2~4.0，默认1.0；编辑会话双指修改，退出时写入方向键）。 */
    private float camScale = CAM_SCALE_DEFAULT;
    // ---- v1.34：编辑模式画面平移（panX/panY，默认0；编辑会话单指平移，退出时写入方向键）----
    /** 当前方向画面横向平移量（px，正右负左，0=居中）。 */
    private float panX = 0f;
    /** 当前方向画面纵向平移量（px，正下负上，0=居中）。 */
    private float panY = 0f;
    /** 编辑模式视觉元素：金色描边边框、四边边缘手柄（改窗口形状）、底部角标。 */
    private View borderView;
    private View cropHandleL;
    private View cropHandleR;
    private View cropHandleT;
    private View cropHandleB;
    // ---- v1.51：四角等比例缩放手柄（编辑模式显示，拖动窗口角等比缩放）----
    private View cropHandleTL;
    private View cropHandleTR;
    private View cropHandleBL;
    private View cropHandleBR;
    private TextView editHintView;

    private CameraManager cameraManager;
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread bgThread;
    private Handler bgHandler;
    // ---- v1.62：GL 一进多出中转（根治双窗多 target 会话失败）----
    /** 相机唯一输出面 + 多窗口分发；null 表示尚未创建。 */
    private GlCameraRelay glRelay = null;
    /** 是否启用 GL 中转；初始化失败自动置 false 并回退直连相机。 */
    private boolean glRelayMode = true;
    /** v1.63：GL 中转连续停帧后判定不可用，本次会话内不再尝试（单路直连即可正常出画面）。 */
    private boolean glRelayDisabled = false;
    /** v1.63：中转停帧看门狗的采样状态。 */
    private long relayWatchFrames = -1L;
    private long relayWatchChangeMs = 0L;

    // ============ v1.67 beta3：主窗（key="P"）画面更新探针 ============
    /** 主窗 TextureView 最近一次 onSurfaceTextureUpdated 的时刻（0=本轮显示还没出过帧）。 */
    private static volatile long lastPUpdatedMs = 0L;
    /** 主窗 onSurfaceTextureUpdated 累计回调次数。 */
    private static volatile long pUpdatedTotal = 0L;
    /** 探针锚点：主窗开始显示的时刻，用于「一直没出过帧」的判据。 */
    private long pProbeAnchorMs = 0L;
    /** 主窗超过该时长没有新帧即判定「未更新」。 */
    private static final long P_UPDATE_STALL_MS = 5000L;
    /** 探针巡检周期（独立于中转看门狗的 800ms）。 */
    private static final long P_PROBE_WATCH_MS = 5000L;
    /** 仅在「冻结 / 恢复」翻转时各记一条，避免每 5 秒刷屏。 */
    private boolean pStallRecorded = false;
    /** 探针巡检是否已在跑。 */
    private volatile boolean pProbeRunning = false;
    /** v1.67 beta4：探针巡检改由主线程 Handler 驱动（不受 camera-bg 线程生死影响）。 */
    private final Handler pProbeHandler = new Handler(Looper.getMainLooper());
    /** v1.67 beta4：当前存活的服务实例，供主界面兜底重启探针。 */
    private static volatile CameraOverlayService sProbeOwner = null;
    /** 探针记录（最多 12 条，最新在最前），供主界面「主窗更新探针」面板展示。 */
    private static final List<String> probeRecords =
            Collections.synchronizedList(new ArrayList<String>());
    private static final int PROBE_MAX = 12;
    /** v1.67 beta4：探针门控（相机打开中/会话重建中）持续时长起点。 */
    private long pProbeGateSince = 0L;
    /** v1.67 beta4：门控阻塞记录上一次写入时刻（30s 节流）。 */
    private long pProbeGateLoggedAt = 0L;
    /** v1.67 beta4：状态错位记录上一次写入时刻（30s 节流）。 */
    private long pProbeMismatchLoggedAt = 0L;
    /** v1.67 beta4：最近一次心跳（无条件）写入时刻，用于确认探针本体还活着。 */
    private long pProbeHeartbeatAt = 0L;
    private long relayLastStallMs = 0L;
    private int relayStallCount = 0;
    /** v1.67：最近一次因资源压力整实例重建中转的时刻。 */
    private long lastRelayFullRebuildMs = 0L;
    /** v1.67：60 秒窗口内的整实例重建次数，超过 2 次即降级直连。 */
    private int relayFullRebuildCount = 0;

    private Surface previewSurface;
    /** v1.57 beta4：previewSurface 当前绑定的 SurfaceTexture，用于同纹理复用 Surface 而不重建。 */
    private SurfaceTexture previewSurfaceTex = null;
    /** v1.57 beta4：当前已打开的 cameraId（openCamera 成功下发时记录，供复用判断）。 */
    private String openedCameraId = null;
    /** v1.57 beta4：openCamera 失败重试计数（onOpened 归零）。 */
    private int openRetryCount = 0;
    /** v1.57 beta4：openCamera 最大重试次数。 */
    private static final int OPEN_RETRY_MAX = 3;
    /** v1.57 beta4：createCaptureSession 在途标志（重建飞行锁，防双窗并发 configure）。 */
    private volatile boolean sessionCreating = false;
    /** v1.57 beta4：会话重建期间收到的新请求，串行补建一次。 */
    private volatile boolean sessionRebuildPending = false;
    /** v1.57 beta4：会话 configure 失败重试计数（onConfigured 归零）。 */
    private int sessionRetryCount = 0;
    /** v1.57 beta4：会话 configure 最大重试次数。 */
    private static final int SESSION_RETRY_MAX = 2;
    // ---- v1.57 beta5：会话重建去抖 / target 签名去重 / 目标延迟释放 ----
    /** 当前已生效会话的 target 签名（M=主窗, R=BSD 右窗, L=左编辑窗, E=右编辑窗, I=仪表投影）。
     * 目标集合未变化时跳过重建，杜绝同帧多次整条会话重配。 */
    private String sessionTargetSig = "";
    /** 重建请求序号：每次发起重建递增，过期会话回调按序号丢弃，防止旧会话覆盖新状态。 */
    private int sessionReqSeq = 0;
    /** 待释放 Surface：等新会话配置成功后再释放，避免会话向已释放目标提交帧导致画面冻结。 */
    private final java.util.ArrayList<Surface> pendingSurfaceRelease = new java.util.ArrayList<>();
    /** 待释放 SurfaceTexture：同上。 */
    private final java.util.ArrayList<SurfaceTexture> pendingTexRelease = new java.util.ArrayList<>();
    /** openCamera 单飞标志：并发 openCamera 会产生多个 StateCallback 相互关闭相机。 */
    private boolean cameraOpening = false;
    /** openCamera 单飞起始时间（ms），超过 3s 视为失效允许重新打开。 */
    private long cameraOpeningAtMs = 0L;
    /** AVM 守护重发计数：达到上限后停止，避免反复下发打断预览流。 */
    private int avmRefreshCount = 0;
    /** AVM 守护重发次数上限。 */
    private static final int AVM_REFRESH_MAX = 5;

    private String direction = "LEFT";
    /** 当前已打开摄像头对应的方向（LEFT/RIGHT/MANUAL），用于去抖分支判断是否需要重开摄像头。 */
    private String openedDirection = null;
    /** 当前是否处于 AVM 视角下发模式（转向灯绑定 AVM 视角时打灯经 AVM 下发，不打开物理摄像头）。 */
    private boolean avmModeActive = false;
    /** v1.39：最近一次成功激活浮窗的转向方向。打灯期间同方向重复亮起回调一律忽略，
     * 杜绝 dumpsys 抖动产生的假 0->1 边沿导致周期式重发绑定视角（覆盖倒挡影像、卡顿）。 */
    private String lastActiveDir = null;
    // ---- v1.46：浮窗右上角关闭按钮的“关闭抑制”状态 ----
    /** 手动提前关闭浮窗后处于抑制状态的方向（"LEFT"/"RIGHT"），null=无抑制。
     * 抑制期间同方向 0->1 亮起信号不重开浮窗（防打灯抖动假边沿误重开）；
     * 检测到 1 秒熄灭信号（两灯均 0 持续 1s）或换方向打灯时解除抑制并恢复召唤。 */
    private String closeSuppressedDir = null;

    private SignalMonitor monitor;
    private AdbClient adb;
    private Handler mainHandler;

    // ---- v1.52：SignalGate 慢变量门控 / BSD 旁路 / Camera1 回退 / R 挡退出延时抑制 ----
    private SignalGate signalGate;
    private SideWarning sideWarning;
    private Camera1Preview camera1Preview;
    /** BSD 提醒开关（偏好 bsd_warning_enabled，默认 false）。 */
    private boolean bsdWarningEnabled = false;
    /** R 挡退出延时抑制窗口时长（ms，偏好 r_exit_suppress_ms，默认 2000）。 */
    private long rExitSuppressMs = 2000L;
    /** R 挡退出延时抑制窗口截止时间戳（ms）；0=无窗口。 */
    private long rExitSuppressUntilMs = 0L;
    /** 用于 R 挡 3->非3 边沿检测的上一轮 gearLevel（仅当解析到 GEARLEV 才更新）。 */
    private int lastGearForRExit = -1;
    /** v1.53 beta2：BSD 无车常显开关（偏好 bsd_show_always，默认 false），
     * 开启时 SideWarning 无车也显示调试样式，便于拖动调位置。 */
    private boolean bsdShowAlways = false;
    // ---- v1.54：BSD 预警自动弹窗（最低优先级） ----
    /** BSD 预警自动弹窗开关（偏好 bsd_auto_overlay，默认 true）：开启后检测
     * SCU_BSD_LEFT_WARNING/RIGHT_WARNING 0->1 边沿自动弹对应侧摄像头浮窗，
     * 预警熄灭后自动关闭对应侧；优先级最低，不干扰打灯/倒车/窄路辅助。 */
    private boolean bsdAutoOverlay = true;
    /** v1.54 beta3：BSD 预警浮窗红色遮罩开关（偏好 bsd_red_overlay，默认 true）：
     * 开启时预警弹窗边界加一小圈向内的半透明红色遮罩（示意预警状态）；
     * 仅 BSD 预警弹窗显示，打灯浮窗/手动浮窗不显示。 */
    private boolean bsdRedOverlay = true;
    // ---- v1.65 beta1：双闪双侧出图开关（调试双窗用） ----
    /** 双闪联动开关（偏好 hazard_dual_enabled，默认 false）。
     * 开启：打双闪（左右转向灯同时亮）时按双侧事件投递，优先窗显示左侧、次级窗显示右侧，
     * 两窗同时出来；关闭：保持 v1.48 起的旧语义——双闪不弹窗，若正在显示则收起。 */
    private boolean hazardDualEnabled = false;
    /** 上一轮 BSD 预警状态（左/右），用于 0->1 边沿检测与日志去抖。 */
    private boolean lastBsdLeft = false;
    private boolean lastBsdRight = false;
    /** v1.56 beta1：打灯浮窗联动 BSD 红框的边沿跟踪（上一轮左右 BSD 状态），
     * 由 onSignalTick 实时调用 onBsdEdgeForTurn 叠加/移除红框使用；
     * 打灯浮窗不在显示或红框开关关闭时复位，避免重入浮窗时误叠加。 */
    private boolean lastTurnBsdLeft = false;
    private boolean lastTurnBsdRight = false;
    /** 当前由 BSD 自动弹窗激活的浮窗方向（"LEFT"/"RIGHT"/null）。打灯/手动等更高优先级
     * 接管时置 null 让位；BSD 预警全灭时关闭对应侧浮窗。 */
    private String bsdAutoActiveDir = null;
    /** 以 BSD 自动弹窗身份调用 showOverlayForDirection 的临时标记：跳过打灯幂等锁与
     * lastActiveDir 写入，避免 BSD 弹窗污染打灯方向锁。 */
    private boolean bsdAutoCalling = false;
    // ---- v1.67 beta6：L1 延迟销毁（熄灯/预警结束收窗立即，相机与会话留 LINGER_MS 待复用） ----
    /**
     * 收窗后相机/会话的保留时长。
     *
     * <p>熄灯链路最贵的不是窗口（removeView 几十毫秒），而是相机 close+open 与
     * CaptureSession 重新协商（车机 HAL 数百毫秒到秒级，来不及协商就是空框/冻结）。
     * 保留 1.2s 足以覆盖「左灯→关→右灯」的人工间隔，超时立即完整释放。</p>
     */
    private static final long LINGER_MS = 1200L;
    /**
     * v1.70：相机延迟释放总开关。
     *
     * <p>beta5 是「熄灯即销毁窗口 + 立即释放相机」，实车不卡。beta6 为省掉相机开关开销加了
     * 1.2s 延迟释放并复用会话，结果把最短路径从「说开就开」变成「等 1.2s 释放 + 250ms 冷却
     * + CAMERA_IN_USE 300/600/900ms 重试」：熄灯后 1.2s 内再打另一侧，必定撞进长尾等待，
     * relay 侧表现为新输出迟迟不出帧 → 判死 → 换纹理 → 卡顿与掉帧。v1.70 起默认关闭。</p>
     */
    private static final boolean CAMERA_LINGER_ENABLED = false;

    /**
     * 延迟窗口内是否复用现有 CaptureSession。
     *
     * <p>v1.62 起相机侧输出恒为 relay 的离屏输入面（签名恒为 RELAY），窗口重建只是
     * 给 relay 重新 attach 一路输出，相机侧目标集合不变，因此会话无需重建。
     * 做成常量开关：真机若出现复用后不出图，改 false 即回到「复用时强制重建会话」。</p>
     */
    private static final boolean LINGER_REUSE_SESSION = true;
    /**
     * v1.67 beta8：相机释放后到允许再次 openCamera 的最小间隔。
     *
     * <p>HAL 侧 close 是异步的，紧接着 openCamera 会拿到 CAMERA_IN_USE，落进
     * scheduleOpenRetry 的 300/600/900ms 递增重试，用户看到的就是"打灯半天不出画面"。
     * 先等冷却窗口过去再开，把这段重试换成一次有序等待。</p>
     */
    private static final long CAMERA_REOPEN_GUARD_MS = 0L;   // v1.70：归零，等价 beta5 的「说开就开」
    /** 已排一次冷却后的重开请求，避免重复排队（beta8）。 */
    private boolean cameraReopenPending = false;
    /** 最近一次释放相机的时间戳，用于 reopen 冷却判定（beta8）。 */
    private long lastCameraClosedMs = 0L;
    /** 「相机已收窗、待延迟释放」计时是否在跑。 */
    private boolean cameraLingerActive = false;
    /** 延迟窗口内被复用（取消释放）的次数，仅用于诊断打点。 */
    private int cameraLingerReuseCount = 0;
    /** 延迟到期：完整释放相机与会话（幂等）。 */
    private final Runnable cameraLingerRunnable = new Runnable() {
        @Override
        public void run() {
            boolean wasActive = cameraLingerActive;
            cameraLingerActive = false;
            boolean had = cameraDevice != null || captureSession != null;
            if (!wasActive && !had) {
                return;
            }
            serviceLog("相机延迟窗口到期（" + LINGER_MS + "ms）：完整释放相机与会话，期间复用 "
                    + cameraLingerReuseCount + " 次");
            cameraLingerReuseCount = 0;
            if (had) {
                releaseCamera();
            }
        }
    };
    // ---- v1.54：BSD 双窗避让（左右同时预警时右窗紧挨左窗右侧，不重叠） ----
    /** BSD 双窗时右窗与左窗的间距（dp）。 */
    private static final int BSD_DUAL_GAP_DP = 8;
    /** BSD 双窗场景的右侧浮窗（复用 overlay_window 布局，只读展示，不参与拖动/编辑）。 */
    private View bsdRightOverlayView = null;
    private TextureView bsdRightTextureView = null;
    private TextView bsdRightLabelView = null;
    private Surface bsdRightSurface = null;
    /** v1.57 beta4：bsdRightSurface 当前绑定的 SurfaceTexture（用于同纹理复用 Surface）。 */
    private SurfaceTexture bsdRightSurfaceTex = null;
    /** BSD 右窗当前是否显示。 */
    private boolean bsdRightOverlayShowing = false;
    /** 当前预览会话是否为“双 surface”模式（主窗 + BSD 右窗），true 时 createPreviewSession 同时输出两路。 */
    private boolean dualSessionTargets = false;
    // ---- v1.54 beta4：BSD 预警浮窗自定义编辑（独立 bsd_ 前缀键，左右各自独立） ----
    /** BSD 预警编辑态是否激活（主界面「编辑预警浮窗」按钮召唤；再次点击同时关闭并保存）。 */
    private boolean bsdEditActive = false;
    /** v1.54 beta6：BSD 编辑态是否由本服务独立启动摄像头预览（无打灯/BSD/手动浮窗归属）。 */
    private boolean bsdEditPreviewActive = false;
    /** v1.54 beta6：进入编辑态独立启动预览前的原 direction，退出编辑时恢复。 */
    private String savedDirectionBeforeEdit = null;
    /** BSD 左/右预警编辑浮窗（复用 overlay_window 布局，独立可拖动/双击编辑/右上角关闭）。 */
    private BsdEditWindow bsdEditLeft = null;
    private BsdEditWindow bsdEditRight = null;
    // ---- v1.53：AVM 画面守护 / 焦点解析日志 ----
    /** AVM 绑定视角守护重发间隔（ms）：打灯浮窗存活期间节流重发抵抗外部抢占。 */
    private static final long AVM_REFRESH_INTERVAL_MS = 1500L;
    /** 最近一次 AVM 守护重发时间戳（ms），0=未重发过。 */
    private long lastAvmRefreshMs = 0L;
    /** 焦点解析日志节流：记录上次上屏的包名与命中，仅在变化时上屏防刷屏。 */
    private String lastFocusLogPkg = null;
    private boolean lastFocusLogHit = false;
    // ---- v1.56 beta3：窄路辅助主动收起 / 退出恢复 ----
    /** 上一轮窄路辅助窗口在屏状态（裸包名窗口 com.xiaopeng.xmart.camera），用于 0->1/1->0 边沿检测。 */
    private boolean lastNarrowAssistActive = false;
    /** 窄路辅助激活时正在显示的打灯方向快照（"LEFT"/"RIGHT"/null），窄路消失后据此恢复打灯浮窗。 */
    private String narrowTurnDirSnapshot = null;
    /** 窄路辅助期间打灯转向信号是否仍亮（onSignalChanged 熄灭回调置 false，亮起回调置 true）。 */
    private boolean narrowTurnStillOn = false;
    /** 窄路辅助退出恢复后的防抖抑制窗口截止时间戳（ms），窗口内窄路再现不重复收起，防抖动循环。 */
    private long narrowExitSuppressUntilMs = 0L;
    /** 窄路辅助退出恢复防抖窗口时长（ms，参考 R 挡退出 2000ms 抑制机制）。 */
    private static final long NARROW_EXIT_SUPPRESS_MS = 2000L;

    /** 启动前台服务。后台场景可能抛 ForegroundServiceStartNotAllowedException，此处捕获并返回是否
     * 启动成功，由调用方决定延后补启，确保任何生命周期状态下都不会因启动服务导致进程崩溃。 */
    /** 开启打灯联动：启动/复用常驻前台服务并开始转向灯轮询（服务在前台运行，退后台也能弹窗）。 */
    public static void startLink(Context ctx, String host, int port) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_START_LINK);
        i.putExtra(EXTRA_HOST, host);
        i.putExtra(EXTRA_PORT, port);
        safeStart(ctx, i);
    }

    /** 关闭打灯联动：停止轮询并隐藏浮窗；若手动浮窗也未显示则停止前台服务。 */
    public static void stopLink(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_STOP_LINK);
        safeStart(ctx, i);
    }

    /** 显示手动浮窗（调试用）：服务未运行则先启动前台服务。 */
    public static void showManual(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_SHOW_MANUAL);
        safeStart(ctx, i);
    }

    /** 隐藏手动浮窗；若无联动在运行则停止前台服务。 */
    public static void hideManual(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_HIDE_MANUAL);
        safeStart(ctx, i);
    }

    /** 记录当前浮窗位置（v1.30）：服务读取 overlayView 当前 x/y 写入 prefs 并置 recorded=true。 */
    public static void recordCurrentPosition(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_RECORD_POS);
        safeStart(ctx, i);
    }

    /** 按 overlay_scale 应用浮窗大小（v1.30）：浮窗正在显示时实时调整宽高。 */
    public static void applyOverlaySize(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_APPLY_SIZE);
        safeStart(ctx, i);
    }

    /** 还原左右转向浮窗配置到默认（v1.32）：清除 LEFT/RIGHT 两组配置键，浮窗在显示时按默认刷新。 */
    public static void resetOverlayDefaults(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_RESET_OVERLAY);
        safeStart(ctx, i);
    }

    /** 右浮窗对齐左浮窗（v1.46）：将 RIGHT 方向配置（位置/宽高/画面缩放/平移）复制为 LEFT 的当前配置；
     * 浮窗正在显示右转向浮窗时立即应用。 */
    public static void alignRightToLeft(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_ALIGN_RIGHT_TO_LEFT);
        safeStart(ctx, i);
    }

    /**
     * v1.52/v1.53 beta2/v1.54 beta4：应用慢变量门控配置（BSD 提醒开关 /
     * BSD 无车常显开关 / BSD 自动弹窗开关 / BSD 预警红色遮罩开关）。
     * v1.54 beta4：焦点抑制与原厂相机包名功能停用，不再写入/读取相关 prefs。
     * MainActivity 的开关改动后调用：写入 prefs 并推送 ACTION_APPLY_CONFIG
     * 让常驻服务即时生效（服务可能因未联动/手动浮窗而存续，直接改 prefs 不会被读取）。
     */
    public static void applyConfig(Context ctx, boolean bsdOn,
                                   boolean bsdShowAlways, boolean bsdAutoOverlay,
                                   boolean bsdRedOverlay) {
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        ed.putBoolean("bsd_warning_enabled", bsdOn);
        ed.putBoolean("bsd_show_always", bsdShowAlways);
        ed.putBoolean("bsd_auto_overlay", bsdAutoOverlay);
        ed.putBoolean("bsd_red_overlay", bsdRedOverlay);
        ed.apply();
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_APPLY_CONFIG);
        safeStart(ctx, i);
    }

    /** v1.65 beta1：设置「双闪联动」开关并即时生效（偏好 hazard_dual_enabled）。
     * 开启后打双闪同时触发左右两侧影像（优先窗左 / 次级窗右）；关闭后打双闪不显示影像。
     * 与 applyConfig 一样走 ACTION_APPLY_CONFIG 让常驻服务重读偏好。 */
    public static void setHazardDualEnabled(Context ctx, boolean on) {
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        ed.putBoolean("hazard_dual_enabled", on);
        ed.apply();
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_APPLY_CONFIG);
        safeStart(ctx, i);
    }

    /** v1.54：BSD 常显窄条位置一键复位：清除 bsd_left/right_x/y 持久化键并恢复默认位置。 */
    public static void resetBsdPositions(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_RESET_BSD_POS);
        safeStart(ctx, i);
    }

    /** v1.58：进入「优先、次级浮窗位置设置」编辑态（side=LEFT/RIGHT 对应左状态/右状态）。 */
    public static void editSlotLayout(Context ctx, String side) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_EDIT_BSD_WARNING);
        i.putExtra(EXTRA_BSD_SIDE, side);
        safeStart(ctx, i);
    }

    /** v1.58：退出浮窗编辑并保存。 */
    public static void closeSlotEdit(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_CLOSE_BSD_WINDOW);
        safeStart(ctx, i);
    }

    /** v1.58：一键同步大小（次级窗 S 跟随优先窗 P 的宽高）。 */
    public static void syncSlotSize(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_SYNC_BSD_TO_TURN);
        safeStart(ctx, i);
    }

    /** v1.58：复位两窗位置/大小/画面到默认。 */
    public static void resetSlotGeometry(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_RESET_OVERLAY);
        safeStart(ctx, i);
    }

    /** v1.54 beta4：召唤/关闭 BSD 预警浮窗编辑态（主界面「编辑预警浮窗」按钮调用）。
     * 未召唤时同时召唤左右预警浮窗进入可编辑状态；已召唤时同时关闭并保存两侧配置。 */
    public static void editBsdWarning(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_EDIT_BSD_WARNING);
        safeStart(ctx, i);
    }

    /** v1.54 beta4：预警浮窗同步转向灯浮窗（左对左、右对右：位置/大小/画面 pan 缩放）。 */
    public static void syncBsdToTurn(Context ctx) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_SYNC_BSD_TO_TURN);
        safeStart(ctx, i);
    }

    /** v1.54 beta4：单独关闭某侧 BSD 预警编辑浮窗并保存该侧配置（编辑窗右上角按钮）。 */
    public static void closeBsdWindow(Context ctx, String side) {
        Intent i = new Intent(ctx, CameraOverlayService.class);
        i.setAction(ACTION_CLOSE_BSD_WINDOW);
        if (side != null) {
            i.putExtra(EXTRA_BSD_SIDE, side);
        }
        safeStart(ctx, i);
    }

    private static void safeStart(Context ctx, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "启动前台服务被拒绝: " + e.getMessage());
            // 服务已在后台且未提前前台运行时才会走到这里；正常情况下
            // 联动/手动浮窗都在主界面前台点击，或服务已常驻，不会触发。
            serviceLog("启动服务被系统拒绝，请从主界面重新开启");
        }
    }

    public static boolean isLinkActive() {
        return linkActive.get();
    }

    public static boolean isManualVisible() {
        return manualVisible.get();
    }

    /** 拉取并清空服务侧新增日志（主界面定时刷新用）。 */
    public static List<String> drainLogs() {
        List<String> out = new ArrayList<String>();
        synchronized (recentLogs) {
            if (!recentLogs.isEmpty()) {
                out.addAll(recentLogs);
                recentLogs.clear();
            }
        }
        return out;
    }

    /** v1.41：清空服务侧内存日志（主界面“清空日志”按钮调用，与显示区同步清空）。 */
    public static void clearLogs() {
        synchronized (recentLogs) {
            recentLogs.clear();
        }
    }

    private static void serviceLog(String line) {
        Log.i(TAG, line);
        synchronized (recentLogs) {
            recentLogs.add(line);
            if (recentLogs.size() > 50) {
                recentLogs.remove(0);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sProbeOwner = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        bgThread = new HandlerThread("camera-bg");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        createNotificationChannel();
        // v1.58：一次性把旧版窗口/画面配置映射到 pa_/pb_ 新键
        migrateV158();
        // v1.52：读取慢变量门控相关偏好；signalGate 在联动开启（startLinkInternal）时随 adb 启动
        loadConfigPrefs();
        signalGate = new SignalGate(this);
        sideWarning = new SideWarning(this, wm);
        serviceLog("前台服务已常驻（后台可弹窗）");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }
        String action = intent.getAction();
        if (action == null) action = "";
        if (ACTION_START_LINK.equals(action)) {
            startForegroundCompat();
            String host = intent.getStringExtra(EXTRA_HOST);
            int port = intent.getIntExtra(EXTRA_PORT, 5050);
            startLinkInternal(host, port);
        } else if (ACTION_STOP_LINK.equals(action)) {
            stopLinkInternal();
        } else if (ACTION_SHOW_MANUAL.equals(action)) {
            startForegroundCompat();
            manualVisible.set(true);
            showOverlayForDirection("MANUAL");
        } else if (ACTION_HIDE_MANUAL.equals(action)) {
            manualVisible.set(false);
            hideOverlayAndCamera();
            if (!linkActive.get()) {
                stopSelfInternal();
            }
        } else if (ACTION_HIDE_ALL.equals(action)) {
            stopLinkInternal();
        } else if (ACTION_RECORD_POS.equals(action)) {
            recordPositionInternal();
        } else if (ACTION_APPLY_SIZE.equals(action)) {
            applyOverlaySizeInternal();
        } else if (ACTION_RESET_OVERLAY.equals(action)) {
            resetSlotGeometryInternal();
        } else if (ACTION_ALIGN_RIGHT_TO_LEFT.equals(action)) {
            syncSlotSizeInternal();
        } else if (ACTION_APPLY_CONFIG.equals(action)) {
            applyConfigInternal();
        } else if (ACTION_RESET_BSD_POS.equals(action)) {
            resetBsdPositionsInternal();
        } else if (ACTION_EDIT_BSD_WARNING.equals(action)) {
            openSlotEditFromUi(intent.getStringExtra(EXTRA_BSD_SIDE));
        } else if (ACTION_SYNC_BSD_TO_TURN.equals(action)) {
            syncSlotSizeInternal();
        } else if (ACTION_CLOSE_BSD_WINDOW.equals(action)) {
            closeSlotEditInternal();
        }
        return START_STICKY;
    }

    /** 开启联动轮询：创建 ADB 连接并启动 SignalMonitor（300ms 轮询转向灯）。 */
    private void startLinkInternal(String host, int port) {
        if (linkActive.get()) {
            // 已开启：仅更新连接参数（服务常驻，无需重复启动线程）
            serviceLog("联动已在运行，更新 ADB 参数 " + host + ":" + port);
            return;
        }
        byte[] pem = readRawAdbKey();
        if (pem.length == 0) {
            serviceLog("内置密钥读取失败，无法建立 ADB 连接");
            return;
        }
        try {
            adb = new AdbClient(host, port, pem);
            monitor = new SignalMonitor(adb, this, 300);
            if (monitor.start()) {
                linkActive.set(true);
                // v1.52：联动开启时同步启动 SignalGate 慢变量轮询（BSD/AVM/挡位冗余）
                // v1.54 beta4：焦点抑制停用，不再下发原厂相机包名列表
                if (signalGate != null) {
                    signalGate.start(adb);
                }
                // v1.52：BSD 提醒开启时挂载盲区窄条
                if (bsdWarningEnabled && sideWarning != null) {
                    sideWarning.attach();
                }
                serviceLog("联动已开启，ADB 轮询启动 (" + host + ":" + port + ")");
                updateNotificationText("联动轮询运行中 · 转向灯弹窗就绪");
            } else {
                serviceLog("旧轮询线程仍在退出（Socket read 最长 15s），请稍后重新开启联动");
                adb = null;
                monitor = null;
            }
        } catch (Exception e) {
            serviceLog("初始化失败: " + e.getMessage());
        }
    }

    /** 关闭联动：停止轮询、隐藏浮窗；无手动浮窗时停止前台服务。 */
    private void stopLinkInternal() {
        linkActive.set(false);
        if (monitor != null) {
            monitor.stop();
            monitor = null;
        }
        // v1.52：联动关闭时同步停止 SignalGate 慢变量轮询并摘除 BSD 窄条
        if (signalGate != null) {
            signalGate.stop();
        }
        if (sideWarning != null) {
            sideWarning.detach();
        }
        if (adb != null) {
            adb = null;
        }
        hideOverlayAndCamera();
        serviceLog("联动已关闭，轮询停止");
        if (!manualVisible.get()) {
            stopSelfInternal();
        }
    }

    /** 停止前台服务：先移除浮窗再 stopForeground/stopSelf，避免 IllegalStateException。 */
    private void stopSelfInternal() {
        hideOverlayAndCamera();
        // v1.67 beta4：停止前清空打灯/槽位状态，避免重开总开关后 evt 电平仍为 true
        // 导致「电平没变化 → 不投递事件 → 双闪窗再也出不来/收不回」。
        resetSlotStateOnStop();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    /** 读取内置 ADB 私钥（res/raw/adb_key），与服务同 APK 直接读取，无需 Activity 传入。 */
    private byte[] readRawAdbKey() {
        try {
            java.io.InputStream is = getResources().openRawResource(R.raw.adb_key);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            is.close();
            return bos.toByteArray();
        } catch (Exception e) {
            serviceLog("读取内置密钥失败: " + e.getMessage());
            return new byte[0];
        }
    }

    private void updateNotificationText(String text) {
        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        nb.setContentTitle("CameraAssist")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true);
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
        nb.setContentIntent(pi);
        startForeground(NOTIF_ID, nb.build());
    }

    private void startForegroundCompat() {
        Notification.Builder nb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        nb.setContentTitle("CameraAssist")
                .setContentText(linkActive.get() ? "联动轮询运行中 · 转向灯弹窗就绪"
                        : "侧边摄像头预览运行中")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true);
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
        nb.setContentIntent(pi);
        Notification n = nb.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "CameraAssist 预览", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }

    // ---- SignalMonitor.Listener：转向信号由服务直接处理，不依赖 Activity 前台状态 ----

    @Override
    public void onLog(final String line) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                serviceLog(line);
            }
        });
    }

    @Override
    public void onSignalChanged(final String direction, final boolean anyOn) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (anyOn && direction != null) {
                    // v1.65 beta1：双闪（左右同时亮）单独处理——由「双闪联动」开关决定是否双侧出图
                    if ("BOTH".equals(direction)) {
                        if (!hazardDualEnabled) {
                            serviceLog("双闪检测：双闪联动已关闭，不召唤浮窗（若在显示则收起）");
                            closeSuppressedDir = null;
                            postTurnEvent(null, false);
                            return;
                        }
                        if (closeSuppressedDir != null) {
                            serviceLog("双闪触发，解除关闭抑制");
                            closeSuppressedDir = null;
                        }
                        serviceLog("双闪检测：双闪联动已开启，优先窗出左侧、次级窗出右侧");
                        postTurnEventBilateral();
                        return;
                    }
                    if (closeSuppressedDir != null && closeSuppressedDir.equals(direction)) {
                        serviceLog("关闭抑制中（" + direction + "），同方向亮起不重开浮窗");
                        return;
                    }
                    if (closeSuppressedDir != null) {
                        serviceLog("检测到换方向打灯（" + direction + "），解除关闭抑制并重新召唤浮窗");
                        closeSuppressedDir = null;
                    }
                    serviceLog(("LEFT".equals(direction) ? "左转向灯" : "右转向灯") + " 亮起，投递打灯事件");
                    postTurnEvent(direction, true);
                } else {
                    if (closeSuppressedDir != null) {
                        serviceLog("检测到1秒熄灭信号，解除关闭抑制");
                        closeSuppressedDir = null;
                    }
                    serviceLog("转向灯熄灭，投递打灯事件（两侧全灭）");
                    postTurnEvent(null, false);
                }
            }
        });
    }

    /**
     * v1.45：ADB 连接成功后自动补授悬浮窗权限。
     * 新车机安装后 appops SYSTEM_ALERT_WINDOW 默认拒绝，浮窗无法直接弹出；
     * 此处检测未授权则经同一已认证 ADB 连接下发 appops set ... allow（失败再尝试 pm grant）。
     * 在 SignalMonitor 轮询线程内执行，任何异常仅记日志，不阻塞联动。
     */
    @Override
    public void onAdbConnected() {
        if (Settings.canDrawOverlays(this)) {
            return;
        }
        AdbClient conn = adb;
        if (conn == null) {
            return;
        }
        try {
            serviceLog("ADB 已连接，检测到悬浮窗未授权，自动申请权限…");
            String r = conn.exec("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
            serviceLog("ADB 授权悬浮窗(appops)：" + (r == null || r.trim().isEmpty() ? "已执行（无输出）" : r.trim()));
            if (!Settings.canDrawOverlays(this)) {
                String r2 = conn.exec("pm grant " + getPackageName() + " android.permission.SYSTEM_ALERT_WINDOW");
                serviceLog("ADB 授权悬浮窗(pm grant)：" + (r2 == null || r2.trim().isEmpty() ? "已执行（无输出）" : r2.trim()));
            }
            if (Settings.canDrawOverlays(this)) {
                serviceLog("悬浮窗权限已生效，浮窗可正常弹出");
            } else {
                serviceLog("悬浮窗授权命令已执行，但尚未生效，请确认车机允许");
            }
        } catch (Exception e) {
            serviceLog("ADB 自动授权悬浮窗失败（不影响联动）：" + e.getMessage());
        }
    }

    // ---- v1.52：慢变量门控配置加载 / 应用 / 回调 ----

    private void loadConfigPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bsdWarningEnabled = prefs.getBoolean("bsd_warning_enabled", false);
        bsdShowAlways = prefs.getBoolean("bsd_show_always", false);
        bsdAutoOverlay = prefs.getBoolean("bsd_auto_overlay", true);
        bsdRedOverlay = prefs.getBoolean("bsd_red_overlay", true);
        // v1.65 beta1：双闪联动开关（默认关，保持旧行为）
        hazardDualEnabled = prefs.getBoolean("hazard_dual_enabled", false);
        rExitSuppressMs = prefs.getLong("r_exit_suppress_ms", 2000L);
        if (rExitSuppressMs < 0) rExitSuppressMs = 2000L;
        // v1.57 beta3：仪表 H.264 投影开关/地址（主界面无 UI，仅保留 prefs 钩子供调试）
        iclProjectionEnabled = prefs.getBoolean("icl_projection_enable", true);
        iclIp = prefs.getString("icl_ip", ICLinkProjector.DEFAULT_HOST);
        iclPort = prefs.getInt("icl_port", ICLinkProjector.DEFAULT_PORT);
    }

    /** ACTION_APPLY_CONFIG：重新加载偏好并即时生效（BSD 窄条挂载/摘除）。v1.54 beta4 不再下发焦点包名。 */
    private void applyConfigInternal() {
        loadConfigPrefs();
        if (bsdWarningEnabled) {
            if (sideWarning != null && linkActive.get()) {
                sideWarning.attach();
            }
        } else {
            if (sideWarning != null) {
                sideWarning.detach();
            }
        }
        serviceLog("配置已应用：BSD提醒=" + bsdWarningEnabled
                + " BSD常显=" + bsdShowAlways + " BSD自动弹窗=" + bsdAutoOverlay
                + " BSD红遮罩=" + bsdRedOverlay
                + " 双闪联动=" + hazardDualEnabled);
        // v1.65 beta1：双闪联动由开改关时，若当前正处双闪双侧出图态，立即收起两窗
        if (!hazardDualEnabled && evtTurnLeft && evtTurnRight) {
            serviceLog("双闪联动已关闭，收起当前双闪双侧影像");
            postTurnEvent(null, false);
        }
    }

    /** v1.54 beta4：焦点抑制已停用，抑制判定方法移除（不再判断原厂相机前台）。 */

    /** SignalGate.Listener：500ms 慢变量快照回调（在轮询线程内触发，转主线程处理）。 */
    @Override
    public void onSignalTick(final SignalGate.Snapshot s) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (sideWarning != null) {
                    sideWarning.update(s, bsdShowAlways);
                }
                long nowMs = System.currentTimeMillis();
                if (s.gearLevel != -1) {
                    lastGearForRExit = s.gearLevel;
                }
                // R 挡进行中持续刷新抑制窗口：R 挡期间打灯/预警不弹窗，退出后仍保留一段抑制时间
                if (s.gearLevel == 3) {
                    rExitSuppressUntilMs = nowMs + rExitSuppressMs;
                }
                // 系统影像（窄路辅助）边沿：出现即整体收起两窗并释放设备/AVM，退出后按电平恢复
                boolean narrow = s.narrowAssistActive;
                if (narrow != narrowActive) {
                    narrowActive = narrow;
                    if (narrow) {
                        serviceLog("系统影像激活（窄路辅助），两窗整体让位并释放相机");
                        hideAllSlots();
                        if (cameraDevice != null) {
                            closeCameraOnly();
                        }
                        stopProjection();
                        resetAvmSendMemo();
                        avmModeActive = false;
                    } else {
                        serviceLog("系统影像退出，350ms 后按当前电平恢复浮窗");
                        mainHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                evaluateSlots("系统影像退出恢复");
                            }
                        }, 350L);
                    }
                }
                // BSD 预警电平投递：bsd_auto_overlay 关闭时预警不参与槽位
                boolean bsdL = bsdAutoOverlay && s.bsdLeft;
                boolean bsdR = bsdAutoOverlay && s.bsdRight;
                postWarnEvent(bsdL, bsdR);
                // 焦点解析日志（仅变化时打）
                if (s.focusPkg != null
                        && (!s.focusPkg.equals(lastFocusLogPkg) || s.focusHit != lastFocusLogHit)) {
                    lastFocusLogPkg = s.focusPkg;
                    lastFocusLogHit = s.focusHit;
                    serviceLog("焦点解析: pkg=" + s.focusPkg + " hit=" + s.focusHit);
                }
            }
        });
    }

    /** Camera1 回退钩子：Camera2 打开失败时若偏好 camera1_fallback=true 且纹理就绪，尝试 Camera1 预览。 */
    private void tryCamera1Fallback() {
        if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("camera1_fallback", false)) {
            return;
        }
        if (textureView == null || textureView.getSurfaceTexture() == null) {
            Log.e(TAG, "camera1 fallback: textureView not ready");
            return;
        }
        try {
            camera1Preview = new Camera1Preview(textureView.getSurfaceTexture(), BUFFER_W, BUFFER_H);
            if (camera1Preview.open()) {
                serviceLog("Camera2 打开失败，已回退 Camera1 预览（Phase 2 验证态）");
            } else {
                serviceLog("Camera1 回退失败，浮窗仅保留静态画面");
                camera1Preview = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "camera1 fallback error", e);
            camera1Preview = null;
        }
    }

    // ---- v1.54：BSD 预警自动弹窗（最低优先级） ----

    /** BSD 最低优先级抑制判定：打灯/其它方向浮窗激活中、手动浮窗显示中、R 挡倒车显示中、
     * 窄路辅助动态窗口在屏 → 一律抑制 BSD 自动弹窗（BSD 仅在这些更高优先级缺席时弹出）。 */
    private boolean shouldSuppressBsdAuto() {
        if (signalGate != null) {
            SignalGate.Snapshot s = signalGate.snapshot();
            if (s.narrowAssistActive) {
                serviceLog("BSD 自动弹窗：窄路辅助动态窗口在屏，抑制不弹浮窗");
                return true;
            }
            if (s.gearLevel == 3) {
                serviceLog("BSD 自动弹窗：R 挡倒车显示中（gearLevel=3），抑制不弹浮窗");
                return true;
            }
        }
        if (lastActiveDir != null && !"MANUAL".equals(lastActiveDir)) {
            serviceLog("BSD 自动弹窗：打灯/其它方向浮窗激活中（" + lastActiveDir + "），抑制不弹浮窗");
            return true;
        }
        if (manualVisible.get()) {
            serviceLog("BSD 自动弹窗：手动浮窗显示中，抑制不弹浮窗");
            return true;
        }
        return false;
    }

    /** BSD 边沿回调（主线程）：rising=true 为 SCU_BSD_*_WARNING 0->1 预警上升沿，false 为 1->0 熄灭沿。 */
    private void onBsdEdge(String dir, boolean rising) {
        if (!rising) {
            // 熄灭沿：若该侧浮窗由 BSD 弹起则关闭对应侧（沿用打灯去抖关闭逻辑）
            if (dir.equals(bsdAutoActiveDir)) {
                bsdAutoActiveDir = null;
                if (lastActiveDir != null && !"MANUAL".equals(lastActiveDir)) {
                    // 更高优先级（打灯/手动）已接管主浮窗，仅清 BSD 归属标记，不关浮窗
                    return;
                }
                // v1.67 beta6：预警熄灭与熄灯同属「本轮结束」，走延迟释放（窗口立即收）
                hideOverlayAndCamera(true);
                serviceLog("BSD 自动弹窗：BSD 预警熄灭，已关闭 " + dir + " 摄像头浮窗（相机延迟释放）");
            }
            if ("RIGHT".equals(dir) && bsdRightOverlayShowing) {
                hideBsdRightOverlay();
                serviceLog("BSD 自动弹窗：右侧预警熄灭，已关闭 BSD 右浮窗");
            }
            return;
        }
        // 上升沿：最低优先级抑制判定（打灯/倒车/窄路/手动占用时不弹）
        if (shouldSuppressBsdAuto()) {
            return;
        }
        if ("LEFT".equals(dir)) {
            // v1.56 beta1：右侧 BSD 主浮窗显示中（bsdAutoActiveDir=="RIGHT" 且相机就绪）时，
            // 先把右侧画面转为右独立窗显示，再执行左上升沿逻辑，实现左主浮窗+右独立窗双开，
            // 避免左上升沿覆盖 direction 导致右侧显示消失（先建右窗、后切左方向，右侧画面持续显示）。
            if ("RIGHT".equals(bsdAutoActiveDir)
                    && overlayView != null && cameraDevice != null) {
                showBsdRightOverlay();
                bsdAutoActiveDir = null;
                serviceLog("BSD 自动弹窗：左预警到来，右侧先转为 BSD 右独立窗显示，再切左主浮窗（双开）");
            }
            // 左预警：弹出左摄像头浮窗（等同打灯左浮窗逻辑）
            bsdAutoActiveDir = "LEFT";
            bsdAutoCalling = true;
            showOverlayForDirection("LEFT");
            bsdAutoCalling = false;
            // v1.54 beta3：BSD 预警弹窗边界加红色遮罩（示意预警状态，仅预警弹窗显示）
            showRedOverlayOn(overlayView);
            // v1.54 beta4：左预警应用用户编辑保存的 bsd_left_ 键（位置/大小/画面），未编辑跳过
            applyBsdLeftConfigIfPresent();
        } else {
            // 右预警：左右同时预警（BSD 双开）→ 弹独立只读右窗（避让公式）；
            // 仅右预警 → 弹出右摄像头主浮窗（等同打灯右浮窗逻辑）
            if (signalGate != null && signalGate.snapshot().bsdLeft) {
                showBsdRightOverlay();
            } else {
                bsdAutoActiveDir = "RIGHT";
                bsdAutoCalling = true;
                showOverlayForDirection("RIGHT");
                bsdAutoCalling = false;
                // v1.54 beta3：仅右预警（主浮窗）同样加红色遮罩
                showRedOverlayOn(overlayView);
            }
        }
    }

    /** 开关关闭/服务清理时释放由 BSD 弹起的全部浮窗（右窗无条件移除，主窗仅当归属 BSD 时关闭）。 */
    private void releaseBsdAutoOverlay() {
        hideBsdRightOverlay();
        if (bsdAutoActiveDir != null) {
            bsdAutoActiveDir = null;
            if (lastActiveDir == null || "MANUAL".equals(lastActiveDir)) {
                hideOverlayAndCamera();
            }
        }
    }

    /**
     * v1.56 beta3：窄路辅助窗口出现边沿——主动收起已显示浮窗。
     * 1) 打灯浮窗（lastActiveDir 非 MANUAL）：记录恢复快照后经 hideOverlayAndCamera 收起并清
     *    avmModeActive；收起前若处于 AVM 绑定路径（avmModeActive==true，已下发 display-mode=8）
     *    则补发 sendAvmMode(8) 恢复 360 全景默认视角。
     * 2) BSD 主浮窗（bsdAutoActiveDir 非 null）：清归属后同样 hideOverlayAndCamera 收起；BSD 主浮窗
     *    实际走 AVM 绑定路径（getAvmBinding 恒返回 8），按 avmModeActive 判断是否补发 mode=8；
     *    物理预览路径（avmModeActive==false）不涉 AVM mode，无需发送。
     * 3) BSD 右独立窗：调 hideBsdRightOverlay 单独收起（复用 overlay_window 布局，不涉 AVM mode）。
     * 手动浮窗（MANUAL）为用户主动调试，不在自动收起范围。
     */
    private void collectNarrowAssistOverlays() {
        serviceLog("窄路辅助窗口出现，主动收起已显示浮窗");
        boolean anyTurn = lastActiveDir != null && !"MANUAL".equals(lastActiveDir);
        boolean anyBsdMain = bsdAutoActiveDir != null;
        if (anyTurn) {
            narrowTurnDirSnapshot = lastActiveDir;
            narrowTurnStillOn = true;
        } else {
            narrowTurnDirSnapshot = null;
            narrowTurnStillOn = false;
        }
        boolean wasAvm = avmModeActive;
        if (anyTurn || anyBsdMain) {
            if (anyBsdMain) {
                bsdAutoActiveDir = null;
            }
            hideOverlayAndCamera();
            if (wasAvm) {
                sendAvmMode(8);
                serviceLog("窄路辅助收起：浮窗曾绑定 AVM 视角，恢复 display-mode=8（360 全景默认）");
            } else {
                serviceLog("窄路辅助收起：浮窗为物理预览路径，无需下发 AVM mode");
            }
        }
        if (bsdRightOverlayShowing) {
            hideBsdRightOverlay();
            serviceLog("窄路辅助收起：BSD 右独立窗已收起");
        }
    }

    /**
     * v1.56 beta3：窄路辅助窗口消失边沿——解除抑制后按当前状态恢复浮窗。
     * 1) 打灯信号仍亮（窄路期间未收到熄灭回调）且记录过方向 → 恢复打灯浮窗：
     *    showOverlayForDirection(dir) 内 AVM 绑定块自动 sendAvmMode(8)（绑定视角即 360 全景默认），
     *    红框由 syncTurnBsdRedOverlay 同步；若打灯已熄灭则窄路期间抑制的新亮起方向已由
     *    onSignalChanged 快照接续，同样可恢复。
     * 2) BSD 对应侧信号仍为 1（有车）→ 按当前电平直接恢复弹窗（复用 onBsdEdge 上升沿，
     *    含双开/左右逻辑与红色遮罩；窄路期间上升沿被 shouldSuppressBsdAuto 抑制后无新沿，
     *    必须按电平补沿）。打灯已恢复时 onBsdEdge 内部打灯占用抑制自然让位。
     * 恢复完成后进入 2s 防抖窗口（调用方设置 narrowExitSuppressUntilMs），窗口内窄路再现不重复收起。
     */
    private void restoreAfterNarrowExit(SignalGate.Snapshot s) {
        serviceLog("窄路辅助窗口消失，按当前状态恢复浮窗");
        // 1) 打灯仍亮：恢复打灯浮窗（AVM 绑定路径自动下发 display-mode=8）
        if (narrowTurnStillOn && narrowTurnDirSnapshot != null) {
            String dir = narrowTurnDirSnapshot;
            serviceLog("窄路恢复：打灯信号仍亮（" + dir + "），恢复打灯浮窗 + display-mode=8");
            showOverlayForDirection(dir);
        }
        // 2) BSD 侧仍 1：按当前电平恢复弹窗（左亮恢复左主浮窗；右亮按双开/左右逻辑恢复）
        if (s.bsdLeft) {
            serviceLog("窄路恢复：BSD 左侧信号仍为 1，按当前电平恢复弹窗");
            onBsdEdge("LEFT", true);
        }
        if (s.bsdRight) {
            serviceLog("窄路恢复：BSD 右侧信号仍为 1，按当前电平恢复弹窗");
            onBsdEdge("RIGHT", true);
        }
        // 消耗本 tick 刚出现的 BSD 上升沿（若有），避免紧随其后的 BSD 边沿检测块重复弹窗；
        // 正常无新沿时赋值与已有状态一致，无副作用。
        lastBsdLeft = s.bsdLeft;
        lastBsdRight = s.bsdRight;
        // 清理恢复快照
        narrowTurnDirSnapshot = null;
        narrowTurnStillOn = false;
    }

    // =====================================================================
    // v1.58 双槽位统一模型：优先窗 P / 次级窗 S
    //   · 每侧（LEFT/RIGHT）最多一个有效事件：打灯 > 预警
    //   · 先触发者占 P 窗，后触发者占 S 窗；同一刻到达左优先
    //   · 任一侧事件消失后重新分配，S 窗内容可前移至 P 窗
    //   · 两窗常驻（addView 一次），隐藏即移出屏幕 + alpha=0，Surface 保活零重建
    // =====================================================================

    private static final String EVT_TURN = "TURN";
    private static final String EVT_WARN = "WARN";
    private static final int SLOT_HIDDEN_X = -30000;
    private static final int SLOT_HIDDEN_Y = -30000;

    private boolean evtTurnLeft = false, evtTurnRight = false;
    private boolean evtWarnLeft = false, evtWarnRight = false;
    private long evtSeqLeft = 0L, evtSeqRight = 0L;
    private long evtSeqGen = 0L;

    private String slotPSide = null, slotPType = null;
    private String slotSSide = null, slotSType = null;
    private boolean slotPShown = false, slotSShown = false;

    private boolean slotEditActive = false;
    private String slotEditSide = "LEFT";
    private String slotEditSlot = null;
    private BsdEditWindow slotEditP = null;
    private BsdEditWindow slotEditS = null;

    private String lastAvmLeadSide = null;
    private int lastSentAvmMode = -1;

    /** 系统影像（窄路辅助等）是否在屏：在屏期间两窗整体让位。 */
    private boolean narrowActive = false;

    private static boolean sameStr(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String normSide(String side) {
        return "RIGHT".equals(side) ? "RIGHT" : "LEFT";
    }

    private boolean evtTurnOf(String side) {
        return "RIGHT".equals(side) ? evtTurnRight : evtTurnLeft;
    }

    private boolean evtWarnOf(String side) {
        return "RIGHT".equals(side) ? evtWarnRight : evtWarnLeft;
    }

    private long evtSeqOf(String side) {
        return "RIGHT".equals(side) ? evtSeqRight : evtSeqLeft;
    }

    /** 该侧当前有效事件类型：打灯优先于预警；无事件返回 null。 */
    private String evtTypeOf(String side) {
        if (evtTurnOf(side)) return EVT_TURN;
        if (evtWarnOf(side)) return EVT_WARN;
        return null;
    }

    /** 打灯电平投递（onSignalChanged 主线程回调）。anyOn=false 表示两侧灯全灭。 */
    private void postTurnEvent(String dir, boolean anyOn) {
        if (manualVisible.get()) {
            serviceLog("手动浮窗在屏，打灯接管：先收起手动浮窗");
            manualVisible.set(false);
            direction = null;
            removeOverlay();
        }
        boolean l = anyOn && "LEFT".equals(dir);
        boolean r = anyOn && "RIGHT".equals(dir);
        boolean changed = (l != evtTurnLeft) || (r != evtTurnRight);
        evtTurnLeft = l;
        evtTurnRight = r;
        if (changed) {
            serviceLog("打灯电平更新 L=" + l + " R=" + r);
        }
        evaluateSlots("打灯 " + (anyOn ? ("LEFT".equals(dir) ? "左亮" : "右亮") : "全灭"));
    }

    /** v1.65 beta1：双闪双侧出图投递——左右两侧同时置位为打灯事件，
     *  由 evaluateSlots 统一分配：左侧进优先窗 P、右侧进次级窗 S，两窗同时出来。
     *  两侧同一轮进入，refreshSeq 分配同一序号，leftFirst 判定使左优先，与需求一致。 */
    private void postTurnEventBilateral() {
        if (manualVisible.get()) {
            serviceLog("手动浮窗在屏，双闪接管：先收起手动浮窗");
            manualVisible.set(false);
            direction = null;
            removeOverlay();
        }
        boolean changed = !evtTurnLeft || !evtTurnRight;
        evtTurnLeft = true;
        evtTurnRight = true;
        if (changed) {
            serviceLog("打灯电平更新（双闪）L=true R=true");
        }
        evaluateSlots("打灯 双闪");
    }

    /** BSD 预警电平投递（onSignalTick 500ms 慢变量回调）。 */
    private void postWarnEvent(boolean l, boolean r) {
        boolean changed = (l != evtWarnLeft) || (r != evtWarnRight);
        evtWarnLeft = l;
        evtWarnRight = r;
        if (changed) {
            serviceLog("BSD 预警电平更新 L=" + l + " R=" + r);
        }
        evaluateSlots("预警 L=" + l + " R=" + r);
    }

    /** 侧事件序号：侧进入活跃时分配新序号，退出清零；两侧同一时刻进入给同一序号（左优先兜底）。 */
    private void refreshSeq() {
        boolean lAct = evtTypeOf("LEFT") != null;
        boolean rAct = evtTypeOf("RIGHT") != null;
        if (lAct && rAct && evtSeqLeft == 0L && evtSeqRight == 0L) {
            long same = ++evtSeqGen;
            evtSeqLeft = same;
            evtSeqRight = same;
            return;
        }
        if (lAct && evtSeqLeft == 0L) evtSeqLeft = ++evtSeqGen;
        if (!lAct) evtSeqLeft = 0L;
        if (rAct && evtSeqRight == 0L) evtSeqRight = ++evtSeqGen;
        if (!rAct) evtSeqRight = 0L;
    }

    /** 统一分配入口：任何电平变化都走这里重算 P/S 两窗内容。 */
    private void evaluateSlots(String reason) {
        if (slotEditActive) {
            serviceLog("浮窗位置编辑中，暂不刷新槽位（" + reason + "）");
            return;
        }
        refreshSeq();
        String lType = evtTypeOf("LEFT");
        String rType = evtTypeOf("RIGHT");
        // 抑制条件：系统影像在屏 / R 挡进行中或刚退出抑制窗口 —— 期间一律不弹窗
        long nowMs = System.currentTimeMillis();
        if (narrowActive || nowMs < rExitSuppressUntilMs) {
            if (lType != null || rType != null) {
                serviceLog("槽位抑制中（" + (narrowActive ? "系统影像在屏" : "R挡抑制窗口") + "），暂不弹窗");
            }
            lType = null;
            rType = null;
        }
        String pSide = null, pType = null, sSide = null, sType = null;
        if (lType != null && rType != null) {
            boolean leftFirst = evtSeqLeft <= evtSeqRight;
            pSide = leftFirst ? "LEFT" : "RIGHT";
            pType = leftFirst ? lType : rType;
            sSide = leftFirst ? "RIGHT" : "LEFT";
            sType = leftFirst ? rType : lType;
        } else if (lType != null) {
            pSide = "LEFT";
            pType = lType;
        } else if (rType != null) {
            pSide = "RIGHT";
            pType = rType;
        }
        applyAssignment(pSide, pType, sSide, sType, reason);
    }

    private void applyAssignment(String pSide, String pType, String sSide, String sType, String reason) {
        boolean pChanged = !sameStr(slotPSide, pSide) || !sameStr(slotPType, pType);
        boolean sChanged = !sameStr(slotSSide, sSide) || !sameStr(slotSType, sType);
        String prevP = slotPSide == null ? "空" : (slotPSide + "/" + slotPType);
        String prevS = slotSSide == null ? "空" : (slotSSide + "/" + slotSType);
        slotPSide = pSide;
        slotPType = pType;
        slotSSide = sSide;
        slotSType = sType;
        if (pSide == null) {
            hideSlotWindow("P");
        } else if (pChanged || !slotPShown) {
            renderSlot("P");
        } else {
            syncSlotRedFrame("P", slotHost("P"), pSide, pType);
        }
        if (sSide == null) {
            hideSlotWindow("S");
        } else if (sChanged || !slotSShown || bsdRightOverlayView == null) {
            renderSlot("S");
        } else {
            syncSlotRedFrame("S", slotHost("S"), sSide, sType);
        }
        updateCameraForSlots();
        if (pChanged || sChanged) {
            serviceLog("槽位分配[" + reason + "] P:" + prevP + "→" + (pSide == null ? "空" : (pSide + "/" + pType))
                    + " S:" + prevS + "→" + (sSide == null ? "空" : (sSide + "/" + sType)));
        }
    }

    /** 相机与会话：按优先窗所在侧决定画面源；两窗共用同一会话。 */
    private void updateCameraForSlots() {
        String leadSide = slotPSide != null ? slotPSide : slotSSide;
        // v1.67 beta6：延迟窗口内再次出现活跃槽位——说明这次是「熄灯后很快又打灯」，
        // 相机与会话都还在（未走到 releaseCamera），只需取消释放计时并按需重新 attach 输出。
        boolean lingerReuse = cameraLingerActive;
        if (leadSide != null && lingerReuse) {
            cameraLingerReuseCount++;
            cancelCameraLinger();
            serviceLog("延迟窗口内复触发（lead=" + leadSide + "）：取消释放计时，复用已就绪的相机与会话");
        }
        // v1.60_beta4：本次打灯/预警属于重新拉起（上一次无活跃槽位）时，
        // 清空 display-mode 下发记忆，保证本次一定重发一次 display-mode=8，
        // 并作废会话签名让输出目标按最新可见槽位重建。
        boolean relaunch = leadSide != null && lastAvmLeadSide == null;
        if (relaunch) {
            resetAvmSendMemo();
        }
        syncAvmMode(leadSide);
        if (leadSide == null) {
            // v1.58：无活跃槽位时的收尾必须幂等且零噪音——
            // 相机已释放、投影未运行时直接返回，避免 500ms 慢变量回调反复空转。
            boolean hadDevice = cameraDevice != null;
            if (hadDevice) {
                if (canLingerCamera()) {
                    // v1.67 beta6：能留就留（已在计时则幂等，不续期）
                    scheduleCameraLinger();
                } else {
                    // 窄路辅助/系统影像在屏、R 挡抑制窗口、编辑模式、手动窗、总开关已关：
                    // 一律立即释放，并作废在途的延迟计时，避免占着摄像头挡住 AVM
                    closeCameraOnly();
                }
            }
            boolean projRunning = iclProjectionEnabled && icProjector.isRunning();
            if (hadDevice || projRunning) {
                stopProjection();
            }
            return;
        }
        if (cameraDevice == null) {
            openCamera();
        } else {
            if (relaunch) {
                if (lingerReuse && LINGER_REUSE_SESSION) {
                    serviceLog("打灯/预警重新拉起（延迟窗口内）：已重下发 display-mode，会话仍有效不重建（lead="
                            + leadSide + "）");
                } else {
                    sessionTargetSig = "";
                    serviceLog("打灯/预警重新拉起：已重下发 display-mode 并强制重建预览会话（lead=" + leadSide + "）");
                }
            }
            requestSessionRebuild(0L);
        }
        startProjectionIfEnabled();
    }

    /** display-mode 只在首次出现 / 内容切换 / 外部干扰结束时下发一次，持续期间零下发。 */
    private void syncAvmMode(String leadSide) {
        if (leadSide == null) {
            if (lastSentAvmMode != -1) {
                serviceLog("AVM 下发复位：无活跃槽位");
            }
            lastSentAvmMode = -1;
            lastAvmLeadSide = null;
            avmModeActive = false;
            return;
        }
        avmModeActive = true;
        boolean sideChanged = !sameStr(leadSide, lastAvmLeadSide);
        if (!sideChanged && lastSentAvmMode == 8) {
            return;
        }
        lastAvmLeadSide = leadSide;
        lastSentAvmMode = 8;
        sendAvmMode(8);
        serviceLog("AVM 下发 display-mode=8（lead=" + leadSide + "）");
    }

    /** 外部干扰结束（系统影像退出等）时清空下发记忆，下次重新下发一次。 */
    private void resetAvmSendMemo() {
        lastSentAvmMode = -1;
        lastAvmLeadSide = null;
    }

    /** 槽位窗口是否已 addView。 */
    private View slotHost(String slot) {
        return "P".equals(slot) ? overlayView : bsdRightOverlayView;
    }

    private TextureView slotTexture(String slot) {
        return "P".equals(slot) ? textureView : bsdRightTextureView;
    }

    private TextView slotLabelView(String slot) {
        return "P".equals(slot) ? labelView : bsdRightLabelView;
    }

    private String geoPrefixOf(String slot) {
        return "P".equals(slot) ? "pa_" : "pb_";
    }

    private String framePrefixOf(String slot, String side) {
        return geoPrefixOf(slot) + ("RIGHT".equals(side) ? "r_" : "l_");
    }

    /** v1.60_beta1：当前 P 窗画面配置前缀，与 BsdEditWindow 保存的「窗×侧」键一致。 */
    private String curFramePrefix() {
        String side = slotPSide != null ? slotPSide : normSide(direction);
        return framePrefixOf("P", side);
    }

    /** 读取槽位几何（左右状态共用）：返回 {w,h,x,y}。 */
    private int[] readSlotGeo(String slot, DisplayMetrics dm) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String p = geoPrefixOf(slot);
        int defW = (int) (dm.widthPixels * OVERLAY_DEF_W_RATIO);
        int defH = (int) (defW * 9f / 16f);
        int w = prefs.getInt(p + "overlay_w", defW);
        int h = prefs.getInt(p + "overlay_h", defH);
        int minW = (int) (dm.widthPixels * MIN_SIZE_RATIO);
        int minH = (int) (dm.heightPixels * MIN_SIZE_RATIO);
        if (w < minW) w = minW;
        if (w > dm.widthPixels) w = dm.widthPixels;
        if (h < minH) h = minH;
        if (h > dm.heightPixels) h = dm.heightPixels;
        int defX;
        if ("P".equals(slot)) {
            // v1.60：优先窗默认位置改为水平居中，不再默认贴左（x=120）
            defX = Math.max(dp(24), (dm.widthPixels - w) / 2);
        } else {
            defX = 120 + defW + dp(BSD_DUAL_GAP_DP);
            if (defX + w > dm.widthPixels) {
                defX = Math.max(16, dm.widthPixels - w - 120);
            }
        }
        int defY = dm.heightPixels - h - 120;
        // v1.60：位置一律以 overlay_pos_recorded 标记为准。未编辑过的槽位忽略存储残值
        // （旧迁移 migrateV158 曾把 position 无条件写成 0，导致浮窗贴屏幕最左）。
        boolean rec = prefs.getBoolean(p + "overlay_pos_recorded", false);
        // v1.60_beta4：位置双层兜底。recorded 标记在某些路径下会被漏置（历史迁移/旧版本残留），
        // 导致明明拖好过、却每次打灯都读回默认值（用户感知为"位置自己变回左下角"）。
        // 这里在 recorded 缺失时回退到 last_ 记录——它由"任意一次真实可见的保存"写入，
        // 不含任何默认值，只有真正保存过才存在。
        int x;
        int y;
        if (rec) {
            x = prefs.getInt(p + "overlay_pos_x", defX);
            y = prefs.getInt(p + "overlay_pos_y", defY);
        } else if (prefs.contains(p + "last_pos_x") && prefs.contains(p + "last_pos_y")) {
            x = prefs.getInt(p + "last_pos_x", defX);
            y = prefs.getInt(p + "last_pos_y", defY);
        } else {
            x = defX;
            y = defY;
        }
        // v1.60_beta3：这里过去用 `x <= 0 || y <= 0` 判非法并回退默认值，而 snapToNearestEdge
        // 的"上吸附线"正是 y=0、"左吸附线"是 screenW*0.08——用户把优先窗拖到左上角，
        // y 被吸附成 0，再读回来就被当成非法值 → 回退 defY = 屏高-h-120（屏幕底部），
        // 于是"放左上角自动弹回左下角"。现在只把「隐藏坐标(-30000)/负数/出屏」当非法，
        // 合法区间内一律 clamp，贴边保存的位置原样保留。
        int maxX = Math.max(0, dm.widthPixels - w);
        int maxY = Math.max(0, dm.heightPixels - h);
        int badMin = -dp(4);
        // 只要落在屏幕内就按 clamp 保留（允许窗体贴着右/下边缘、甚至略微压边），
        // 只有完全出屏/隐藏坐标才认为这条记录作废。
        // v1.67_beta7：只有真正的隐藏坐标/完全出屏才作废回退默认；用户拖到左上角的轻微越界
        // （负几十像素，旧版本已存进 prefs 的脏数据）一律 clamp 到 0 保留，不再弹回屏底默认值。
        if (x <= SLOT_HIDDEN_X || x > dm.widthPixels) x = defX;
        if (y <= SLOT_HIDDEN_Y || y > dm.heightPixels) y = defY;
        x = Math.min(Math.max(x, 0), maxX);
        y = Math.min(Math.max(y, 0), maxY);
        return new int[] {w, h, x, y};
    }

    /** 应用槽位几何（位置 + 大小）。 */
    private void applySlotGeometry(String slot, View host) {
            if (host == null) return;
            // v1.59：隐藏态若把几何写回屏幕坐标，窗口又会变回"alpha=0 却吃触摸"的隐形浮窗。
            if (!("P".equals(slot) ? slotPShown : slotSShown)) return;
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            int[] geo = readSlotGeo(slot, dm);
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) host.getLayoutParams();
            lp.width = geo[0];
            lp.height = geo[1];
            lp.x = geo[2];
            lp.y = geo[3];
            if ("P".equals(slot)) {
                lastPGeom = new int[] {geo[0], geo[1], geo[2], geo[3]};
            }
            try {
                wm.updateViewLayout(host, lp);
                // v1.60_beta4：位置排查日志——记录本次实际应用到的几何与来源键
                serviceLog("槽位几何[" + slot + "] w=" + lp.width + " h=" + lp.height
                        + " pos=(" + lp.x + "," + lp.y + ") prefix=" + geoPrefixOf(slot));
            } catch (Exception e) {
                Log.e(TAG, "updateViewLayout slot=" + slot + " failed", e);
            }
        }

    /**
     * 显示槽位窗口。
     *
     * <p>v1.67 beta5：**取消"隐藏态"**。v1.58 起窗口改成常驻、隐藏时移出屏幕
     * (-30000,-30000) + alpha=0 + FLAG_NOT_TOUCHABLE 来保活 Surface，这正是
     * 「主窗偶发卡死、探针零记录、重开总开关才恢复」的根因：窗口离屏且透明后
     * 合成器不再消费该 Layer 的 buffer，一旦隐藏期间 Layer 被重建，TextureView 与
     * 新 Layer 的绑定断开，relay 侧 swap 仍次次成功、也没有任何 Surface 销毁回调，
     * 画面就永久定格在最后一帧。故此处只保留「确保可见」一条语义，
     * 隐藏一律走 hideSlotWindow() 销毁窗口。
     */
    private void setSlotVisible(String slot, View host, boolean visible) {
            if (host == null) return;
            if (!visible) {
                // 兜底：任何遗留的「隐藏」调用都转成销毁，绝不留离屏常驻窗口
                hideSlotWindow(slot);
                return;
            }
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) host.getLayoutParams();
            host.setAlpha(1f);
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                wm.updateViewLayout(host, lp);
            } catch (Exception ignored) {
            }
            if ("P".equals(slot)) {
                slotPShown = true;
            } else {
                slotSShown = true;
            }
            // v1.65：显隐直接决定这一路该不该占 GL 输出，改完就对账，
            // 不要等下一次打灯触发才刷新（那次也可能被签名去抖拦下）
            refreshRelayOutputs();
        }

    /** 两窗全部让位（系统影像激活 / 熄灯 / 关总开关）：销毁窗口，不留离屏常驻窗。 */
    private void hideAllSlots() {
        hideSlotWindow("P");
        hideSlotWindow("S");
    }

    /**
     * 隐藏槽位窗口 = 连 Surface 带 View 一起释放。
     *
     * <p>v1.67 beta5 回退 v1.57 行为。常驻隐藏（离屏 + alpha=0）会让 Layer 失去合成
     * 消费者，隐藏期间 Layer 一旦被重建，TextureView 与新 Layer 的绑定即断裂，
     * 表现为画面永久定格、relay 无异常、无回调。销毁重建虽多花一次 addView 与会话重建的
     * 几百毫秒，但不会卡死，且这是 v1.57 及之前二十多个版本一直稳定的做法。</p>
     */
    private void hideSlotWindow(final String slot) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler h = mainHandler != null ? mainHandler : pProbeHandler;
            h.post(new Runnable() {
                @Override
                public void run() {
                    hideSlotWindow(slot);
                }
            });
            return;
        }
        View host = slotHost(slot);
        if (host != null) {
            hideRedOverlayOn(host);
        }
        if ("P".equals(slot)) {
            removeOverlay();          // 内部：detachOutput("P") + removeView + 置 null + slotPShown=false
        } else {
            destroySecondaryWindow(true);
        }
        refreshRelayOutputs();
    }

    /** 渲染一个槽位：几何 → 可见 → 画面矩阵 → 标签 → 红框。 */
    private void renderSlot(String slot) {
        String side = "P".equals(slot) ? slotPSide : slotSSide;
        String type = "P".equals(slot) ? slotPType : slotSType;
        if (side == null) {
            hideSlotWindow(slot);
            return;
        }
        if ("P".equals(slot)) {
            if (overlayView == null) {
                // v1.58：ensureOverlay 内部按 direction 是否 MANUAL 选择几何键，
                // 这里临时借用 side 走槽位几何分支，返回后必须还原，防止污染原方向状态。
                String keep = direction;
                direction = side;
                ensureOverlay();
                direction = keep;
                if (overlayView == null) {
                    return;
                }
            }
        } else {
            if (bsdRightOverlayView == null) {
                ensureSecondaryWindow();
                if (bsdRightOverlayView == null) return;
            }
        }

        View host = slotHost(slot);
        if (host == null) return;
        // v1.59：顺序反转——几何写入受隐藏态守卫，必须先让槽位回到可见态
        setSlotVisible(slot, host, true);
        applySlotGeometry(slot, host);

        applySlotTransform(slot, side);
        updateSlotLabel(slot, side, type);
        syncSlotRedFrame(slot, host, side, type);
    }

    /** 槽位标签文案。 */
    private void updateSlotLabel(String slot, String side, String type) {
        TextView tv = slotLabelView(slot);
        if (tv == null) return;
        boolean isLeft = "LEFT".equals(side);
        if (EVT_TURN.equals(type)) {
            tv.setText(isLeft ? "左转向 · AVM 视角" : "右转向 · AVM 视角");
        } else {
            tv.setText(isLeft ? "BSD 左预警" : "BSD 右预警");
        }
    }

    /** 转向同侧预警时该侧加红框；预警独立成画面时无红框。 */
    private void syncSlotRedFrame(String slot, View host, String side, String type) {
        if (host == null) return;
        boolean want = bsdRedOverlay && EVT_TURN.equals(type) && evtWarnOf(side);
        boolean has = host.findViewWithTag("bsd_red_overlay") != null;
        if (want && !has) {
            showRedOverlayOn(host);
            serviceLog("BSD 联动红框[" + slot + "]：" + ("LEFT".equals(side) ? "左" : "右") + "侧有车，叠加呼吸红框");
        } else if (!want && has) {
            hideRedOverlayOn(host);
            serviceLog("BSD 联动红框[" + slot + "]：移除红框");
        }
    }

    /** P 窗触摸：双击进入本窗当前侧的画面编辑（位置编辑统一在设置页做）。 */
    private void setupSlotPInteraction(final View view) {
        if (view == null) return;
        view.setOnTouchListener(new View.OnTouchListener() {
            private long lastTap = 0L;

            @Override
            public boolean onTouch(View v, MotionEvent event) {

                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // v1.59：隐藏槽位一律不响应双击（防御性兜底，正常情况下 NOT_TOUCHABLE 已拦掉）
                if (!slotPShown) {
                lastTap = 0L;
                return false;
                }
                long now = System.currentTimeMillis();
                if (now - lastTap < 400L) {
                lastTap = 0L;
                String tapSide = slotPSide == null ? normSide(direction) : slotPSide;
                if (tapSide == null) return true;
                openSlotEditor("P", tapSide, false);
                return true;
                }
                lastTap = now;
                }

                return false;
            }
        });
    }

    /** S 窗触摸：双击进入本窗当前侧的画面编辑。 */
    private void setupSlotSInteraction(final View view) {
        if (view == null) return;
        view.setOnTouchListener(new View.OnTouchListener() {
            private long lastTap = 0L;

            @Override
            public boolean onTouch(View v, MotionEvent event) {

                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // v1.59：隐藏槽位一律不响应双击
                if (!slotSShown) {
                lastTap = 0L;
                return false;
                }
                long now = System.currentTimeMillis();
                if (now - lastTap < 400L) {
                lastTap = 0L;
                String tapSide = slotSSide == null ? "RIGHT" : slotSSide;
                openSlotEditor("S", tapSide, false);
                return true;
                }
                lastTap = now;
                }

                return false;
            }
        });
    }

    /** v1.54：BSD 双开时右侧独立只读浮窗——位置固定为紧挨左浮窗预设位置的右侧
     * （rightX = leftX + 左浮窗宽 + 8dp），无论左浮窗是否实际出现均按该公式摆放；
     * 不参与拖动/编辑（复用 overlay_window 布局，隐藏关闭按钮）。 */
    /** v1.58：次级窗 S——窗口只创建一次并常驻（addView 不重复），内容由槽位分配驱动。
     * 旧 v1.54 的"紧挨左窗固定位置"逻辑已废弃，位置改由 pb_ 几何键统一管理（设置页可拖拽）。 */
    private void showBsdRightOverlay() {
        ensureSecondaryWindow();
        renderSlot("S");
    }

    /** 创建次级窗 S（幂等）。窗口常驻，此后仅做移屏隐藏/移回显示，Surface 与预览会话全程存活。 */
    private void ensureSecondaryWindow() {
        if (bsdRightOverlayView != null) return;
        try {
            LayoutInflater li = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            bsdRightOverlayView = li.inflate(R.layout.overlay_window, null);
            bsdRightTextureView = bsdRightOverlayView.findViewById(R.id.overlay_texture);
            if (bsdRightTextureView != null) {
                bsdRightTextureView.setClipToOutline(true);
                bsdRightTextureView.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(24));
                    }
                });
                bsdRightTextureView.setSurfaceTextureListener(bsdRightSurfaceListener);
            }
            bsdRightLabelView = bsdRightOverlayView.findViewById(R.id.overlay_label);
            if (bsdRightLabelView != null) bsdRightLabelView.setText("次级窗");
            View closeBtn = bsdRightOverlayView.findViewById(R.id.overlay_close);
            if (closeBtn != null) closeBtn.setVisibility(View.GONE);
            View border = bsdRightOverlayView.findViewById(R.id.overlay_border);
            if (border != null) border.setVisibility(View.GONE);
            View hint = bsdRightOverlayView.findViewById(R.id.overlay_edit_hint);
            if (hint != null) hint.setVisibility(View.GONE);
            hideEdgeHandles(bsdRightOverlayView);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            int[] geo = readSlotGeo("S", dm);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    geo[0], geo[1],
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);

            lp.gravity = Gravity.TOP | Gravity.START;
            // v1.67 beta5：窗口不再常驻，创建即用于显示，直接落最终几何并可见。
            // 旧的「创建就移屏外 + alpha=0 保活」正是主窗/S 窗定格卡死的来源。
            lp.x = geo[2];
            lp.y = geo[3];
            bsdRightOverlayView.setAlpha(1f);
            wm.addView(bsdRightOverlayView, lp);
            slotSShown = true;

            setupSlotSInteraction(bsdRightOverlayView);
            applySlotTransform("S", slotSSide == null ? "RIGHT" : slotSSide);
            serviceLog("次级窗已创建并常驻 w=" + geo[0] + " h=" + geo[1] + " pos=(" + geo[2] + "," + geo[3] + ")");
        } catch (Exception e) {
            Log.e(TAG, "addView secondary overlay failed", e);
            bsdRightOverlayView = null;
            bsdRightTextureView = null;
            bsdRightLabelView = null;
            slotSShown = false;
        }
    }

    /** 隐藏布局内的四边/四角编辑手柄（次级窗不直接编辑，编辑统一走编辑窗）。 */
    private void hideEdgeHandles(View root) {
        if (root == null) return;
        int[] ids = new int[] {R.id.handle_left, R.id.handle_right, R.id.handle_top,
                R.id.handle_bottom, R.id.handle_tl, R.id.handle_tr, R.id.handle_bl, R.id.handle_br};
        for (int id : ids) {
            View v = root.findViewById(id);
            if (v != null) v.setVisibility(View.GONE);
        }
    }

    /** 移除 BSD 右浮窗并释放其 surface；主会话存活时重建预览会话去掉右窗 target。 */
    /** v1.58：隐藏次级窗——窗口与 Surface 保活，仅移出屏幕 + alpha=0，不再 removeView/release。 */
    private void hideBsdRightOverlay() {
        bsdRightOverlayShowing = false;
        if (bsdRightOverlayView == null) return;
        hideSlotWindow("S");
        serviceLog("次级窗已隐藏（窗口常驻，Surface 保活）");
    }

    /**
     * v1.67 beta4：真正销毁次级窗（关闭浮窗总开关 / 服务销毁时必须调用）。
     *
     * <p>历史遗留缺陷：整条链路只有 hideSlotWindow 把 S 窗移出屏幕，从未 removeView。
     * 一旦服务实例被 stopSelf 销毁再重建，旧 S 窗就成了没有任何引用的孤儿浮窗：
     * 新实例 bsdRightOverlayView=null 会再建一个，旧的永久留在屏上关不掉。
     * 取消总开关时的现象即「次级窗不关闭，之后一直关不掉」。</p>
     */
    private void destroySecondaryWindow() {
        destroySecondaryWindow(false);
    }

    /**
     * v1.67 beta5：keepSide=true 用于「打灯结束收起次级窗」——窗口照常销毁，但保留
     * 当前是哪一侧/哪种类型，下次同侧打灯直接按原几何重建，无需等下一轮信号重新决策。
     */
    private void destroySecondaryWindow(boolean keepSide) {
        bsdRightOverlayShowing = false;
        View v = bsdRightOverlayView;
        if (v == null) {
            slotSShown = false;
            if (!keepSide) {
                slotSSide = null;
                slotSType = null;
            }
            return;
        }
        // GL 中转模式下先摘 EGLSurface，避免 relay 继续往已销毁的纹理提交帧。
        // 传 null 只摘输出不 release，纹理由 removeView 触发的 onSurfaceTextureDestroyed
        // 统一释放（传 st 会在此处 release，与销毁回调叠加成双重释放）。
        if (glRelay != null) {
            try {
                glRelay.detachOutput("S", null);
            } catch (Throwable ignored) {
            }
        }
        hideRedOverlayOn(v);
        try {
            wm.removeView(v);
        } catch (Exception ignored) {
        }
        bsdRightOverlayView = null;
        bsdRightTextureView = null;
        bsdRightLabelView = null;
        slotSShown = false;
        if (!keepSide) {
            slotSSide = null;
            slotSType = null;
        }
        refreshRelayOutputs();
        serviceLog("次级窗已销毁（窗口已从 WindowManager 移除）");
    }

    /**
     * v1.67 beta4：停止服务时把打灯/槽位状态整体复位。
     *
     * <p>否则重开总开关后 evtTurnLeft/evtTurnRight 仍为 true，SignalMonitor 认为
     * 「电平没变化」不再回调，于是双闪重开既不出窗、也收不回窗。</p>
     */
    private void resetSlotStateOnStop() {
        evtTurnLeft = false;
        evtTurnRight = false;
        evtSeqLeft = 0L;
        evtSeqRight = 0L;
        slotPSide = null;
        slotPType = null;
        slotSSide = null;
        slotSType = null;
        slotPShown = false;
        slotSShown = false;
        closeSuppressedDir = null;
        lastAvmLeadSide = null;
        pStallRecorded = false;
        lastPUpdatedMs = 0L;
        pProbeAnchorMs = 0L;
        pProbeGateSince = 0L;
        pProbeGateLoggedAt = 0L;
    }

    /** BSD 右浮窗 TextureView 监听：surface 就绪后把右窗 target 加入预览会话。 */
    private final TextureView.SurfaceTextureListener bsdRightSurfaceListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
            st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
            // v1.54 beta3：右窗 surface 就绪后重放渲染矩阵（复用右转向灯画面源，与位置解耦）
            applyBsdRightRenderTransform();
            // v1.57 beta4：守卫只判自身就绪与相机存活——不再要求主窗可用，
            // 否则主窗纹理暂不可用时右窗永远拿不到画面（双开空框的另一诱因）
            // v1.69：同主窗——新纹理就绪后立刻回表（换纹理自愈后右窗空框的直接原因）
            refreshRelayOutputs();
            if (cameraDevice != null) {
                createPreviewSession();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
            // v1.54 beta3：右窗尺寸变化重放渲染矩阵
            applyBsdRightRenderTransform();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                        // v1.62：GL 中转模式下该纹理由 relay 作为输出端持有，先摘 EGLSurface 再释放
            if (glRelayMode && glRelay != null) {
                glRelay.detachOutput("S", st);
                requestSessionRebuild(0L);
                return false;
            }
// v1.57 beta5：与主窗一致——该纹理若仍是会话目标，由本端延后释放；
            // 直接交给系统释放会让同一路会话向死纹理提交帧，主窗一并冻结。
            if (bsdRightSurfaceTex != null && bsdRightSurfaceTex == st) {
                if (bsdRightSurface != null) {
                    pendingSurfaceRelease.add(bsdRightSurface);
                    bsdRightSurface = null;
                }
                pendingTexRelease.add(st);
                bsdRightSurfaceTex = null;
                // v1.60_beta2：不再把 bsdRightTextureView 置空。窗口/View 仍然存在，
                // 置空后 ensureSecondaryWindow 因 bsdRightOverlayView != null 直接返回，
                // 次级窗将永久失去纹理引用（黑窗），后续会话也永远少一路目标。
                if (hasAnyPreviewTarget()) {
                    requestSessionRebuild(0L);
                } else if (cameraDevice != null) {
                    releaseCamera();
                }
                return false;
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
        }
    };

    // ---- v1.54 beta3：BSD 预警红色遮罩 + 右预警画面复用右转向灯画面源 ----

    /** 在宿主浮窗边界加一小圈向内的半透明红色遮罩（示意 BSD 预警状态）。
     * 仅 BSD 预警弹窗调用（onBsdEdge/showBsdRightOverlay）；开关关闭时不加。
     * 宿主必须为 overlay_window 的 FrameLayout；遮罩 View 不可点击，不拦截拖动/编辑手势。
     * v1.54 beta4：红边改为呼吸红色渐变闪烁——alpha 在低-高区间周期性循环，替换静态描边。 */
    private void showRedOverlayOn(View host) {
        if (host == null || !bsdRedOverlay) return;
        if (host.findViewWithTag("bsd_red_overlay") != null) return;
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(0x00000000);
        gd.setStroke(dp(9), 0x66FF0000); // v1.54 beta7：呼吸红框描边加粗为 3 倍（3dp→9dp）
        View rv = new View(this);
        rv.setTag("bsd_red_overlay");
        rv.setBackground(gd);
        rv.setClickable(false);
        rv.setFocusable(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        ((FrameLayout) host).addView(rv, lp);
        // v1.54 beta4：呼吸闪烁动画——透明度 0.15 -> 1.0 循环往复，红边随之渐变闪动
        android.animation.ObjectAnimator anim = android.animation.ObjectAnimator.ofFloat(rv, "alpha", 0.15f, 1.0f);
        anim.setDuration(900);
        anim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        anim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        anim.start();
        rv.setTag(R.id.overlay_border, anim);
    }

    /** 移除宿主浮窗上的 BSD 预警红色遮罩（无则跳过），并取消呼吸动画。 */
    private void hideRedOverlayOn(View host) {
        if (host == null) return;
        View rv = host.findViewWithTag("bsd_red_overlay");
        if (rv != null) {
            Object anim = rv.getTag(R.id.overlay_border);
            if (anim instanceof android.animation.Animator) {
                ((android.animation.Animator) anim).cancel();
            }
            ((FrameLayout) host).removeView(rv);
        }
    }

    /** v1.54 beta7：打灯浮窗联动 BSD 红框——BSD 信号边沿到来时，若对应侧打灯变道浮窗
     * 正在显示（非 BSD 弹起、非手动），叠加/移除呼吸红框。复用 showRedOverlayOn（内部受
     * bsd_red_overlay 开关控制）；打灯期间 BSD 自动弹窗已被最低优先级抑制，红框为替代提醒。 */
    private void onBsdEdgeForTurn(String dir, boolean rising) {
        if (overlayView == null || direction == null) return;
        if ("MANUAL".equals(direction)) return;
        if (bsdAutoActiveDir != null) return;
        if (!dir.equals(direction)) return;
        if (rising) {
            showRedOverlayOn(overlayView);
            serviceLog("BSD 联动红框: 打" + ("LEFT".equals(dir) ? "左" : "右")
                    + "转向灯期间对应侧 BSD 有车，浮窗叠加呼吸红框");
        } else {
            hideRedOverlayOn(overlayView);
            serviceLog("BSD 联动红框: " + ("LEFT".equals(dir) ? "左" : "右")
                    + "侧 BSD 信号消失，移除打灯浮窗红框");
        }
    }

    /** v1.54 beta7：打灯浮窗建立/方向切换后同步当前 BSD 状态——对应侧 BSD 已有车（无 0->1 边沿）
     * 时立即叠加呼吸红框，保证浮窗显示期间红框状态与 BSD 信号实时一致。 */
    private void syncTurnBsdRedOverlay() {
        if (overlayView == null || direction == null || "MANUAL".equals(direction)) return;
        if (bsdAutoActiveDir != null) return;
        if (!bsdRedOverlay) return;
        if (signalGate == null) return;
        SignalGate.Snapshot s = signalGate.snapshot();
        boolean warn = "LEFT".equals(direction) ? s.bsdLeft : s.bsdRight;
        if (warn) {
            if (overlayView.findViewWithTag("bsd_red_overlay") == null) {
                showRedOverlayOn(overlayView);
                serviceLog("BSD 联动红框: 打" + ("LEFT".equals(direction) ? "左" : "右")
                        + "转向灯浮窗显示期间对应侧 BSD 已有车，叠加呼吸红框");
            }
        } else {
            hideRedOverlayOn(overlayView);
        }
    }

    /** v1.54 beta3：BSD 右预警浮窗渲染矩阵——默认复用右转向灯画面源设置（right_cam_scale /
     * right_pan_x / right_pan_y，与打右转向灯浮窗同一套持久化键，包含默认初始右下 1/4 裁切
     * 与用户编辑后的自由平移），使右预警画面与打右转向灯时完全一致。
     * v1.54 beta4：若用户已用「编辑预警浮窗」保存过 bsd_right_ 独立键（存在 bsd_right_cam_scale），
     * 则优先使用 bsd_right_ 独立配置，未编辑时保持 beta3 的 right_ 键回退逻辑。
     * 画面内容与浮窗位置彻底解耦：矩阵只依赖窗口尺寸与画面配置，不读 lp.x/lp.y。 */
    /** v1.58：次级窗 S 渲染入口——画面配置按 pb_l_ / pb_r_ 取键，与窗口位置解耦。 */
    private void applyBsdRightRenderTransform() {
        applySlotTransform("S", slotSSide == null ? "RIGHT" : slotSSide);
    }

    /** v1.54：BSD 常显窄条位置一键复位（ACTION_RESET_BSD_POS 入口）。 */
    private void resetBsdPositionsInternal() {
        if (sideWarning != null) {
            sideWarning.resetPositions();
        }
        serviceLog("BSD 常显窄条位置已一键复位为默认（x=8dp, y=90dp）");
    }

    /** 按方向显示/切换浮窗：首次弹窗创建 overlay，方向变化释放旧摄像头并按新方向重开。 */
    private void showOverlayForDirection(String dir) {
        // v1.54：BSD 自动弹窗调用（bsdAutoCalling=true）已由 onBsdEdge 完成最低优先级抑制判定，
        // 这里跳过打灯专属抑制块；同时不参与打灯幂等锁/方向锁写入，避免污染打灯链路。
        boolean bsdCall = bsdAutoCalling;
        // v1.52/v1.53 beta2：抑制优先级（仅作用于转向灯弹窗，不干扰 MANUAL 手动调试）：
        // 窄路辅助动态窗口（最高） > GEARLEV==3 倒车显示中 > R 挡退出短延时窗口 > 原厂相机焦点 > 单边转向。
        if (!bsdCall && !"MANUAL".equals(dir)) {
            // v1.53 beta2：窄路辅助动态窗口在屏（裸包名窗口）时打灯一律抑制，
            // 避免转向浮窗覆盖窄路辅助画面（最高优先级）。
            if (signalGate != null && signalGate.snapshot().narrowAssistActive) {
                serviceLog("窄路辅助动态窗口在屏，打灯抑制不弹浮窗");
                return;
            }
            // v1.53：R 挡进行中（gearLevel==3 已正确解析）打灯一律抑制，
            // 避免倒车影像期间弹转向浮窗抢占 AVM 画面。
            if (signalGate != null && signalGate.snapshot().gearLevel == 3) {
                serviceLog("R 挡进行中（gearLevel=3），打灯抑制不弹浮窗");
                return;
            }
            long remain = rExitSuppressUntilMs - System.currentTimeMillis();
            if (remain > 0) {
                serviceLog("R 挡退出延时抑制窗口内（剩余 " + remain + "ms），打灯不弹浮窗");
                return;
            }
        }
        // v1.57 beta3：浮窗弹窗即异步启动仪表 H.264 投影（连接失败静默降级，浮窗不受影响）。
        // 多次打灯循环：stop 后再次 show 可重新启动，不泄漏编码器/socket/线程。
        startProjectionIfEnabled();
        // v1.39：同方向持续亮起期间若浮窗与相机均已就绪，忽略重复触发，绝不重发 AVM 绑定视角。
        // 打灯后转向灯信号理论上只产生一次 0->1 边沿；若车机 dumpsys 输出抖动出现假边沿，
        // 再次进入本方法会重复 sendAvmMode 抢占 AVM 视角（表现为倒挡影像被覆盖、转向画面卡顿），
        // 此幂等锁 + SignalMonitor 缺失行保持原状态双重兜底，确保打灯期间只下发一次绑定视角。
        // v1.54：BSD 自动弹窗调用不参与幂等锁（0->1 边沿每轮至多一次，无重复触发风险）。
        if (!bsdCall && dir.equals(lastActiveDir) && overlayView != null && cameraDevice != null) {
            serviceLog("同方向信号持续亮起，忽略重复触发，不重发 AVM 视角");
            return;
        }
        // v1.57：打灯/手动接管浮窗时无条件清理 BSD 归属标记并移除预警红框——
        // 预警红框与预警浮窗完全绑定（随浮窗出现/消失），浮窗一旦被转向/手动接管
        // （不再限于同方向），立即移除预警红框；清理归属保证转向红框独立判定
        // （syncTurnBsdRedOverlay / onBsdEdgeForTurn 不再被残留归属短路），
        // 同时避免 BSD 预警熄灭沿把已接管浮窗误关（见 onBsdEdge）。
        if (!bsdCall) {
            if (bsdAutoActiveDir != null) {
                serviceLog("打灯/手动接管浮窗，清理 BSD 归属标记（" + bsdAutoActiveDir + "）");
                bsdAutoActiveDir = null;
            }
            hideRedOverlayOn(overlayView);
        }
        if (!bsdCall) {
            lastActiveDir = dir;
        }
        String oldDir = direction;
        // v1.48：方向切换前先保存旧方向当前显示状态（位置/大小/画面缩放平移），
        // 修复编辑模式未退出或普通调整后直接换方向打灯导致旧方向配置丢失的问题。
        // v1.60_beta1：仅当编辑窗真的打开时才落盘。否则打灯瞬间会把窗口当前坐标
        // （可能已被旧键污染）写回 pa_，永久覆盖用户在设置页定好的位置。
        if (slotEditActive && overlayView != null && !dir.equals(oldDir)
                && oldDir != null && !"MANUAL".equals(oldDir)) {
            saveEditConfigInternal();
        }
        direction = dir;
        // v1.32：左右转向浮窗配置独立（位置/大小/裁切），方向切换且浮窗已存在时按新方向重新应用；
        // 旧方向若处于编辑模式先自动保存，避免切换丢失未保存配置。
        if (overlayView != null && !dir.equals(oldDir)) {
            applyOverlayConfigForDirection();
        }
        // v1.28：转向灯若已绑定 AVM 视角（left_avm_mode/right_avm_mode），打灯经 AVM 下发该视角，
        // 不再打开物理摄像头（实车验证物理 cameraId 直选无效）；未绑定则回退原物理摄像头浮窗逻辑。
        if (!"MANUAL".equals(dir)) {
            int avmMode = getAvmBinding(dir);
            if (avmMode != -1) {
                avmModeActive = true;
                avmRefreshCount = 0;
                if (overlayView == null) {
                    startForegroundCompat();
                    ensureOverlay();
                } else {
                    updateLabel();
                }
                // v1.57 beta4：修复双开（主浮窗 + BSD 右窗）两窗同时弹出只有空框的缺陷——
                // 原逻辑无条件 closeCameraOnly() 后立刻 openCamera()，CameraDevice.close() 异步
                // 未完成即重开同一 cameraId，返回 CAMERA_IN_USE 触发 onError，之后无路径重开相机。
                // 现改为：相机已打开且复用同一路画面源时不再关开，只下发改视角 + 重建会话。
                sendAvmMode(avmMode);
                if (cameraDevice != null) {
                    if (canReuseOpenedCamera()) {
                        serviceLog("相机已打开且画面源一致，跳过重开，仅重建预览会话");
                        if (hasAnyPreviewTarget()) {
                            createPreviewSession();
                        }
                    } else {
                        closeCameraOnly();
                        openCamera();
                    }
                } else {
                    // v1.33：AVM 绑定路径同样打开物理摄像头（openCamera 默认 cameraId=0，实车可出鱼眼画面），
                    // 否则浮窗 TextureView 无预览流导致打灯画面空白。
                    openCamera();
                }
                // v1.54 beta7：打灯浮窗建立后同步当前 BSD 状态——对应侧 BSD 已有车则立即叠加呼吸红框
                syncTurnBsdRedOverlay();
                return;
            }
        }
        avmModeActive = false;
        if (overlayView == null) {
            startForegroundCompat();
            ensureOverlay();
            openCamera();
        } else if (cameraDevice == null) {
            // 浮窗在但摄像头未就绪（如刚移除过 TextureView）：重开
            startForegroundCompat();
            openCamera();
        } else if (!dir.equals(openedDirection)) {
            // 方向变化（如左转->右转）：释放旧方向摄像头并按新方向重开，避免画面与标签不一致
            startForegroundCompat();
            closeCameraOnly();
            openCamera();
        } else {
            updateLabel();
        }
    }

    /** 隐藏浮窗并释放摄像头（幂等）。 */
    /** 隐藏浮窗并释放摄像头（幂等）。联动关闭、停服务、手动关闭等路径走这里，立即完整释放。 */
    private void hideOverlayAndCamera() {
        hideOverlayAndCamera(false);
    }

    /**
     * v1.67 beta6：收窗 + 相机处理，按路径决定相机是否延迟释放。
     *
     * @param lingerCamera true=熄灯/预警结束的常规路径：窗口立即销毁（hideAllSlots +
     *                     removeOverlay + destroySecondaryWindow），但相机与 CaptureSession
     *                     延迟 {@link #LINGER_MS}（1.2s）再释放，期间再触发可直接复用；
     *                     false=必须立即完整释放（联动关闭、停服务、手动窗关闭、
     *                     系统影像占用等「不该占着相机」的场景）。
     */
    private void hideOverlayAndCamera(boolean lingerCamera) {
        // v1.48：隐藏前无条件保存当前方向浮窗配置（位置/窗口大小/画面缩放平移）。
        // 修复关闭打灯联动后再次开启、浮窗大小位置重置：无论用户经哪种路径调整过
        //（普通拖动、编辑模式、拖边、双指缩放），关闭/熄灭那一刻都把当前显示状态落盘，
        // 下次打灯 ensureOverlay 从 prefs 恢复，不再回退默认值。
        if (overlayView != null && direction != null && !"MANUAL".equals(direction)) {
            saveEditConfigInternal();
        }
        // v1.39：灯熄灭即清除方向锁，确保下次重新打灯能再次下发一次绑定视角
        lastActiveDir = null;
        avmModeActive = false;
        // v1.67 beta4：收窗必须把两窗一起收——过去只 removeOverlay()（P 窗），
        // 次级窗既不隐藏也不销毁，关闭浮窗总开关后它会停在屏上，且服务重建后彻底失去引用。
        hideAllSlots();
        removeOverlay();
        destroySecondaryWindow();
        if (lingerCamera && canLingerCamera()) {
            // v1.67 beta6 L1：窗口已收，相机与会话留 1.2s
            scheduleCameraLinger();
        } else {
            cancelCameraLinger();
            releaseCamera();
        }
        // v1.57 beta3：熄灯/预警结束收窗。投影始终立即停——延迟释放只保相机与会话，
        // 仪表画面若一并保留会出现残影，因此 startProjection 的逆操作不做延迟。
        stopProjection();
    }

    /** v1.67 beta6：排一次延迟释放，覆盖上一次未到期的计时（幂等）。 */
    private void scheduleCameraLinger() {
        if (cameraDevice == null && captureSession == null) {
            return;
        }
        if (cameraLingerActive) {
            // 已在计时：不重复排、不续期（否则 500ms 慢变量回调会无限延后释放）
            return;
        }
        Handler h = mainHandler != null ? mainHandler : pProbeHandler;
        h.removeCallbacks(cameraLingerRunnable);
        cameraLingerActive = true;
        cameraLingerReuseCount = 0;
        if (!h.postDelayed(cameraLingerRunnable, LINGER_MS)) {
            cameraLingerActive = false;
            serviceLog("延迟释放 postDelayed 失败：改为立即释放相机");
            releaseCamera();
            return;
        }
        serviceLog("熄灯/预警结束：窗口已收，相机与会话保留 " + LINGER_MS + "ms 待复用");
    }

    /** 取消延迟释放计时（只撤计时，不释放相机本身，幂等）。 */
    private void cancelCameraLinger() {
        Handler h = mainHandler != null ? mainHandler : pProbeHandler;
        h.removeCallbacks(cameraLingerRunnable);
        cameraLingerActive = false;
    }

    /**
     * 延迟释放的准入判定：任何「不该占着相机」的状态一律退回立即释放。
     *
     * <p>尤其是窄路辅助/系统影像在屏（narrowActive）与倒车抑制窗口——这两类场景
     * 车机 AVM 可能正在抢摄像头，留着相机不放会直接冲掉对方画面。</p>
     */
    private boolean canLingerCamera() {
        if (!CAMERA_LINGER_ENABLED) {
            // v1.70：回到 beta5——熄灯即完整释放，不保留相机与会话
            return false;
        }
        if (!LINGER_REUSE_SESSION) {
            return false;
        }
        if (!linkActive.get()) {
            // 联动已关：不会再有下一次打灯触发，留着相机没有意义，直接释放
            return false;
        }
        if (narrowActive) {
            return false;
        }
        if (System.currentTimeMillis() < rExitSuppressUntilMs) {
            return false;
        }
        if (slotEditP != null || slotEditS != null) {
            return false;
        }
        if (manualVisible.get()) {
            return false;
        }
        return true;
    }

    // ---- v1.57 beta3：仪表 H.264 投影联动 ----

    /** 启用仪表投影时异步启动（失败静默降级，绝不阻塞/影响浮窗）。 */
    private void startProjectionIfEnabled() {
        if (!iclProjectionEnabled) {
            return;
        }
        try {
            if (icProjector.isRunning()) {
                return;
            }
            icProjector.start(iclIp, iclPort);
        } catch (Throwable t) {
            Log.w(TAG, "startProjection failed, silent: " + t.getMessage());
        }
    }

    /** 同步停止仪表投影（幂等，可重复调用）。 */
    private void stopProjection() {
        try {
            icProjector.stop();
        } catch (Throwable t) {
            Log.w(TAG, "stopProjection failed, silent: " + t.getMessage());
        }
    }

    /**
     * v1.46：浮窗右上角关闭按钮点击处理——打灯结束前提前关闭浮窗。
     * 转向浮窗：关闭后进入“关闭抑制”，同方向 0->1 亮起信号不再重开（防打灯抖动假边沿误重开），
     * 直到检测到 1 秒熄灭信号或换方向打灯才恢复召唤（见 onSignalChanged）。
     * 手动浮窗：直接隐藏，无联动运行时停止前台服务。
     */
    private void closeOverlayFromButton() {
        if (overlayView == null) return;
        if ("MANUAL".equals(direction)) {
            manualVisible.set(false);
            hideOverlayAndCamera();
            if (!linkActive.get()) {
                stopSelfInternal();
            }
            serviceLog("手动浮窗已通过关闭按钮关闭");
            return;
        }
        // v1.58：转向/预警场景下 P、S 是常驻窗口，✕ 只做隐藏不销毁，
        // 先落盘当前几何与画面，再清空槽位分配，避免下次重建造成黑帧。
        String closeSide = slotPSide != null ? slotPSide : direction;
        closeSuppressedDir = closeSide;
        if (slotPSide != null) {
            saveEditConfigInternal();
        }
        slotPSide = null;
        slotPType = null;
        slotSSide = null;
        slotSType = null;
        hideAllSlots();
        lastActiveDir = null;
        avmModeActive = false;
        updateCameraForSlots();
        serviceLog("浮窗已手动关闭（" + closeSide + "），进入关闭抑制：同方向亮起不重开，等1秒熄灭或换方向");
    }

    private void ensureOverlay() {
        if (overlayView != null) return;
        LayoutInflater li = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        overlayView = li.inflate(R.layout.overlay_window, null);
        textureView = overlayView.findViewById(R.id.overlay_texture);
        // v1.47：TextureView 按 24dp 圆角裁剪，防止矩形预览画面覆盖浮窗背景 R 角呈直角
        if (textureView != null) {
            textureView.setClipToOutline(true);
            textureView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(24));
                }
            });
        }
        labelView = overlayView.findViewById(R.id.overlay_label);
        updateLabel();
        // v1.32：编辑模式视觉元素（金色描边边框、四边裁切手柄、底部角标）
        borderView = overlayView.findViewById(R.id.overlay_border);
        cropHandleL = overlayView.findViewById(R.id.handle_left);
        cropHandleR = overlayView.findViewById(R.id.handle_right);
        cropHandleT = overlayView.findViewById(R.id.handle_top);
        cropHandleB = overlayView.findViewById(R.id.handle_bottom);
        cropHandleTL = overlayView.findViewById(R.id.handle_tl);
        cropHandleTR = overlayView.findViewById(R.id.handle_tr);
        cropHandleBL = overlayView.findViewById(R.id.handle_bl);
        cropHandleBR = overlayView.findViewById(R.id.handle_br);
        editHintView = overlayView.findViewById(R.id.overlay_edit_hint);
        android.graphics.drawable.GradientDrawable borderBg = new android.graphics.drawable.GradientDrawable();
        borderBg.setStroke(dp(2), 0xFFFFD98A);
        borderBg.setColor(0x00000000);
        if (borderView != null) borderView.setBackground(borderBg);
        if (editHintView != null) editHintView.setText("可拖动");
        // v1.46：右上角关闭按钮——打灯结束前可提前关闭浮窗（转向浮窗进入关闭抑制，防同方向误重开）
        View closeBtn = overlayView.findViewById(R.id.overlay_close);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeOverlayFromButton();
                }
            });
        }
        // v1.33：四边边缘手柄改窗口形状（窗口变化不影响画面，画面按基准尺寸居中裁剪）
        setupWindowEdgeHandle(cropHandleL, "left");
        setupWindowEdgeHandle(cropHandleR, "right");
        setupWindowEdgeHandle(cropHandleT, "top");
        setupWindowEdgeHandle(cropHandleB, "bottom");
        // v1.51：四角等比例缩放手柄（tl/tr/bl/br）
        setupWindowCornerHandle(cropHandleTL, "tl");
        setupWindowCornerHandle(cropHandleTR, "tr");
        setupWindowCornerHandle(cropHandleBL, "bl");
        setupWindowCornerHandle(cropHandleBR, "br");

        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        // v1.58：P 窗几何统一读 pa_ 前缀（左右状态共用同一套位置/大小）；
        // MANUAL 手动浮窗仍走 v1.30 通用 overlay_scale / overlay_pos_* 键。
        boolean manual = "MANUAL".equals(direction);
        int w;
        int h;
        int px;
        int py;
        if (manual) {
            float scale = readOverlayScale();
            w = (int) (dm.widthPixels * scale);
            h = (int) (w * 9f / 16f);
            if (h > dm.heightPixels) h = dm.heightPixels;
            camScale = CAM_SCALE_DEFAULT;
            SharedPreferences mp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            px = mp.getInt("overlay_pos_x", 16);
            py = mp.getInt("overlay_pos_y", (int) (dm.heightPixels * 0.25f));
        } else {
            migrateLegacyOverlayScale(dm.widthPixels, dm.heightPixels);
            int[] geo = readSlotGeo("P", dm);
            w = geo[0];
            h = geo[1];
            px = geo[2];
            py = geo[3];
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = px;
        lp.y = py;
        try {
            wm.addView(overlayView, lp);
            slotPShown = true;
        } catch (Exception e) {
            Log.e(TAG, "addView overlay failed", e);
            overlayView = null;
            slotPShown = false;
            return;
        }
        // v1.58：P 窗不再支持拖动位置（位置统一在设置页编辑），双击进入本窗画面编辑
        setupSlotPInteraction(overlayView);
        if (textureView != null) textureView.setSurfaceTextureListener(surfaceListener);
        applySlotTransform("P", slotPSide == null ? normSide(direction) : slotPSide);
    }

    /** 读取 MANUAL 手动浮窗缩放比例（0.2~1.0，默认 0.4），越界自动收敛。
     * v1.33：仅 MANUAL 使用（通用 overlay_scale）；转向浮窗改用 left/right_overlay_w/h 与 cam_scale。 */
    private float readOverlayScale() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        float s = prefs.getFloat("overlay_scale", 0.4f);
        if (s < 0.2f) s = 0.2f;
        if (s > 1.0f) s = 1.0f;
        return s;
    }

    /** 读取当前方向窗口宽度（像素）：优先 left/right_overlay_w，默认屏宽*0.4；越界收敛到 [屏宽*0.15, 屏宽]。 */
    private int readOverlayW(int screenW) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String p = "LEFT".equals(direction) ? "left_" : "right_";
        int defW = (int) (screenW * OVERLAY_DEF_W_RATIO);
        int w = prefs.getInt(p + "overlay_w", defW);
        int minW = (int) (screenW * MIN_SIZE_RATIO);
        if (w < minW) w = minW;
        if (w > screenW) w = screenW;
        return w;
    }

    /** 读取当前方向窗口高度（像素）：优先 left/right_overlay_h，默认 基准宽*9/16；越界收敛到 [屏高*0.15, 屏高]。 */
    private int readOverlayH(int screenH) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String p = "LEFT".equals(direction) ? "left_" : "right_";
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int defW = (int) (dm.widthPixels * OVERLAY_DEF_W_RATIO);
        int defH = (int) (defW * 9f / 16f);
        int h = prefs.getInt(p + "overlay_h", defH);
        int minH = (int) (screenH * MIN_SIZE_RATIO);
        if (h < minH) h = minH;
        if (h > screenH) h = screenH;
        return h;
    }

    /** 读取当前方向画面缩放（cam_scale，默认 1.0），越界收敛到 0.2~4.0。 */
    private float readCamScale() {
        if ("MANUAL".equals(direction)) return CAM_SCALE_DEFAULT;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // v1.60_beta1：优先「窗×侧」画面键（pa_l_/pa_r_，与编辑保存同键），旧 left_/right_ 仅兜底，
        // 否则打灯时读不到用户编辑的画面参数，画面会突然跳回默认裁切（观感等同卡死）。
        String fp = curFramePrefix();
        String legacyP = "LEFT".equals(direction) ? "left_" : "right_";
        float s = prefs.contains(fp + "cam_scale")
                ? prefs.getFloat(fp + "cam_scale", CAM_SCALE_DEFAULT)
                : prefs.getFloat(legacyP + "cam_scale", CAM_SCALE_DEFAULT);
        if (s < CAM_SCALE_MIN) s = CAM_SCALE_MIN;
        if (s > CAM_SCALE_MAX) s = CAM_SCALE_MAX;
        return s;
    }

    /** v1.34 读取当前方向画面横向平移（pan_x，默认 0）。MANUAL 无平移概念返回 0；
     * 实际收敛由 applyRenderTransform 依据窗口尺寸实时完成。 */
    private float readPanX() {
        if ("MANUAL".equals(direction)) return 0f;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String fp = curFramePrefix();
        String legacyP = "LEFT".equals(direction) ? "left_" : "right_";
        return prefs.contains(fp + "pan_x") ? prefs.getFloat(fp + "pan_x", 0f)
                : prefs.getFloat(legacyP + "pan_x", 0f);
    }

    /** v1.34 读取当前方向画面纵向平移（pan_y，默认 0），见 readPanX。 */
    private float readPanY() {
        if ("MANUAL".equals(direction)) return 0f;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String fp = curFramePrefix();
        String legacyP = "LEFT".equals(direction) ? "left_" : "right_";
        return prefs.contains(fp + "pan_y") ? prefs.getFloat(fp + "pan_y", 0f)
                : prefs.getFloat(legacyP + "pan_y", 0f);
    }

    /** 迁移 v1.32 旧配置：存在 left/right_overlay_scale 且未写新键时换算为 overlay_w/h 后删除；
     * 废弃的 left/right_crop_* 裁切键一律删除（读取时忽略）。 */
    private void migrateLegacyOverlayScale(int screenW, int screenH) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
        for (String p : new String[]{"left_", "right_"}) {
            if (prefs.contains(p + "overlay_scale") && !prefs.contains(p + "overlay_w")) {
                float s = prefs.getFloat(p + "overlay_scale", 0.4f);
                if (s < 0.2f) s = 0.2f;
                if (s > 1.0f) s = 1.0f;
                int w = (int) (screenW * s);
                int h = (int) (w * 9f / 16f);
                if (h > screenH) h = screenH;
                ed.putInt(p + "overlay_w", w);
                ed.putInt(p + "overlay_h", h);
            }
            ed.remove(p + "overlay_scale");
            ed.remove(p + "crop_l");
            ed.remove(p + "crop_t");
            ed.remove(p + "crop_r");
            ed.remove(p + "crop_b");
        }
        ed.apply();
    }

    /** 记录当前位置（v1.30）：浮窗未显示则提示先打开手动浮窗，否则按当前方向写位置键并置 recorded。
     * v1.32：转向浮窗写入方向独立键（left_overlay_pos_x/y、right_overlay_pos_x/y），手动沿用通用键。 */
    private void recordPositionInternal() {
        if (overlayView == null) {
            Toast.makeText(this, "请先打开浮窗并拖动到位", Toast.LENGTH_SHORT).show();
            serviceLog("记录位置失败：浮窗未显示");
            return;
        }
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
        if (lp.x <= SLOT_HIDDEN_X || lp.y <= SLOT_HIDDEN_Y) {
            Toast.makeText(this, "浮窗处于隐藏状态，请先打灯显示后再记录", Toast.LENGTH_SHORT).show();
            return;
        }
        String p = "MANUAL".equals(direction) ? "" : ("LEFT".equals(direction) ? "left_" : "right_");
        SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        // v1.60_beta2：主窗几何统一读 pa_ 键，记录位置必须写 pa_，旧方向键保留兼容。
        if (!"MANUAL".equals(direction)) {
            ed.putInt("pa_overlay_pos_x", lp.x);
            ed.putInt("pa_overlay_pos_y", lp.y);
            ed.putBoolean("pa_overlay_pos_recorded", true);
            ed.putInt("pa_last_pos_x", lp.x);
            ed.putInt("pa_last_pos_y", lp.y);
            ed.putInt("pa_overlay_w", lp.width);
            ed.putInt("pa_overlay_h", lp.height);
            lastPGeom = new int[] {lp.width, lp.height, lp.x, lp.y};
        }
        ed.putInt(p + "overlay_pos_x", lp.x);
        ed.putInt(p + "overlay_pos_y", lp.y);
        ed.putBoolean(p + "overlay_pos_recorded", true);
        ed.apply();
        Toast.makeText(this, "位置已记录", Toast.LENGTH_SHORT).show();
        serviceLog("浮窗位置已记录 x=" + lp.x + " y=" + lp.y + " dir=" + direction);
    }

    /** 按当前配置实时调整浮窗大小；浮窗未显示时仅更新配置，下次弹出生效。
     * v1.33：MANUAL 沿用通用 overlay_scale；转向浮窗按方向 left/right_overlay_w/h 应用
     * （未记录位置时 LEFT 贴左下、RIGHT 贴右下），窗口变化后重放解耦渲染矩阵。 */
    private void applyOverlaySizeInternal() {
        if (overlayView == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
        int w;
        int h;
        if ("MANUAL".equals(direction)) {
            float scale = readOverlayScale();
            w = (int) (dm.widthPixels * scale);
            h = (int) (w * 9f / 16f);
            if (h > dm.heightPixels) h = dm.heightPixels;
            camScale = CAM_SCALE_DEFAULT;
        } else {
            migrateLegacyOverlayScale(dm.widthPixels, dm.heightPixels);
            w = readOverlayW(dm.widthPixels);
            h = readOverlayH(dm.heightPixels);
            camScale = readCamScale();
            panX = readPanX();
            panY = readPanY();
        }
        lp.width = w;
        lp.height = h;
        // v1.60_beta1：非 MANUAL 窗的几何一律以槽位键（pa_ + overlay_pos_recorded）为准。
        // 旧实现读 left_/right_ 旧键、未记录时硬编码 x=120 贴左，导致设置页定好的
        // 位置在每次打灯/方向切换时被这里重置。
        if (!"MANUAL".equals(direction)) {
            int[] geoSz = readSlotGeo("P", dm);
            lp.width = geoSz[0];
            lp.height = geoSz[1];
            lp.x = geoSz[2];
            lp.y = geoSz[3];
        }
        try {
            wm.updateViewLayout(overlayView, lp);
        } catch (Exception e) {
            Log.e(TAG, "applyOverlaySize update failed", e);
        }
        applyRenderTransform();
    }

    /** 更新浮窗左上角方向/模式标签。 */
    /** 更新优先窗 P 的标签（文案由当前槽位内容侧/类型决定）。 */
    private void updateLabel() {
        if (labelView == null) return;
        if ("MANUAL".equals(direction)) {
            labelView.setText("手动预览 · 可拖动");
            return;
        }
        String side = slotPSide != null ? slotPSide : normSide(direction);
        String type = slotPType != null ? slotPType : EVT_TURN;
        updateSlotLabel("P", side, type);
    }

    /** 浮窗触摸处理（v1.34）：
     * - MANUAL 手动浮窗保持 v1.31 现状：始终单指可拖动，不进入编辑模式；
     * - LEFT/RIGHT 转向浮窗：普通模式单指滑动 = 拖动整个浮窗位置，松手自动保存到方向配置；
     *   双击进入编辑模式；编辑模式单指滑动 = 平移框内画面（panX/panY，窗口不动）、
     *   双指固定 16:9 比例缩放（非 ScaleGestureDetector，避免与拖动冲突）、
     *   双击退出并保存当前 位置/大小/缩放/平移。 */
    private void makeDraggable(final View view) {
        if (view == null) return;
        view.setOnTouchListener(new View.OnTouchListener() {
            private int downX;
            private int downY;
            private int initX;
            private int initY;
            // v1.34：编辑模式平移画面：起始 pan 值
            private float initPanX;
            private float initPanY;
            private boolean dragging = false;
            // 双击检测：记录上次单击抬起时间（ms）
            private long lastTapTime = 0L;
            private boolean doubleTapConsumed = false;
            // 双指画面缩放：起始两指间距与起始 camScale
            private float startSpan = 0f;
            private float startCamScale = 0f;
            private boolean pinchActive = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (overlayView == null) return false;
                WindowManager.LayoutParams lp =
                        (WindowManager.LayoutParams) overlayView.getLayoutParams();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = (int) event.getRawX();
                        downY = (int) event.getRawY();
                        initX = lp.x;
                        initY = lp.y;
                        initPanX = panX;
                        initPanY = panY;
                        dragging = false;
                        pinchActive = false;
                        // 转向浮窗：双击切换编辑模式（普通模式→进入；编辑模式→退出保存）
                        if (!"MANUAL".equals(direction)) {
                            long now = System.currentTimeMillis();
                            if (now - lastTapTime < 400L) {
                                lastTapTime = 0L;
                                doubleTapConsumed = true;
                                toggleEditMode();
                            } else {
                                lastTapTime = 0L;
                                doubleTapConsumed = false;
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        // 双指画面缩放仅编辑模式生效（手动浮窗保持 v1.31 拖动现状）
                        if (editMode && event.getPointerCount() >= 2) {
                            pinchActive = true;
                            dragging = false;
                            startSpan = computeSpan(event);
                            startCamScale = camScale;
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (pinchActive && event.getPointerCount() >= 2) {
                            float span = computeSpan(event);
                            if (startSpan > 0f && span > 0f) {
                                float newScale = startCamScale * span / startSpan;
                                applyCamScale(newScale);
                            }
                            return true;
                        }
                        if (editMode) {
                            // v1.34 编辑模式单指滑动 = 平移框内画面（panX/panY），窗口位置不变；applyRenderTransform 内部实时收敛
                            int dx = (int) event.getRawX() - downX;
                            int dy = (int) event.getRawY() - downY;
                            if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
                                dragging = true;
                            }
                            if (dragging) {
                                panX = initPanX + dx;
                                panY = initPanY + dy;
                                applyRenderTransform();
                            }
                            return true;
                        }
                        if ("MANUAL".equals(direction)) {
                            // v1.31 现状：手动浮窗始终可拖动
                            int dx = (int) event.getRawX() - downX;
                            int dy = (int) event.getRawY() - downY;
                            if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
                                dragging = true;
                            }
                            if (dragging) {
                                lp.x = initX + dx;
                                lp.y = initY + dy;
                                try {
                                    wm.updateViewLayout(overlayView, lp);
                                } catch (Exception ignored) {
                                }
                            }
                            return true;
                        }
                        // v1.34 非编辑模式转向浮窗单指滑动 = 拖动整个浮窗位置（松手 ACTION_UP 自动保存）
                        {
                            int dx = (int) event.getRawX() - downX;
                            int dy = (int) event.getRawY() - downY;
                            if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
                                dragging = true;
                            }
                            if (dragging) {
                                lp.x = initX + dx;
                                lp.y = initY + dy;
                                try {
                                    wm.updateViewLayout(overlayView, lp);
                                } catch (Exception ignored) {
                                }
                            }
                            return true;
                        }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (pinchActive) {
                            pinchActive = false;
                            return true;
                        }
                        boolean wasDragging = dragging;
                        dragging = false;
                        // v1.51：手动浮窗与转向浮窗非编辑模式拖动结束后，先自动吸附再保存（编辑模式平移画面不触发）
                        if (wasDragging && !editMode) {
                            int oldX = lp.x;
                            int oldY = lp.y;
                            snapToNearestEdge(lp);
                            if (lp.x != oldX || lp.y != oldY) {
                                try {
                                    wm.updateViewLayout(overlayView, lp);
                                } catch (Exception ignored) {
                                }
                            }
                            if (!"MANUAL".equals(direction)) {
                                // v1.60_beta2：这里过去只写 left_/right_ 旧键，而主窗几何读取的
                                // 是 pa_ 键（readSlotGeo("P")），于是"打灯时直接把浮窗拖到位"
                                // 这个最常用的动作，位置根本没被主窗读回——表现为位置永远不记录。
                                // 现在必须同时写 pa_（主键）+ 旧键（兼容）。
                                String p = "LEFT".equals(direction) ? "left_" : "right_";
                                SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                                ed.putInt("pa_overlay_pos_x", lp.x);
                                ed.putInt("pa_overlay_pos_y", lp.y);
                                ed.putBoolean("pa_overlay_pos_recorded", true);
                                ed.putInt("pa_last_pos_x", lp.x);
                                ed.putInt("pa_last_pos_y", lp.y);
                                ed.putInt("pa_overlay_w", lp.width);
                                ed.putInt("pa_overlay_h", lp.height);
                                ed.putInt(p + "overlay_pos_x", lp.x);
                                ed.putInt(p + "overlay_pos_y", lp.y);
                                ed.putBoolean(p + "overlay_pos_recorded", true);
                                ed.apply();
                                lastPGeom = new int[] {lp.width, lp.height, lp.x, lp.y};
                                serviceLog("浮窗位置拖动自动保存 dir=" + direction + " pos=(" + lp.x + "," + lp.y + ")");
                            } else {
                                // v1.51：手动浮窗拖动结束同样吸附并落盘（通用键）
                                SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                                ed.putInt("overlay_pos_x", lp.x);
                                ed.putInt("overlay_pos_y", lp.y);
                                ed.putBoolean("overlay_pos_recorded", true);
                                ed.apply();
                                serviceLog("手动浮窗位置拖动自动保存 pos=(" + lp.x + "," + lp.y + ")");
                            }
                        }
                        // 转向浮窗非拖动抬起视为一次单击，供下次双击检测（双击已消费则不再计）
                        if (!wasDragging && !"MANUAL".equals(direction) && !doubleTapConsumed) {
                            lastTapTime = System.currentTimeMillis();
                        }
                        doubleTapConsumed = false;
                        return wasDragging;
                }
                return false;
            }
        });
    }

    /** v1.51：位置自动吸附——贴近屏幕右/上边缘、左/下侧边栏且阈值很近才吸附。
     * 左侧竖向快捷边栏右缘吸附线 = 屏宽*LEFT_BAR_RATIO，底部横向底栏上缘吸附线 = 屏高*BOTTOM_BAR_RATIO，
     * 右侧与上方为纯屏幕边缘。水平/垂直独立判断，允许同时吸附（如右上角）。
     * 编辑模式（平移画面）不触发。 */
    private void snapToNearestEdge(WindowManager.LayoutParams lp) {
        if (lp == null || wm == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int sw = dm.widthPixels;
        int sh = dm.heightPixels;
        int th = dp(SNAP_THRESHOLD_DP);
        // 水平方向：左吸附线 = 屏宽*0.08（左边栏右缘），右吸附线 = 屏宽（屏幕右缘）
        int leftLine = (int) (sw * LEFT_BAR_RATIO);
        int leftDist = Math.abs(lp.x - leftLine);
        int rightDist = Math.abs((lp.x + lp.width) - sw);
        if (leftDist <= th && rightDist <= th) {
            if (leftDist <= rightDist) lp.x = leftLine;
            else lp.x = sw - lp.width;
        } else if (leftDist <= th) {
            lp.x = leftLine;
        } else if (rightDist <= th) {
            lp.x = sw - lp.width;
        }
        // 垂直方向：上吸附线 = 屏顶（y=0），下吸附线 = 底栏上缘 = 屏高*0.92
        int bottomLine = (int) (sh * BOTTOM_BAR_RATIO);
        int topDist = Math.abs(lp.y);
        int bottomDist = Math.abs((lp.y + lp.height) - bottomLine);
        if (topDist <= th && bottomDist <= th) {
            if (topDist <= bottomDist) lp.y = 0;
            else lp.y = bottomLine - lp.height;
        } else if (topDist <= th) {
            lp.y = 0;
        } else if (bottomDist <= th) {
            lp.y = bottomLine - lp.height;
        }
    }

    /** 计算两点间距（欧氏距离）。 */
    private float computeSpan(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0f;
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** dp 转像素（编辑模式描边宽度等）。 */
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 双击切换编辑模式（LEFT/RIGHT 转向浮窗）：进入时加载方向配置（含平移）作为编辑基准，退出时保存。 */
    private void toggleEditMode() {
        if ("MANUAL".equals(direction) || overlayView == null) return;
        if (editMode) {
            saveEditConfigInternal();
            setEditMode(false);
            serviceLog("编辑模式退出，配置已保存");
        } else {
            camScale = readCamScale();
            panX = readPanX();
            panY = readPanY();
            // v1.42：从未编辑过（无 cam_scale 键）时，以默认左下/右下 1/4 裁切区域作为编辑初始值
            String p = "LEFT".equals(direction) ? "left_" : "right_";
            if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).contains(p + "cam_scale")) {
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                float defW = dm.widthPixels * OVERLAY_DEF_W_RATIO;
                float defH = defW * 9f / 16f;
                camScale = 2f;
                panX = "LEFT".equals(direction) ? defW / 2f : -defW / 2f;
                panY = -defH / 2f;
            }
            setEditMode(true);
            serviceLog("编辑模式进入（" + direction + "），双击保存退出");
        }
    }

    /** 设置编辑模式视觉状态：金色描边 + 四边边缘手柄 + 角标提示。 */
    private void setEditMode(boolean on) {
        editMode = on;
        if (borderView != null) borderView.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleL != null) cropHandleL.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleR != null) cropHandleR.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleT != null) cropHandleT.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleB != null) cropHandleB.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleTL != null) cropHandleTL.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleTR != null) cropHandleTR.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleBL != null) cropHandleBL.setVisibility(on ? View.VISIBLE : View.GONE);
        if (cropHandleBR != null) cropHandleBR.setVisibility(on ? View.VISIBLE : View.GONE);
        if (editHintView != null) {
            editHintView.setText(on ? "编辑模式 · 单指平移画面，双指缩放，拖边改形状" : "可拖动");
        }
        if (on) applyRenderTransform();
    }

    /** 保存当前编辑配置到方向键（位置/窗口宽高/画面缩放/画面平移），供双击退出与浮窗关闭时调用；
     * v1.33 不再写入废弃 crop_* 与 overlay_scale 键；v1.34 新增 pan_x/pan_y。 */
    /** v1.58：主窗 P 编辑保存——几何写 pa_ 共用前缀，画面写 pa_l_ / pa_r_ 分侧前缀。 */
    private void saveEditConfigInternal() {
        if (overlayView == null || "MANUAL".equals(direction)) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
        // v1.60_beta2：坐标必须屏内合法才允许落盘。熄灯/系统影像让位时槽位会被移到
        // SLOT_HIDDEN_X(-30000) 隐藏，旧实现在这种状态下照样把隐藏坐标写进 pa_ 并置
        // recorded=true，readSlotGeo 越界校验后回退默认——用户看到的就是
        // “拖好的位置永远不记录、每次回到默认居中”。
        int saveW = lp.width;
        int saveH = lp.height;
        int saveX = lp.x;
        int saveY = lp.y;
        // v1.60_beta3：贴边坐标（x=0 或 y=0）是合法位置（snapToNearestEdge 的上沿吸附线就是 y=0），
        // 不能当非法值丢弃，否则左上角位置永远存不下来。只有隐藏坐标/负数才视为非法。
        int badMin = -dp(4);
        if (saveX < badMin || saveY < badMin
                || saveX <= SLOT_HIDDEN_X || saveY <= SLOT_HIDDEN_Y) {
            if (lastPGeom == null || lastPGeom[2] < badMin || lastPGeom[3] < badMin) {
                serviceLog("跳过位置落盘：当前为隐藏/非法坐标 pos=(" + saveX + "," + saveY + ")");
                return;
            }
            saveW = lastPGeom[0];
            saveH = lastPGeom[1];
            saveX = lastPGeom[2];
            saveY = lastPGeom[3];
            serviceLog("当前为隐藏坐标，改用最近一次真实几何落盘 pos=(" + saveX + "," + saveY + ")");
        }
        String side = slotPSide != null ? slotPSide : normSide(direction);
        SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        ed.putInt("pa_overlay_w", saveW);
        ed.putInt("pa_overlay_h", saveH);
        ed.putInt("pa_overlay_pos_x", saveX);
        ed.putInt("pa_overlay_pos_y", saveY);
        ed.putBoolean("pa_overlay_pos_recorded", true);
        ed.putInt("pa_last_pos_x", saveX);
        ed.putInt("pa_last_pos_y", saveY);
        // v1.58：这里只落盘窗口几何。画面（cam_scale/pan_x/pan_y）唯一来源是
        // BsdEditWindow 编辑态保存，避免把陈旧字段值写进 pa_? 前缀导致
        // applySlotTransform 的 defaultCrop 误判为已编辑、画面从等比 fit 切到自由平移。
        ed.apply();
        serviceLog("优先窗几何已保存 side=" + side + " w=" + saveW + " h=" + saveH
                + " pos=(" + saveX + "," + saveY + ")");
    }

    /** 编辑模式双指缩放：仅改变窗口内画面缩放 camScale（窗口尺寸不变，画面保持 16:9 固定比例），
     * 范围 0.2~4.0（默认 1.0），越界收敛；渲染矩阵同步更新。 */
    private void applyCamScale(float newScale) {
        if (newScale < CAM_SCALE_MIN) newScale = CAM_SCALE_MIN;
        if (newScale > CAM_SCALE_MAX) newScale = CAM_SCALE_MAX;
        camScale = newScale;
        applyRenderTransform();
    }

    /** v1.35 画面与窗口解耦渲染：画面基准尺寸固定为默认窗口尺寸（w_def=屏宽*0.4，h_def=w_def*9/16），
     * 逻辑显示尺寸 = 基准*camScale（16:9 固定比例）；scale = Wv / 真实bufferW（x/y 严格同一因子，等比不拉伸；
     * 显式 m.reset() 后 postScale(Wv/bufferW, Wv/bufferW) 保证 x/y 缩放因子绝对一致，杜绝叠加矩阵不对称变形）；
     * 窗口尺寸直接取 WindowManager.LayoutParams（updateViewLayout 后立即有效，避免 textureView 布局未刷新
     * 导致偏移错位像变形）；translate = 居中 + panX/panY。
     * v1.35 pan 完全自由：不设任何上下/左右边界收敛（用户明确要求自由拖动，允许露出深色底）。
     * 窗口 resize / camScale / pan / surface 变化后重放。 */
    /** v1.50：取消 4:3 固定显示区域。
     * v1.49 把 TextureView 和渲染区域都锁成 4:3，但画面逻辑尺寸是 16:9，
     * 导致默认 16:9 浮窗内画面被横向裁切，区域外露出深色底形成"遮罩"；
     * v1.50 恢复 TextureView 铺满窗口，画面按 16:9 逻辑尺寸等比渲染居中，
     * 不再限制显示区域，避免画面被裁切/遮挡。 */
    private void relayoutTextureView() {
        // v1.50：不再改动布局——TextureView 保持 match_parent 铺满浮窗，
        // 渲染区域与画面逻辑尺寸直接匹配，防止画面被裁切。
    }

    /** v1.58：优先窗 P 渲染入口；MANUAL 手动浮窗仍走 v1.35 通用画面逻辑。 */
    private void applyRenderTransform() {
        if (overlayView == null || textureView == null) return;
        if ("MANUAL".equals(direction)) {
            applyManualTransform();
            return;
        }
        String side = slotPSide != null ? slotPSide : normSide(direction);
        applySlotTransform("P", side);
    }

    /** 手动浮窗渲染（v1.35 逻辑原样保留：MANUAL 使用通用 camScale/pan 字段）。 */
    private void applyManualTransform() {
        if (overlayView == null || textureView == null) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
        int vw = lp.width;
        int vh = lp.height;
        if (vw <= 0 || vh <= 0) {
            vw = textureView.getWidth();
            vh = textureView.getHeight();
        }
        if (vw <= 0) vw = 1;
        if (vh <= 0) vh = 1;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        float defW = dm.widthPixels * OVERLAY_DEF_W_RATIO;
        float defH = defW * 9f / 16f;
        float cs = camScale;
        if (cs < CAM_SCALE_MIN) cs = CAM_SCALE_MIN;
        if (cs > CAM_SCALE_MAX) cs = CAM_SCALE_MAX;
        float Wv = defW * cs;
        float Hv = defH * cs;
        float scale = Wv / BUFFER_W;
        float fit = Math.min(vw / Wv, vh / Hv);
        scale = (Wv / BUFFER_W) * fit;
        float dx = (vw - Wv * fit) / 2f;
        float dy = (vh - Hv * fit) / 2f;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.reset();
        m.postScale(scale, scale);
        m.postTranslate(dx, dy);
        textureView.setTransform(m);
        serviceLog("MANUAL RT vw=" + vw + " vh=" + vh + " scale=" + String.format("%.4f", scale));
    }

    /**
     * v1.58 槽位渲染矩阵：画面配置按「窗 × 侧」取键（pa_l_ / pa_r_ / pb_l_ / pb_r_），
     * 左右状态各有独立缩放与平移；窗口几何（位置/大小）完全不参与矩阵计算，
     * 保证窗口挪位置不改变画面视野。未编辑过（无 cam_scale 键）时按 fitCenter 完整显示。
     */
    private void applySlotTransform(String slot, String side) {
        TextureView tv = slotTexture(slot);
        View host = slotHost(slot);
        if (tv == null || host == null) return;
        side = normSide(side);
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) host.getLayoutParams();
        int vw = lp.width;
        int vh = lp.height;
        if (vw <= 0 || vh <= 0) {
            vw = tv.getWidth();
            vh = tv.getHeight();
        }
        if (vw <= 0) vw = 1;
        if (vh <= 0) vh = 1;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        float defW = dm.widthPixels * OVERLAY_DEF_W_RATIO;
        float defH = defW * 9f / 16f;
        String fp = framePrefixOf(slot, side);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        float cs = prefs.getFloat(fp + "cam_scale", CAM_SCALE_DEFAULT);
        if (cs < CAM_SCALE_MIN) cs = CAM_SCALE_MIN;
        if (cs > CAM_SCALE_MAX) cs = CAM_SCALE_MAX;
        float panXv = prefs.getFloat(fp + "pan_x", 0f);
        float panYv = prefs.getFloat(fp + "pan_y", 0f);
        float Wv = defW * cs;
        float Hv = defH * cs;
        float scale = Wv / BUFFER_W;
        float dx;
        float dy;
        boolean defaultCrop = !prefs.contains(fp + "cam_scale");
        if (defaultCrop) {
            float fit = Math.min(vw / Wv, vh / Hv);
            scale = (Wv / BUFFER_W) * fit;
            dx = (vw - Wv * fit) / 2f;
            dy = (vh - Hv * fit) / 2f;
        } else {
            dx = (vw - Wv) / 2f + panXv;
            dy = (vh - Hv) / 2f + panYv;
        }
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.reset();
        m.postScale(scale, scale);
        m.postTranslate(dx, dy);
        tv.setTransform(m);
        serviceLog("槽位RT[" + slot + "/" + side + "] vw=" + vw + " vh=" + vh
                + " buf=" + bufferW + "x" + bufferH + " Wv=" + (int) Wv + " Hv=" + (int) Hv
                + " pan=(" + (int) panXv + "," + (int) panYv + ") scale=" + String.format("%.4f", scale)
                + " default=" + defaultCrop + " key=" + fp);
    }

    /** v1.33 设置窗口边缘手柄触摸：左右沿 X、上下沿 Y 拖动，改变整个窗口形状（宽高独立变化），
     * 最小尺寸 = 屏宽/屏高*0.15，最大不超屏宽/屏高，越界收敛；
     * left/top 拖动时 x/y 同步以保持对边固定，窗口变化后重放解耦渲染矩阵。 */
    private void setupWindowEdgeHandle(final View handle, final String side) {
        if (handle == null) return;
        handle.setOnTouchListener(new View.OnTouchListener() {
            private int startRaw = 0;
            private int startW = 0;
            private int startH = 0;
            private int startX = 0;
            private int startY = 0;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (overlayView == null || !editMode) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        WindowManager.LayoutParams lp =
                                (WindowManager.LayoutParams) overlayView.getLayoutParams();
                        startW = lp.width;
                        startH = lp.height;
                        startX = lp.x;
                        startY = lp.y;
                        boolean horizontal = "left".equals(side) || "right".equals(side);
                        startRaw = horizontal ? (int) event.getRawX() : (int) event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        boolean h = "left".equals(side) || "right".equals(side);
                        int cur = h ? (int) event.getRawX() : (int) event.getRawY();
                        int d = cur - startRaw;
                        DisplayMetrics dm = new DisplayMetrics();
                        wm.getDefaultDisplay().getRealMetrics(dm);
                        int minW = (int) (dm.widthPixels * MIN_SIZE_RATIO);
                        int minH = (int) (dm.heightPixels * MIN_SIZE_RATIO);
                        WindowManager.LayoutParams lp2 =
                                (WindowManager.LayoutParams) overlayView.getLayoutParams();
                        if ("left".equals(side)) {
                            int newW = startW - d;
                            if (newW < minW) newW = minW;
                            if (newW > dm.widthPixels) newW = dm.widthPixels;
                            // 左边缘拖动：宽变化且 x 同步，保持右边缘固定
                            lp2.width = newW;
                            lp2.x = startX + (startW - newW);
                            if (lp2.x < 0) {
                                lp2.x = 0;
                                lp2.width = startX + startW;
                            }
                        } else if ("right".equals(side)) {
                            int newW = startW + d;
                            if (newW < minW) newW = minW;
                            if (newW > dm.widthPixels) newW = dm.widthPixels;
                            lp2.width = newW;
                        } else if ("top".equals(side)) {
                            int newH = startH - d;
                            if (newH < minH) newH = minH;
                            if (newH > dm.heightPixels) newH = dm.heightPixels;
                            // 上边缘拖动：高变化且 y 同步，保持下边缘固定
                            lp2.height = newH;
                            lp2.y = startY + (startH - newH);
                            if (lp2.y < 0) {
                                lp2.y = 0;
                                lp2.height = startY + startH;
                            }
                        } else {
                            int newH = startH + d;
                            if (newH < minH) newH = minH;
                            if (newH > dm.heightPixels) newH = dm.heightPixels;
                            lp2.height = newH;
                        }
                        try {
                            wm.updateViewLayout(overlayView, lp2);
                        } catch (Exception ignored) {
                        }
                        applyRenderTransform();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });
    }

    /** v1.51：四角等比例缩放手柄——仅编辑模式生效（与 setupWindowEdgeHandle 一致，非编辑 return false）。
     * 拖动逻辑：按下记录 startRawX/startRawY/startW/startH/startX/startY；
     * 移动时按横向/纵向主导方向（|dx|*startH >= |dy|*startW 则横向主导）以当前窗口宽高比 ratio=startW/startH
     * 等比计算 newW=newH*ratio（横向主导）或 newH=newW/ratio（纵向主导）。
     * 锚点规则：tl 固定右下角(startX+startW,startY+startH)，tr 固定左下角(startX,startY+startH)，
     * bl 固定右上角(startX+startW,startY)，br 固定左上角(startX,startY)；
     * 拖动方向含右则 newW=startW+dx，含左则 startW-dx，含下则 newH=startH+dy，含上则 startH-dy。
     * 尺寸 clamp：newW 下限 max(屏宽*0.15, 屏高*0.15*ratio)，上限 min(屏宽, 屏高*ratio)，超出后按 ratio 重算另一边；
     * 锚点计算后 lp.x<0 则贴屏（x=0，width=原右缘X，height=width/ratio 并同步 y 保持对边固定），y<0 同理。 */
    private void setupWindowCornerHandle(final View handle, final String corner) {
        if (handle == null) return;
        handle.setOnTouchListener(new View.OnTouchListener() {
            private int startRawX = 0;
            private int startRawY = 0;
            private int startW = 0;
            private int startH = 0;
            private int startX = 0;
            private int startY = 0;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (overlayView == null || !editMode) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        WindowManager.LayoutParams lp0 =
                                (WindowManager.LayoutParams) overlayView.getLayoutParams();
                        startRawX = (int) event.getRawX();
                        startRawY = (int) event.getRawY();
                        startW = lp0.width;
                        startH = lp0.height;
                        startX = lp0.x;
                        startY = lp0.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) event.getRawX() - startRawX;
                        int dy = (int) event.getRawY() - startRawY;
                        DisplayMetrics dm = new DisplayMetrics();
                        wm.getDefaultDisplay().getRealMetrics(dm);
                        float ratio = (float) startW / startH;
                        int newW = startW;
                        int newH = startH;
                        boolean horizontalDominant = Math.abs(dx) * startH >= Math.abs(dy) * startW;
                        if (horizontalDominant) {
                            // 横向主导：拖动方向含右则 newW=startW+dx，含左则 startW-dx
                            if (corner.contains("r")) newW = startW + dx;
                            else newW = startW - dx;
                            newH = (int) (newW / ratio);
                        } else {
                            // 纵向主导：含下则 newH=startH+dy，含上则 startH-dy
                            if (corner.contains("b")) newH = startH + dy;
                            else newH = startH - dy;
                            newW = (int) (newH * ratio);
                        }
                        if (newW < 1) newW = 1;
                        if (newH < 1) newH = 1;
                        // 尺寸 clamp：宽下限 max(屏宽*0.15, 屏高*0.15*ratio)，上限 min(屏宽, 屏高*ratio)
                        int minW = (int) Math.max(dm.widthPixels * MIN_SIZE_RATIO,
                                dm.heightPixels * MIN_SIZE_RATIO * ratio);
                        int maxW = (int) Math.min(dm.widthPixels, dm.heightPixels * ratio);
                        if (newW < minW) newW = minW;
                        if (newW > maxW) newW = maxW;
                        newH = (int) (newW / ratio);
                        if (newH < 1) newH = 1;
                        WindowManager.LayoutParams lp =
                                (WindowManager.LayoutParams) overlayView.getLayoutParams();
                        lp.width = newW;
                        lp.height = newH;
                        // 锚点规则：tl 固定右下角、tr 固定左下角、bl 固定右上角、br 固定左上角
                        if ("tl".equals(corner)) {
                            lp.x = startX + startW - newW;
                            lp.y = startY + startH - newH;
                        } else if ("tr".equals(corner)) {
                            lp.x = startX;
                            lp.y = startY + startH - newH;
                        } else if ("bl".equals(corner)) {
                            lp.x = startX + startW - newW;
                            lp.y = startY;
                        } else { // br
                            lp.x = startX;
                            lp.y = startY;
                        }
                        // 越界贴屏：x<0 时贴左屏（x=0，width=原右缘X，height=width/ratio 并同步 y 保持对边固定）
                        if (lp.x < 0) {
                            int rightEdge = startX + startW;
                            lp.x = 0;
                            lp.width = rightEdge;
                            lp.height = (int) (rightEdge / ratio);
                            // tl/tr 锚点在下方（底边固定），bl/br 锚点在上方（顶边固定）
                            lp.y = ("tl".equals(corner) || "tr".equals(corner))
                                    ? (startY + startH - lp.height) : startY;
                        }
                        // y<0 同理：贴顶屏（y=0，height=原底缘Y，width=height*ratio，x 保持对边固定）
                        if (lp.y < 0) {
                            int bottomEdge = startY + startH;
                            lp.y = 0;
                            lp.height = bottomEdge;
                            lp.width = (int) (bottomEdge * ratio);
                            // tl/bl 锚点在右缘（右缘固定），tr/br 锚点在左缘（左缘固定）
                            lp.x = ("tl".equals(corner) || "bl".equals(corner))
                                    ? (startX + startW - lp.width) : startX;
                        }
                        try {
                            wm.updateViewLayout(overlayView, lp);
                        } catch (Exception ignored) {
                        }
                        applyRenderTransform();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });
    }

    /** v1.33：浮窗已存在且方向切换时按新方向重新应用位置/窗口形状/画面缩放（旧方向编辑中先自动保存）。 */
    private void applyOverlayConfigForDirection() {
        if (overlayView == null) return;
        if (editMode && !"MANUAL".equals(direction)) {
            saveEditConfigInternal();
        }
        editMode = false;
        if (borderView != null) borderView.setVisibility(View.GONE);
        if (cropHandleL != null) cropHandleL.setVisibility(View.GONE);
        if (cropHandleR != null) cropHandleR.setVisibility(View.GONE);
        if (cropHandleT != null) cropHandleT.setVisibility(View.GONE);
        if (cropHandleB != null) cropHandleB.setVisibility(View.GONE);
        if (cropHandleTL != null) cropHandleTL.setVisibility(View.GONE);
        if (cropHandleTR != null) cropHandleTR.setVisibility(View.GONE);
        if (cropHandleBL != null) cropHandleBL.setVisibility(View.GONE);
        if (cropHandleBR != null) cropHandleBR.setVisibility(View.GONE);
        if (editHintView != null) editHintView.setText("可拖动");
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        // v1.60_beta1：打灯/方向切换一律沿用槽位几何（pa_ + recorded），
        // 严禁再用 left_/right_ 旧键或硬编码 120 覆盖用户预设位置。
        int[] geoDir = readSlotGeo("P", dm);
        int w = geoDir[0];
        int h = geoDir[1];
        camScale = readCamScale();
        panX = readPanX();
        panY = readPanY();
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
        lp.width = w;
        lp.height = h;
        lp.x = geoDir[2];
        lp.y = geoDir[3];
        try {
            wm.updateViewLayout(overlayView, lp);
        } catch (Exception ignored) {
        }
        applyRenderTransform();
        serviceLog("方向切换，已应用 " + direction + " 浮窗配置");
    }

    /** v1.33：主界面"还原浮窗默认"：清除 LEFT/RIGHT 两组全部配置键（位置/窗口形状/画面缩放/recorded/旧 crop/旧 scale）；
     * 浮窗在显示时立即按默认值刷新。 */
    private void resetOverlayDefaultsInternal() {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        ed.remove("left_overlay_w");
        ed.remove("left_overlay_h");
        ed.remove("left_cam_scale");
        ed.remove("left_overlay_scale");
        ed.remove("left_overlay_pos_recorded");
        ed.remove("left_overlay_pos_x");
        ed.remove("left_overlay_pos_y");
        ed.remove("left_crop_l");
        ed.remove("left_crop_t");
        ed.remove("left_crop_r");
        ed.remove("left_crop_b");
        ed.remove("left_pan_x");
        ed.remove("left_pan_y");
        ed.remove("right_overlay_w");
        ed.remove("right_overlay_h");
        ed.remove("right_cam_scale");
        ed.remove("right_overlay_scale");
        ed.remove("right_overlay_pos_recorded");
        ed.remove("right_overlay_pos_x");
        ed.remove("right_overlay_pos_y");
        ed.remove("right_crop_l");
        ed.remove("right_crop_t");
        ed.remove("right_crop_r");
        ed.remove("right_crop_b");
        ed.remove("right_pan_x");
        ed.remove("right_pan_y");
        // v1.43：绑定视角归位 display-mode=8（清除历史绑定残留键）
        ed.remove("left_avm_mode");
        ed.remove("right_avm_mode");
        // v1.60_beta2：主窗几何实际读 pa_/pb_ 键，不清这几组键的话"还原默认"点下去
        // 位置还是旧的（reset 看似无效），必须一并清除。
        for (String gp : new String[] {"pa_", "pb_"}) {
            ed.remove(gp + "overlay_w");
            ed.remove(gp + "overlay_h");
            ed.remove(gp + "overlay_pos_x");
            ed.remove(gp + "overlay_pos_y");
            ed.remove(gp + "overlay_pos_recorded");
            ed.remove(gp + "last_pos_x");
            ed.remove(gp + "last_pos_y");
            ed.remove(gp + "cam_scale");
            ed.remove(gp + "pan_x");
            ed.remove(gp + "pan_y");
        }
        lastPGeom = null;
        ed.apply();
        serviceLog("浮窗配置已还原默认（LEFT/RIGHT 位置/形状/画面）");
        // v1.43：经 AVM 下发一次 display-mode=8，将绑定视角归位为四路鱼眼四宫格
        bgHandler.post(new Runnable() {
            @Override
            public void run() {
                sendAvmMode(8);
            }
        });
        serviceLog("绑定视角已归位 display-mode=8");
        if (overlayView != null) {
            if (editMode && !"MANUAL".equals(direction)) {
                editMode = false;
                if (borderView != null) borderView.setVisibility(View.GONE);
                if (cropHandleL != null) cropHandleL.setVisibility(View.GONE);
                if (cropHandleR != null) cropHandleR.setVisibility(View.GONE);
                if (cropHandleT != null) cropHandleT.setVisibility(View.GONE);
                if (cropHandleB != null) cropHandleB.setVisibility(View.GONE);
                if (editHintView != null) editHintView.setText("可拖动");
            }
            camScale = CAM_SCALE_DEFAULT;
            panX = 0f;
            panY = 0f;
            applyOverlaySizeInternal();
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
            if (!"MANUAL".equals(direction)) {
                lp.x = "LEFT".equals(direction) ? 120 : (dm.widthPixels - lp.width - 120);
                lp.y = dm.heightPixels - lp.height - 120;
            }
            try {
                wm.updateViewLayout(overlayView, lp);
            } catch (Exception ignored) {
            }
            applyRenderTransform();
        }
        Toast.makeText(this, "浮窗已还原默认位置/形状/画面", Toast.LENGTH_SHORT).show();
    }

    /**
     * v1.46：右浮窗对齐左浮窗——将 RIGHT 方向配置复制为 LEFT 的当前生效配置（位置/大小/形状）。
     * 位置：LEFT 有记录则复制记录坐标，未记录则复制其默认贴边显示坐标（保证实际可见位置一致）；
     * 大小：LEFT 有 left_overlay_w/h 键则复制，未编辑过则移除 RIGHT 对应键（两者回到同一默认值）；
     * 形状（画面）：LEFT 有 left_cam_scale 键则连缩放/平移一起复制，未编辑过则移除 RIGHT 画面键
     * （保持与 LEFT 一致的默认裁切显示）。浮窗正在显示右转向浮窗时立即应用。
     */
    private void alignRightToLeftInternal() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int defW = (int) (dm.widthPixels * OVERLAY_DEF_W_RATIO);
        int defH = (int) (defW * 9f / 16f);
        if (defH > dm.heightPixels) defH = dm.heightPixels;

        // 大小：LEFT 未编辑则删除 RIGHT 键回默认（与 LEFT 同一默认值），已编辑则复制 LEFT 键
        if (prefs.contains("left_overlay_w")) {
            ed.putInt("right_overlay_w", prefs.getInt("left_overlay_w", defW));
        } else {
            ed.remove("right_overlay_w");
        }
        if (prefs.contains("left_overlay_h")) {
            ed.putInt("right_overlay_h", prefs.getInt("left_overlay_h", defH));
        } else {
            ed.remove("right_overlay_h");
        }

        // 形状（画面缩放/平移）：LEFT 未编辑则删除 RIGHT 画面键（保持默认裁切），已编辑则整体复制
        if (prefs.contains("left_cam_scale")) {
            ed.putFloat("right_cam_scale", prefs.getFloat("left_cam_scale", CAM_SCALE_DEFAULT));
            ed.putFloat("right_pan_x", prefs.getFloat("left_pan_x", 0f));
            ed.putFloat("right_pan_y", prefs.getFloat("left_pan_y", 0f));
        } else {
            ed.remove("right_cam_scale");
            ed.remove("right_pan_x");
            ed.remove("right_pan_y");
        }

        // 位置：LEFT 有记录复制记录坐标；未记录则复制其默认贴边显示坐标（保证右浮窗与左浮窗实际位置一致）
        int leftW = prefs.getInt("left_overlay_w", defW);
        int leftH = prefs.getInt("left_overlay_h", defH);
        boolean leftRecorded = prefs.getBoolean("left_overlay_pos_recorded", false);
        int leftX = prefs.getInt("left_overlay_pos_x", 120);
        int leftY = prefs.getInt("left_overlay_pos_y", dm.heightPixels - leftH - 120);
        ed.putInt("right_overlay_pos_x", leftX);
        ed.putInt("right_overlay_pos_y", leftY);
        // 无论 LEFT 是否记录过，都按左浮窗实际显示坐标落盘，确保右浮窗完全对齐
        ed.putBoolean("right_overlay_pos_recorded", true);
        ed.apply();

        serviceLog("右浮窗已对齐左浮窗：pos=(" + leftX + "," + leftY + ") size=" + leftW + "x" + leftH
                + (prefs.contains("left_cam_scale") ? " 画面已同步" : " 画面保持默认"));
        // 浮窗正在显示右转向浮窗时立即应用新配置
        if ("RIGHT".equals(direction) && overlayView != null) {
            applyOverlayConfigForDirection();
        }
        Toast.makeText(this, "右浮窗已对齐左浮窗（位置/大小/形状）", Toast.LENGTH_SHORT).show();
    }

    private void removeOverlay() {
        if (overlayView != null) {
            // v1.33：浮窗关闭时若仍在编辑模式，先自动保存当前 位置/窗口形状/画面缩放，防丢失
            if (editMode && !"MANUAL".equals(direction)) {
                saveEditConfigInternal();
                editMode = false;
            }
            // v1.67 beta5：销毁窗口前必须先把 GL 输出从 relay 摘掉。
            // 否则 relay 手里留着一条指向已销毁纹理的 EGLSurface，下次重建窗口时
            // attach 会命中旧记录、拒绝新 SurfaceTexture，画面同样起不来。
            // 注意传 null：只从 relay 摘掉输出、不在此处 release 纹理，
            // 纹理释放统一由 removeView 触发的 onSurfaceTextureDestroyed 完成（传 st 时
            // detachOutput 内部会 st.release()，再叠加销毁回调就会双重释放）。
            if (glRelay != null) {
                try {
                    glRelay.detachOutput("P", null);
                } catch (Throwable ignored) {
                }
            }
            // v1.54 beta3：关闭前移除 BSD 预警红色遮罩（若存在）
            hideRedOverlayOn(overlayView);
            try {
                wm.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        }
        slotPShown = false;
    }

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
            // v1.38：width/height 是 TextureView 视图尺寸（=浮窗窗口尺寸），不是相机输出 buffer 尺寸，
            // 不得写入 bufferW/H；这里强制固定输出 buffer 为 1280x720，保证渲染 scale 与窗口彻底解耦
            st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
            serviceLog("RT view=" + width + "x" + height + " buffer fixed=" + BUFFER_W + "x" + BUFFER_H);
            // v1.33：surface 就绪后重放解耦渲染矩阵（画面居中缩放，超出窗口裁剪）
            applyRenderTransform();
            // v1.69：纹理就绪这一刻必须把这一路重新塞回 GL 输出表。
            // 原实现漏了这一步——replaceSlotTexture() 换完纹理立刻调 refreshRelayOutputs()，
            // 但那时新 TextureView 的 isAvailable() 还是 false，P 路直接被判不合格摘表；
            // 等纹理真就绪了却没人再刷新，该窗只能停最后一帧，直到窗口显隐或探针兜底。
            refreshRelayOutputs();
            if (cameraDevice != null) createPreviewSession();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            // v1.38：窗口 resize（拖浮窗边框改形状/主界面调大小）可能触发 surface 重建/尺寸变更，
            // 这里再次固定输出 buffer 为 1280x720，防止 TextureView 把 buffer 跟随为窗口尺寸、
            // 导致画面按窗口比例拉伸；随后重放解耦渲染矩阵
            st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
            applyRenderTransform();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                        // v1.62：GL 中转模式下该纹理由 relay 作为输出端持有，先摘 EGLSurface 再释放
            if (glRelayMode && glRelay != null) {
                glRelay.detachOutput("P", st);
                requestSessionRebuild(0L);
                return false;
            }
// v1.57 beta5：不再"任一纹理销毁就 releaseCamera()"。
            // 旧窗口的销毁回调可能晚于新窗口的 openCamera 到达，无条件关相机会把刚打开的
            // 设备整体关掉，表现为画面卡住 / 单窗空白。
            if (previewSurfaceTex != null && previewSurfaceTex == st) {
                // 该纹理仍是当前会话目标：先摘出目标，等新会话生效后再释放
                if (previewSurface != null) {
                    pendingSurfaceRelease.add(previewSurface);
                    previewSurface = null;
                }
                pendingTexRelease.add(st);
                previewSurfaceTex = null;
                if (hasAnyPreviewTarget()) {
                    requestSessionRebuild(0L);
                } else if (cameraDevice != null) {
                    releaseCamera();
                }
                return false;
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
            // v1.67 beta3：主窗画面更新探针——每次真正上屏都打一个时间戳。
            // TextureView 只要还在被喂帧就一定会回调；若 relay 侧 swap 正常而这里不再触发，
            // 说明问题在主窗这一段消费侧，而不是中转/相机输入侧。
            lastPUpdatedMs = System.currentTimeMillis();
            pUpdatedTotal++;
        }
    };

    private void openCamera() {
        // v1.57 beta5：单飞保护——并发 openCamera 会产生多个 StateCallback，后到的
        // onError/onDisconnected 会把刚建立的设备关掉，表现为"画面卡住 / 空框"。
        if (cameraDevice != null) {
            Log.i(TAG, "openCamera: 相机已打开，跳过重复打开");
            return;
        }
        // v1.67 beta8：刚释放完相机时不要立刻 open——HAL 尚未释放完，会撞 CAMERA_IN_USE
        long closedAt = lastCameraClosedMs;
        if (closedAt > 0L) {
            long sinceClose = System.currentTimeMillis() - closedAt;
            if (sinceClose >= 0L && sinceClose < CAMERA_REOPEN_GUARD_MS) {
                final long waitMs = CAMERA_REOPEN_GUARD_MS - sinceClose;
                if (!cameraReopenPending) {
                    cameraReopenPending = true;
                    Handler oh = bgHandler != null ? bgHandler : pProbeHandler;
                    Log.i(TAG, "openCamera: 距上次相机释放仅 " + sinceClose + "ms，延后 " + waitMs + "ms 再打开");
                    if (oh != null) {
                        oh.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                cameraReopenPending = false;
                                if (cameraDevice == null && hasAnyPreviewTarget()) {
                                    openCamera();
                                }
                            }
                        }, waitMs);
                    } else {
                        cameraReopenPending = false;
                    }
                }
                return;
            }
        }
        long openNow = System.currentTimeMillis();
        if (cameraOpening && openNow - cameraOpeningAtMs < 3000L) {
            Log.i(TAG, "openCamera: 正在打开中，忽略重复请求");
            return;
        }
        cameraOpening = true;
        cameraOpeningAtMs = openNow;
        // v1.63：每次真正重开相机时重置中转状态；20s 内连续失效过则保持禁用，
        // 避免「尝试中转→卡 1.5s→回退」的反复抖动。
        if (System.currentTimeMillis() - relayLastStallMs > 20000L) {
            glRelayDisabled = false;
            relayStallCount = 0;
        }
        glRelayMode = !glRelayDisabled;
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            cameraOpening = false;
            Log.e(TAG, "no CAMERA permission");
            return;
        }
        try {
            String[] ids = cameraManager.getCameraIdList();
            if (ids.length == 0) {
                Log.e(TAG, "no camera device");
                return;
            }
            cameraId = pickCameraId(ids);
            openedDirection = direction;
            openedCameraId = cameraId;
            cameraManager.openCamera(cameraId, deviceCallback, bgHandler);
        } catch (CameraAccessException | SecurityException e) {
            cameraOpening = false;
            Log.e(TAG, "openCamera failed", e);
            // v1.57 beta4：失败先延迟重试（CAMERA_IN_USE 等瞬时占用），重试耗尽再走 Camera1 回退
            if (scheduleOpenRetry()) {
                return;
            }
            // v1.52：Camera2 打开失败时尝试 Camera1 回退钩子（默认关闭，偏好 camera1_fallback 开启）
            tryCamera1Fallback();
        }
    }

    /** v1.57 beta4：openCamera 失败延迟重试（最多 OPEN_RETRY_MAX 次，间隔递增），避免相机占用后彻底黑屏。 */
    private boolean scheduleOpenRetry() {
        if (openRetryCount >= OPEN_RETRY_MAX) {
            Log.e(TAG, "openCamera 重试次数已达上限 " + OPEN_RETRY_MAX + "，放弃");
            return false;
        }
        openRetryCount++;
        Log.w(TAG, "openCamera 失败，将进行第 " + openRetryCount + " 次延迟重试");
        bgHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (cameraDevice == null && hasAnyPreviewTarget()) {
                    openCamera();
                }
            }
        }, 300L * openRetryCount);
        return true;
    }

    /** v1.57 beta4：当前是否双窗并存（主浮窗 + BSD 右预警窗），双窗共用同一路预览输出。 */
    private boolean isDualPreviewActive() {
        if (bsdRightTextureView != null) return true;
        return bsdRightOverlayShowing || bsdRightOverlayView != null;
    }

    /**
     * v1.57 beta4：已打开的相机能否直接复用（不关不开）。
     * 双窗并存时必须复用——同一路预览输出全部窗口，关开相机只会导致 CAMERA_IN_USE 空框；
     * 单窗时按目标 cameraId 是否与已打开的一致判断，仍保留左右不同镜头配置的切换能力。
     */
    private boolean canReuseOpenedCamera() {
        if (cameraDevice == null) return false;
        if (isDualPreviewActive()) return true;
        if (openedCameraId == null) return false;
        try {
            String[] ids = cameraManager.getCameraIdList();
            if (ids.length == 0) return false;
            return openedCameraId.equals(pickCameraId(ids));
        } catch (CameraAccessException e) {
            Log.e(TAG, "canReuseOpenedCamera 取摄像头列表失败", e);
            return false;
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * 按当前方向选择摄像头 ID：优先使用测试页配置的左/右镜头 ID，
     * 未配置时统一回退 ids[0]（video51 鱼眼广角）。
     * v1.28 起仅作为未绑定 AVM 视角时的兜底；v1.36 右转向未配置时与左转向一致用 ids[0]，
     * 避免右转向落到 ids[1]（video52）导致画面缺一块（实车确认左转向 video51 画面完整）。 */
    private String pickCameraId(String[] ids) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // v1.54 beta6：BSD 编辑态优先读 bsd_ 前缀 cameraId（左 bsd_left_camera_id、
        // 右 bsd_right_camera_id），回退打灯键，保证编辑窗画面与预警浮窗同源
        if (bsdEditActive) {
            String bsdKey = "LEFT".equals(direction) ? "bsd_left_camera_id" : "bsd_right_camera_id";
            String bsdSaved = prefs.getString(bsdKey, null);
            if (bsdSaved != null) {
                for (String id : ids) {
                    if (id.equals(bsdSaved)) return id;
                }
            }
        }
        String key = "LEFT".equals(direction) ? "left_camera_id" : "right_camera_id";
        String saved = prefs.getString(key, null);
        if (saved != null) {
            for (String id : ids) {
                if (id.equals(saved)) return id;
            }
        }
        return ids[0];
    }

    /**
     * v1.41：左右转向灯固定绑定 display-mode=8（四路鱼眼四宫格画面），不再依赖/暴露用户绑定配置；
     * 返回值恒为 8，忽略 prefs 中 left_avm_mode/right_avm_mode 的残留值。
     */
    private int getAvmBinding(String dir) {
        return 8;
    }

    // ---- v1.28 AVM 视角下发（复用 CameraTestActivity 已验证链路：先取后改再送）----

    /** 转向灯打灯时经 AVM 下发绑定视角（display-mode），不打开物理摄像头。 */
    private void sendAvmMode(int displayMode) {
        Log.i(TAG, "AVM: 转向灯下发视角 mode=" + displayMode);
        Object params = callGetCmdControlParameters();
        if (params == null) {
            Log.e(TAG, "AVM: getCmdControlParameters 返回 null/调用失败，转向灯 AVM 下发失败");
            return;
        }
        boolean written = writeAvmDisplayMode(params, displayMode);
        if (!written) {
            Log.e(TAG, "AVM: 三优先级写入均失败，无法下发 display-mode=" + displayMode);
            return;
        }
        if (!callSetCmdControlParameters(params)) {
            Log.e(TAG, "AVM: setCmdControlParameters 调用失败，转向灯 AVM 下发失败");
        }
    }

    /** 反射调用 CameraManager.getCmdControlParameters()，返回参数对象或 null。 */
    private Object callGetCmdControlParameters() {
        try {
            for (Method m : cameraManager.getClass().getMethods()) {
                if ("getCmdControlParameters".equals(m.getName()) && m.getParameterTypes().length == 0) {
                    Object ret = m.invoke(cameraManager);
                    Log.i(TAG, "AVM: getCmdControlParameters 调用成功 -> " + (ret == null ? "null" : ret.getClass().getName()));
                    return ret;
                }
            }
            Log.e(TAG, "AVM: CameraManager 无 getCmdControlParameters() 方法（手机无此接口属预期）");
        } catch (Exception e) {
            Log.e(TAG, "AVM: getCmdControlParameters 调用异常: " + e);
        }
        return null;
    }

    /** 反射调用 CameraManager.setCmdControlParameters：收集全部重载，按优先级依次尝试。 */
    private boolean callSetCmdControlParameters(Object params) {
        String flatten = null;
        try {
            flatten = (String) params.getClass().getMethod("flatten").invoke(params);
        } catch (Exception e) {
            Log.e(TAG, "AVM: flatten() 调用失败: " + e);
        }
        try {
            List<Method> overloads = new ArrayList<>();
            for (Method m : cameraManager.getClass().getMethods()) {
                if ("setCmdControlParameters".equals(m.getName())) {
                    overloads.add(m);
                }
            }
            if (overloads.isEmpty()) {
                Log.e(TAG, "AVM: CameraManager 无 setCmdControlParameters 方法（手机无此接口属预期）");
                return false;
            }
            for (Method m : overloads) {
                if (m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0].isInstance(params)) {
                    return tryInvokeSet(m, params);
                }
            }
            if (flatten != null) {
                for (Method m : overloads) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 2 && pts[0] == int.class && pts[1] == String.class) {
                        return tryInvokeSet(m, 0, flatten);
                    }
                }
                for (Method m : overloads) {
                    if (m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == String.class) {
                        return tryInvokeSet(m, flatten);
                    }
                }
            }
            for (Method m : overloads) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 2 && pts[0] == int.class && pts[1].isInstance(params)) {
                    return tryInvokeSet(m, 0, params);
                }
            }
            Log.e(TAG, "AVM: 所有 setCmdControlParameters 重载均尝试失败或参数不匹配");
        } catch (Exception e) {
            Log.e(TAG, "AVM: setCmdControlParameters 调用异常: " + e);
        }
        return false;
    }

    /** 调用一次 setCmdControlParameters 重载并记录命中/失败签名（含参数类型）。 */
    private boolean tryInvokeSet(Method m, Object... args) {
        try {
            m.invoke(cameraManager, args);
            Log.i(TAG, "AVM: setCmdControlParameters 命中重载 " + buildMethodSig(m));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "AVM: setCmdControlParameters 重载 " + buildMethodSig(m) + " 调用失败: " + e);
        }
        return false;
    }

    /** 生成方法完整签名：返回类型 方法名(参数类型列表)。 */
    private static String buildMethodSig(Method m) {
        StringBuilder ps = new StringBuilder();
        for (Class<?> pt : m.getParameterTypes()) {
            if (ps.length() > 0) ps.append(", ");
            ps.append(pt.getName());
        }
        return m.getReturnType().getName() + " " + m.getName() + "(" + ps + ")";
    }

    /** 三优先级反射写入 display-mode（依据实车 dump 确认的 CameraManager$Parameters 真实结构）。 */
    private boolean writeAvmDisplayMode(Object params, int value) {
        Class<?> cls = params.getClass();
        try {
            Method m = cls.getMethod("setAvmDisplayMode", int.class);
            m.invoke(params, value);
            Log.i(TAG, "AVM: 优先级1 setAvmDisplayMode(int) 调用成功 = " + value);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "AVM: 优先级1 setAvmDisplayMode(int) 失败: " + e);
        }
        try {
            Method m = cls.getMethod("set", String.class, int.class);
            m.invoke(params, "display-mode", value);
            Log.i(TAG, "AVM: 优先级2 set(String,int) 调用成功 display-mode=" + value);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "AVM: 优先级2 set(String,int) 失败: " + e);
        }
        try {
            Method m = cls.getDeclaredMethod("put", String.class, String.class);
            m.setAccessible(true);
            m.invoke(params, "display-mode", String.valueOf(value));
            Log.i(TAG, "AVM: 优先级3 私有 put(String,String) 调用成功 display-mode=" + value);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "AVM: 优先级3 私有 put(String,String) 失败: " + e);
        }
        return false;
    }

    /** 仅关闭摄像头通道（保留 TextureView/SurfaceTexture），用于方向切换后按新方向重开。 */
    private void closeCameraOnly() {
        // v1.67 beta8：记录释放时刻，供 openCamera 冷却判定
        lastCameraClosedMs = System.currentTimeMillis();
        cameraReopenPending = false;
        // v1.67 beta6：立即收相机即作废在途的延迟释放计时（幂等）
        cancelCameraLinger();
        // v1.57 beta4：主机通道释放时同步复位会话重建状态，避免残留飞行锁阻塞后续重建
        sessionCreating = false;
        sessionRebuildPending = false;
        // v1.60：同时作废会话签名，否则重新开相机时 doRebuildSession 会因签名相同而跳过重建
        sessionTargetSig = "";
        // v1.52：Camera1 回退实例一并释放（幂等）
        if (camera1Preview != null) {
            camera1Preview.close();
            camera1Preview = null;
        }
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        previewSurfaceTex = null;
        // v1.54：BSD 右浮窗 surface 一并释放
        if (bsdRightSurface != null) {
            bsdRightSurface.release();
            bsdRightSurface = null;
        }
        bsdRightSurfaceTex = null;
        // v1.54 beta4：BSD 编辑窗 surface 一并释放
        if (bsdEditLeft != null) {
            bsdEditLeft.releaseSurface();
        }
        if (bsdEditRight != null) {
            bsdEditRight.releaseSurface();
        }
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice device) {
            cameraDevice = device;
            cameraOpening = false;
            openRetryCount = 0;
            sessionRetryCount = 0;
            // v1.54 beta4：无主窗 textureView（BSD 编辑窗独立预览）时也建立会话
            if (hasAnyPreviewTarget()) {
                createPreviewSession();
            }
        }

        @Override
        public void onDisconnected(CameraDevice device) {
            // v1.57 beta4：断开后原逻辑只置空 cameraDevice，双窗随之永久空框；改为释放并按需延迟重开
            releaseDeviceAndRetry(device, "onDisconnected");
        }

        @Override
        public void onError(CameraDevice device, int error) {
            // v1.57 beta4：CAMERA_IN_USE 等错误后按需延迟重开（避免双窗空框无法自愈）
            releaseDeviceAndRetry(device, "onError error=" + error);
        }
    };

    /** v1.57 beta4：相机断开/出错后释放设备与会话，并在仍有预览目标时延迟重开。 */
    private void releaseDeviceAndRetry(CameraDevice device, String reason) {
        Log.w(TAG, "相机回调 " + reason + "，释放设备并按需重开");
        cameraOpening = false;
        sessionTargetSig = "";
        sessionReqSeq++;
        try {
            device.close();
        } catch (Exception ignored) {
        }
        if (cameraDevice == device) {
            cameraDevice = null;
            openedCameraId = null;
        }
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (Exception ignored) {
            }
            captureSession = null;
        }
        sessionCreating = false;
        if (!scheduleOpenRetry()) {
            Log.e(TAG, "相机不可用且重试已耗尽，等待重新打灯/重开浮窗");
        }
    }

    /** v1.54 beta4：是否存在任一可用预览目标（主窗 / BSD 右窗 / BSD 编辑窗）。 */
    private boolean hasAnyPreviewTarget() {
        // v1.60_beta4：与 collectTargets 同口径——隐藏槽位不算预览目标，
        // 否则"只剩隐藏窗"时相机会一直挂着不出帧。
        if (slotPShown && textureView != null && textureView.isAvailable()) return true;
        if (slotSShown && bsdRightTextureView != null && bsdRightTextureView.isAvailable()) return true;
        if (slotEditP != null && slotEditP.isTargetReady()) return true;
        if (slotEditS != null && slotEditS.isTargetReady()) return true;
        return false;
    }

    /**
     * v1.57 beta5：预览会话统一入口——请求式重建。
     * 与 beta4 的差别：
     * 1) 所有触发点只"请求"，由去抖合并（双开时主窗/右窗/方向切换会在同一帧内连续触发
     *    3~4 次整条会话重建，车机 HAL 上来不及协商 -> 空框 / 冻结）；
     * 2) target 集合签名未变化时直接跳过，不做无意义重建；
     * 3) 关闭旧会话与新会话创建之间留 40ms，等 close 生效，避免"新会话失败且旧会话已关"
     *    的双输局面（画面永久冻结在最后一帧）；
     * 4) 失败重试用尽后整机重启相机自愈；过期会话按序号丢弃。
     */
    private void createPreviewSession() {
        requestSessionRebuild(60L);
    }

    /** v1.60_beta2：会话配置起始时间，用于 sessionCreating 卡死自愈。 */
    private long sessionCreatingSinceMs = 0L;
    /** v1.60_beta2：连续会话异常计数，达阈值强制重建窗口纹理。 */
    private int sessionFailStreak = 0;

    /** v1.60_beta2：优先窗 P 最近一次「真实应用」的几何（w,h,x,y）。
     * 槽位隐藏时 lp 会被改成 SLOT_HIDDEN_X/Y(-30000)，此时保存必须回退到这份内存几何，
     * 否则要么把隐藏坐标写进 prefs（主窗读回时越界回退默认），要么被迫丢掉本次保存。 */
    private int[] lastPGeom = null;

    private void requestSessionRebuild(long delayMs) {
        if (cameraDevice == null) {
            if (hasAnyPreviewTarget()) {
                openCamera();
            }
            return;
        }
        sessionRebuildPending = true;
        if (bgHandler == null) return;
        bgHandler.removeCallbacks(sessionRebuildRunnable);
        bgHandler.postDelayed(sessionRebuildRunnable, delayMs);
    }

    private final Runnable sessionRebuildRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sessionRebuildPending) return;
            if (sessionCreating) {
                // 有会话配置在途：稍后再试，绝不并发 configure
                // v1.60_beta2：加看门狗——HAL 若没回调 onConfigured/onConfigureFailed（相机异常、
                // 目标 Surface 失效等），sessionCreating 会永久为 true，重建被无限延后，
                // 画面从此冻死。超过 1200ms 未回调即强制复位并立即重建一次（自愈）。
                if (sessionCreatingSinceMs > 0L
                        && System.currentTimeMillis() - sessionCreatingSinceMs > 1200L) {
                    serviceLog("会话配置超时未回调（>1200ms），强制复位并重建（自愈）");
                    recoverOverlayTexture("onConfigured 超时");
                    sessionCreating = false;
                    sessionCreatingSinceMs = 0L;
                    sessionReqSeq++;
                    sessionTargetSig = "";
                } else {
                    bgHandler.postDelayed(this, 60L);
                    return;
                }
            }
            sessionRebuildPending = false;
            doRebuildSession();
        }
    };

    /** v1.60_beta2：会话配置看门狗——独立自调度，不依赖重建队列是否仍有待办。
     * 旧实现只在 sessionRebuildRunnable 里检查超时，而该 runnable 一旦执行到
     * doRebuildSession（pending 已置 false）就不再重投递；此后若 HAL 永久不回调
     * onConfigured/onConfigureFailed，sessionCreating 永远为 true、重建被无限期延后，
     * 画面就冻在最后一帧——这是"画面还是会卡死"的核心原因之一。 */
    private final Runnable sessionWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!sessionCreating || sessionCreatingSinceMs <= 0L) {
                return;
            }
            long waited = System.currentTimeMillis() - sessionCreatingSinceMs;
            if (waited < 1200L) {
                if (bgHandler != null) {
                    bgHandler.postDelayed(this, 300L);
                }
                return;
            }
            serviceLog("会话配置看门狗：等待 " + waited + "ms 仍无回调，强制复位并重建");
            sessionCreating = false;
            sessionCreatingSinceMs = 0L;
            sessionReqSeq++;
            sessionTargetSig = "";
            recoverOverlayTexture("看门狗超时");
            requestSessionRebuild(60L);
        }
    };

    /** 真正执行会话重建（bgHandler 线程）。目标集未变更则跳过。 */
    private void doRebuildSession() {
        if (cameraDevice == null) return;
        java.util.ArrayList<Surface> targets = new java.util.ArrayList<>();
        String sig = collectTargets(targets);
        if (targets.isEmpty()) {
            // v1.67 beta8 修复：延迟释放窗口内的空目标是预期状态（窗口已收、相机与会话保活），
            // 绝不能当作"该收相机"。beta6/beta7 在这里被击穿，L1 等于没做。
            if (cameraLingerActive && cameraDevice != null) {
                sessionTargetSig = "RELAY";
                return;
            }
            // v1.60_beta4：编辑窗已创建但纹理还没就绪（addView 后要等一帧布局），
            // 此时 targets 为空不代表"该收相机"。旧实现直接 releaseCamera，
            // 打灯有画面时双击浮窗就变成了"先关相机再重开"，重开期间窗口全黑，
            // 用户看到的就是那个空白框。这里改为稍后重试，保持相机会话。
            if (slotEditP != null || slotEditS != null) {
                sessionTargetSig = "WAIT_EDIT_TEXTURE";
                final int retrySeq = sessionReqSeq;
                if (bgHandler != null) {
                    bgHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (retrySeq != sessionReqSeq) return;
                            if (cameraDevice == null) return;
                            requestSessionRebuild(60L);
                        }
                    }, 120L);
                }
                return;
            }
            // 目标全部消失：整机释放（此时已无会话使用这些 Surface，可安全释放）
            sessionTargetSig = "";
            releaseCamera();
            return;
        }
        if (captureSession != null && sig.equals(sessionTargetSig)) {
            return;
        }
        sessionCreating = true;
        sessionCreatingSinceMs = System.currentTimeMillis();
        if (bgHandler != null) {
            bgHandler.removeCallbacks(sessionWatchdog);
            bgHandler.postDelayed(sessionWatchdog, 1200L);
        }
        final int seq = ++sessionReqSeq;
        final String newSig = sig;
        if (captureSession != null) {
            CameraCaptureSession old = captureSession;
            captureSession = null;
            try {
                old.close();
            } catch (Exception ignored) {
            }
        }
        final java.util.ArrayList<Surface> tgts = targets;
        bgHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (seq != sessionReqSeq) return;
                if (cameraDevice == null) {
                    sessionCreating = false;
                    return;
                }
                startSession(tgts, newSig, seq);
            }
        }, 40L);
    }

    /**
     * v1.64：输出端可绘制性校验。
     *
     * <p>TextureView 的 BufferQueue 是同步队列，只要 hwui 不再为它出帧（View 尺寸为 0、
     * 父窗口 alpha=0、已 detach、无 windowToken），队列就永远不被消费，接下来这次
     * eglSwapBuffers 会永久阻塞 —— 线程一死，其余输出连同相机输入全部停帧。
     * 所以在把某一路塞进 GL 输出表之前，先确认它真的在屏幕上、真的有尺寸。</p>
     */
    private boolean isOutputDrawable(View root, View tex) {
        return isOutputAttachable(root, tex) && isOutputVisible(root);
    }

    /**
     * v1.67：硬条件——这一路能不能作为 GL 输出目标。
     * 尺寸 0 / 未 attach / 无 windowToken 时，eglCreateWindowSurface 会直接失败，
     * 而且每轮巡检都会重试一次，必须真的摘表。
     */
    private boolean isOutputAttachable(View root, View tex) {
        if (tex == null || tex.getWidth() <= 0 || tex.getHeight() <= 0) return false;
        if (!tex.isShown() || tex.getWindowToken() == null) return false;
        return true;
    }

    /**
     * v1.67：软条件——这一路现在看不看得见。
     * alpha < 0.01 只是「视觉上不可见」（面板淡出/收起过渡），承载面完全正常。
     * 旧实现把它和硬条件混在一起判否，结果是每次淡入淡出都要拆掉再重建全套
     * 输出线程与 EGL 资源，正是资源翻腾的主要来源之一。
     */
    private boolean isOutputVisible(View root) {
        return root == null || root.getAlpha() >= 0.01f;
    }

    /**
     * v1.65：构建当前应当出现在 GL 中转输出表里的全部目标。
     * 只读窗口状态、不碰相机会话，因此可以被高频调用（对账用）。
     */
    private java.util.ArrayList<GlCameraRelay.Target> buildRelayOutputs() {
        java.util.ArrayList<GlCameraRelay.Target> outs = new java.util.ArrayList<>();
        if (slotPShown && textureView != null && textureView.isAvailable()
                && textureView.getSurfaceTexture() != null) {
            if (isOutputAttachable(overlayView, textureView)) {
                GlCameraRelay.Target t = GlCameraRelay.Target.ofTexture("P",
                        textureView.getSurfaceTexture());
                if (t != null) {
                    // v1.67：不可见只暂停绘制，不摘表、不拆线程
                    t.paused = !isOutputVisible(overlayView);
                    outs.add(t);
                }
            }
        }
        if (slotSShown && bsdRightTextureView != null && bsdRightTextureView.isAvailable()
                && bsdRightTextureView.getSurfaceTexture() != null) {
            if (isOutputAttachable(bsdRightOverlayView, bsdRightTextureView)) {
                GlCameraRelay.Target t = GlCameraRelay.Target.ofTexture("S",
                        bsdRightTextureView.getSurfaceTexture());
                if (t != null) {
                    t.paused = !isOutputVisible(bsdRightOverlayView);
                    outs.add(t);
                }
            }
        }
        if (slotEditP != null) {
            GlCameraRelay.Target t = slotEditP.outputTarget("EDIT_P");
            if (t != null) outs.add(t);
        }
        if (slotEditS != null) {
            GlCameraRelay.Target t = slotEditS.outputTarget("EDIT_S");
            if (t != null) outs.add(t);
        }
        if (iclProjectionEnabled) {
            Surface iclSurface = icProjector.getInputSurface();
            if (iclSurface != null && iclSurface.isValid()) {
                GlCameraRelay.Target t = GlCameraRelay.Target.ofSurface("ICL", iclSurface);
                if (t != null) outs.add(t);
            }
        }
        return outs;
    }

    /**
     * v1.65：只刷新 GL 中转的输出分发表，不重建会话、不动相机（在 bgHandler 上执行）。
     *
     * <p>为什么必须有它：中转模式下会话签名恒为 "RELAY"，doRebuildSession 见到签名未变
     * 就直接 return；而 TextureView 一旦被替换（窗口重建 / 阻塞自愈换纹理），新纹理
     * 只有在下一次 setOutputs 里才会进表。漏掉这一次，该窗就永久停在最后一帧。
     * 所以任何窗口显隐 / 纹理更换之后都必须调它。</p>
     */
    private void refreshRelayOutputs() {
        if (!glRelayMode || glRelayDisabled) return;
        final android.os.Handler bh = bgHandler;
        if (bh == null) return;
        if (android.os.Looper.myLooper() != bh.getLooper()) {
            bh.post(new Runnable() {
                @Override
                public void run() {
                    refreshRelayOutputs();
                }
            });
            return;
        }
        GlCameraRelay relay = glRelay;
        if (relay == null || !relay.isAlive()) return;
        java.util.ArrayList<GlCameraRelay.Target> outs = buildRelayOutputs();
        relay.setBaseIntervalMs(0L);
        relay.setOutputs(outs);
    }

    /**
     * v1.62：收集当前所有预览目标并生成签名。
     *
     * <p>与旧实现的本质区别：相机侧永远只有 1 路输出——GL 中转的离屏输入面。
     * 窗口数量变化（单窗 → 双窗 → 带编辑窗 → 带仪表投影）只影响 GL 的输出分发表，
     * 不再触发 CaptureSession 重建，因此不会再出现「双开 configure 失败 / 两路都饿死」。</p>
     */
    private String collectTargets(java.util.ArrayList<Surface> targets) {
        if (!glRelayMode || glRelayDisabled) {
            return collectTargetsDirect(targets);
        }
        GlCameraRelay relay = ensureRelay();
        java.util.ArrayList<GlCameraRelay.Target> outs = buildRelayOutputs();
        if (outs.isEmpty()) {
            // 所有窗口都不可见：清空 GL 输出表，交给上层释放相机
            relay.setOutputs(outs);
            relayWatchFrames = -1L;
            relayWatchChangeMs = 0L;
            // v1.67 beta6：延迟释放窗口内不改签名。窗口已收但相机与会话仍在保活，
            // 此时若按空签名重建会话，1.2s 内再打灯就得重新协商 HAL，延迟释放失去意义。
            // 相机侧目标恒为 relay 输入面，这里继续报 RELAY 与实际会话一致。
            if (cameraLingerActive && cameraDevice != null) {
                // v1.67 beta8 修复：延迟窗口内必须把 relay 输入面照旧上报为会话目标。
                // beta6 只改签名不改 targets，doRebuildSession 见 targets 为空就直接
                // releaseCamera()，熄灯纹理销毁回调一路把保活中的相机整机释放，
                // L1 延迟销毁被击穿，还额外制造 close/open 竞态——就是"打灯更慢"的来源。
                Surface lingerIn = relay.obtainInputSurface();
                if (lingerIn != null && lingerIn.isValid()) {
                    targets.add(lingerIn);
                }
                return "RELAY";
            }
            return "";
        }
        // v1.64：单路也走中转，不再切回直连。
        // v1.63 在「单路直连 / 多路中转」之间来回切，屏幕状态只要抖动一次
        // （TextureView 瞬间 0 尺寸、淡入期 alpha<1、刚 addView 还没 attach），
        // 整条渲染链路就被换掉并重建会话——表现正是「刚出画面 2 秒又卡住」。
        // 新版中转把每一路放在独立线程里画，单路走中转同样不会被下游队列阻塞，
        // 因此这里统一走中转，让相机侧签名恒为 RELAY，全程不重建。
        // 先确保 GL 就绪（同步初始化），再下发输出表，避免首帧输出被丢弃
        Surface in = relay.obtainInputSurface();
        if (in == null || !in.isValid()) {
            glRelayMode = false;
            serviceLog("GL 中转初始化失败，回退直连相机（多窗仍可能受限）");
            return collectTargetsDirect(targets);
        }
        // v1.64：不再人为压帧率。每路输出已有独立线程，某一路慢只会影响它自己，
        // 需要节流时由 GlCameraRelay 按该路真实 swap 耗时自适应下调，其余路照常满帧。
        relay.setBaseIntervalMs(0L);
        relay.setOutputs(outs);
        targets.add(in);
        relayWatchFrames = -1L;
        relayWatchChangeMs = System.currentTimeMillis();
        startRelayWatchdog();
        // 相机侧签名恒定：只要中转在，目标集合永不变化 → 会话不再因窗口增减而重建
        return "RELAY";
    }

    /** v1.62：把编辑窗（左右两个都试一遍）从 GL 输出表摘除并释放其纹理。 */
    private void detachRelayOutput(String key) {
        if (glRelay == null) return;
        SurfaceTexture st = null;
        if (slotEditP != null && key.equals("EDIT_P")) st = slotEditP.currentTexture();
        if (slotEditS != null && key.equals("EDIT_S")) st = slotEditS.currentTexture();
        glRelay.detachOutput(key, st);
        requestSessionRebuild(0L);
    }

    /** v1.62：惰性创建 GL 中转。 */
    private GlCameraRelay ensureRelay() {
        if (glRelay == null) {
            glRelay = new GlCameraRelay(BUFFER_W, BUFFER_H);
            // v1.64：某一路输出阻塞时只隔离该路，这里换掉它的 TextureView 让画面回来
            glRelay.setOutputStallListener(new GlCameraRelay.OutputStallListener() {
                @Override
                public void onOutputStalled(String key) {
                    recoverStalledOutput(key);
                }
            });
        }
        return glRelay;
    }

    /**
     * v1.64：某一路 GL 输出被判定阻塞（该路 swap 未返回）时的自愈入口。
     *
     * <p>阻塞的是「这一路到它自己的 TextureView 队列」这一段，所以解药就是换掉那一路的
     * TextureView：新 SurfaceTexture = 全新 BufferQueue = 全新读端，旧队列连同卡住的
     * swap 一起报废，其他窗口与相机输入全程不受影响。</p>
     */
    /** v1.69：自愈冷却台账 key -> [窗口起点ms, 窗口内已修次数]。 */
    private final java.util.HashMap<String, long[]> stallHealHistory = new java.util.HashMap<>();

    /** v1.69：自愈冷却窗口。 */
    private static final long HEAL_WINDOW_MS = 30000L;

    /** v1.69：同一路在一个冷却窗口内最多修几次。 */
    private static final int HEAL_MAX_PER_WINDOW = 2;

    private void recoverStalledOutput(final String key) {
        if (mainHandler == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    // v1.69：自愈节流。判死一次就换一次纹理，而新纹理出首帧本身要几百毫秒，
                    // 这段时间里很容易再被判死一次，无节制地换下去就是「画面反复黑一下又回来」
                    // 的卡死感（也正是「能跳出来」的来源）。同一路 30 秒内只修 2 次，
                    // 第 3 次起只记录不动手，交给窗口显隐 / 下一次打灯自然重建。
                    String hk = key == null ? "?" : key;
                    long nowMs = System.currentTimeMillis();
                    synchronized (stallHealHistory) {
                        long[] rec = stallHealHistory.get(hk);
                        if (rec == null || nowMs - rec[0] > HEAL_WINDOW_MS) {
                            rec = new long[]{nowMs, 0L};
                            stallHealHistory.put(hk, rec);
                        }
                        if (rec[1] >= HEAL_MAX_PER_WINDOW) {
                            serviceLog("输出 " + hk + " 自愈冷却中（" + (HEAL_WINDOW_MS / 1000L)
                                    + "s 内已修 " + rec[1] + " 次），本次只记录不动手");
                            return;
                        }
                        rec[1]++;
                        serviceLog("输出 " + hk + " 自愈第 " + rec[1] + " 次（窗口 "
                                + (HEAL_WINDOW_MS / 1000L) + "s 内限 " + HEAL_MAX_PER_WINDOW + " 次）");
                    }
                    if ("P".equals(key)) {
                        replaceSlotTexture(true);
                    } else if ("S".equals(key)) {
                        replaceSlotTexture(false);
                    } else if (glRelay != null) {
                        glRelay.detachOutput(key, null);
                        sessionTargetSig = "";
                        requestSessionRebuild(0L);
                    }
                } catch (Throwable t) {
                    serviceLog("重启受阻输出失败：" + t);
                }
            }
        });
    }

    /**
     * v1.64：替换主窗（primary=true）或次级窗的 TextureView，窗口本身、位置、尺寸、
     * 缩放平移参数全部保持原样，只是换一块全新的画面承载面。
     */
    private void replaceSlotTexture(boolean primary) {
        View rootView = primary ? overlayView : bsdRightOverlayView;
        TextureView old = primary ? textureView : bsdRightTextureView;
        if (!(rootView instanceof FrameLayout) || old == null) return;
        FrameLayout root = (FrameLayout) rootView;
        TextureView.SurfaceTextureListener listener =
                primary ? surfaceListener : bsdRightSurfaceListener;

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        android.view.ViewGroup.LayoutParams olp = old.getLayoutParams();
        if (olp instanceof FrameLayout.LayoutParams) {
            lp = (FrameLayout.LayoutParams) olp;
        }

        TextureView nv = new TextureView(this);
        nv.setLayoutParams(lp);
        nv.setOpaque(old.isOpaque());
        nv.setAlpha(old.getAlpha());
        nv.setVisibility(old.getVisibility());
        if (old.getBackground() != null) nv.setBackground(old.getBackground());
        nv.setSurfaceTextureListener(listener);
        // v1.64b：必须插回「原父容器 + 原层级」，TextureView 位于 bg_panel 内，
        // 直接 addView 到根 FrameLayout 会破坏层级（被 bg_panel 挡住或盖住按钮）
        android.view.ViewGroup parent = (old.getParent() instanceof android.view.ViewGroup)
                ? (android.view.ViewGroup) old.getParent() : (android.view.ViewGroup) root;
        int idx = parent.indexOfChild(old);
        parent.addView(nv, idx < 0 ? 0 : idx);

        // v1.64b：先把这一路从 GL 输出表摘掉，旧 SurfaceTexture 马上失效，
        // 否则 relay 会继续往死纹理 swap（第二次卡死的入口）
        if (glRelay != null) {
            try {
                glRelay.detachOutput(primary ? "P" : "S", old.getSurfaceTexture());
            } catch (Throwable ignored) {
            }
        }
        // 先解绑再移除：st 释放会让卡在 swap 里的旧线程立刻返回并退出
        old.setSurfaceTextureListener(null);
        ((android.view.ViewGroup) old.getParent() == null ? root
                : (android.view.ViewGroup) old.getParent()).removeView(old);

        if (primary) {
            textureView = nv;
        } else {
            bsdRightTextureView = nv;
        }
        serviceLog((primary ? "主窗" : "次级窗") + "纹理已更换（输出阻塞自愈）");
        // v1.65：新纹理必须立刻进 GL 输出表，否则这一窗会一直停在最后一帧
        refreshRelayOutputs();
        requestSessionRebuild(0L);
    }

    // ================= v1.67 beta3：主窗（key="P"）画面更新探针 =================

    /**
     * 主窗更新探针巡检：每 5 秒一次，与中转停帧看门狗相互独立调度。
     *
     * <p>判据：主窗应当在显示（slotPShown 且 relay 表内有 P 路），但
     * onSurfaceTextureUpdated 超过 5 秒没被调用 → 主窗画面已冻。此刻把
     * 主窗回调次数 / 窗口状态 / relay P 路内部状态 / 输入帧数 / 相机与中转状态
     * 一次性记成一条，直接区分三个候选：
     * A 主窗被标 paused（记录里「暂停=1」）；
     * B relay 正常 swap 但主窗不再显示（「末次swap=xxms前」很小、回调却不涨）；
     * C 已降级直连（「中转=已降级直连」）。</p>
     *
     * <p>只读 service 自身状态与 relay 表，不碰 TextureView / View 属性，
     * 因此可以在 bgHandler 上安全执行。</p>
     */
    private void checkPFrameProbe() {
        final long now = System.currentTimeMillis();
        final GlCameraRelay relay = glRelay;
        // v1.67 beta4：窗口是否在屏以 View 为准。beta3 只认 slotPShown，一旦状态位与
        // 实际窗口错位（slotPShown=false 但窗口仍挂在 WindowManager 上），探针就永远沉默。
        final boolean windowUp = (overlayView != null) || slotPShown;
        final boolean outputOk = (relay == null) || relay.hasOutput("P");
        if (!windowUp) {
            // 主窗真的没在显示（收起 / 未创建）：不判，也不留旧时间戳，免得收起再打开时误报
            lastPUpdatedMs = 0L;
            pProbeAnchorMs = 0L;
            pStallRecorded = false;
            pProbeGateSince = 0L;
            return;
        }
        // v1.67 beta4：状态错位单独打点——过去这两种情况被当成"不在显示"直接跳过，
        // 于是主窗明明在屏站着、面板却一条记录都没有。
        if (!slotPShown || !outputOk) {
            if (pProbeMismatchLoggedAt == 0L || now - pProbeMismatchLoggedAt >= 30000L) {
                pProbeMismatchLoggedAt = now;
                recordProbe("窗口在屏但状态异常（slotPShown=" + (slotPShown ? 1 : 0)
                        + " P路在表=" + (outputOk ? 1 : 0) + " overlayView=" + (overlayView != null ? 1 : 0)
                        + "）| " + describePProbe(now));
            }
        }
        // v1.67 beta4：换面 / 重建会话 / 相机未就绪不再让探针沉默——只累计持续时长，
        // 超过 10 秒仍卡在门控里就记一条（这本身就是"主窗不出帧"的一种成因）。
        final boolean gated = (cameraDevice == null) || cameraOpening || sessionCreating;
        if (gated) {
            if (pProbeGateSince == 0L) pProbeGateSince = now;
            final long held = now - pProbeGateSince;
            if (held >= 10000L && (pProbeGateLoggedAt == 0L || now - pProbeGateLoggedAt >= 30000L)) {
                pProbeGateLoggedAt = now;
                recordProbe("探针门控持续 " + (held / 1000L) + "s（相机=" + (cameraDevice != null ? "就绪" : "空")
                        + " 打开中=" + (cameraOpening ? 1 : 0) + " 会话重建中=" + (sessionCreating ? 1 : 0)
                        + "）| " + describePProbe(now));
            }
        } else if (pProbeGateSince != 0L) {
            final long held = now - pProbeGateSince;
            pProbeGateSince = 0L;
            pProbeGateLoggedAt = 0L;
            if (held >= 3000L) {
                recordProbe("探针门控解除（持续 " + (held / 1000L) + "s）| " + describePProbe(now));
            }
        }
        // v1.67 beta4：60 秒一条心跳——用来区分「探针没跑」与「探针在跑但主窗正常」。
        // 只在未处于冻结态时写，避免挤掉冻结记录。
        if (!pStallRecorded && (pProbeHeartbeatAt == 0L || now - pProbeHeartbeatAt >= 60000L)) {
            pProbeHeartbeatAt = now;
            final long hbGap = lastPUpdatedMs > 0L ? (now - lastPUpdatedMs) : -1L;
            recordProbe("心跳 主窗回调共" + pUpdatedTotal + "次 上次出新帧="
                    + (hbGap < 0 ? "本轮0次" : (hbGap / 1000L) + "s前")
                    + " 门控=" + (gated ? 1 : 0));
        }
        long last = lastPUpdatedMs;
        if (last <= 0L) {
            if (pProbeAnchorMs <= 0L) {
                pProbeAnchorMs = now;
                return;
            }
            last = pProbeAnchorMs;
        } else {
            pProbeAnchorMs = 0L;
        }
        final long gap = now - last;
        if (gap >= P_UPDATE_STALL_MS) {
            if (!pStallRecorded) {
                pStallRecorded = true;
                recordProbe("主窗 " + (gap / 1000L) + "s 未更新 | " + describePProbe(now));
            }
        } else if (pStallRecorded) {
            pStallRecorded = false;
            recordProbe("主窗画面恢复更新（冻结约 " + (gap / 1000L) + "s 后恢复）| " + describePProbe(now));
        }
    }

    /** 探针记录用的一行诊断快照。 */
    private String describePProbe(long now) {
        GlCameraRelay relay = glRelay;
        StringBuilder sb = new StringBuilder();
        sb.append("主窗回调共").append(pUpdatedTotal).append("次");
        sb.append(" 相机=").append(cameraDevice != null ? "就绪" : "空");
        sb.append(" 会话重建中=").append(sessionCreating ? 1 : 0);
        sb.append(" 中转=").append(glRelayMode ? (glRelayDisabled ? "已降级直连" : "在用") : "未启用");
        if (relay != null) {
            sb.append(" 输入帧=").append(relay.getFrameCount());
            sb.append(" 输出表=").append(relay.outputCount());
            sb.append(" | P[ ").append(relay.snapshotOutput("P", now)).append(" ]");
        } else {
            sb.append(" relay=空");
        }
        return sb.toString();
    }

    /** 写一条探针记录：系统日志 + 服务日志 + 主界面「主窗更新探针」面板。 */
    private static void recordProbe(String line) {
        String stamped = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date()) + " " + line;
        Log.w(TAG, "探针 " + line);
        serviceLog("探针 " + line);
        synchronized (probeRecords) {
            probeRecords.add(0, stamped);
            while (probeRecords.size() > PROBE_MAX) {
                probeRecords.remove(probeRecords.size() - 1);
            }
        }
    }

    /** 主界面取探针记录（最新在最前）。 */
    public static String getProbeText() {
        synchronized (probeRecords) {
            if (probeRecords.isEmpty()) {
                return "暂无记录（探针每 60 秒写一条心跳；主窗 5 秒不出新帧时立即写冻结记录）";
            }
            StringBuilder sb = new StringBuilder();
            for (String s : probeRecords) {
                sb.append(s).append('\n');
            }
            return sb.toString().trim();
        }
    }

    /** 清空探针记录（主界面「清空探针」用）。 */
    public static void clearProbeRecords() {
        synchronized (probeRecords) {
            probeRecords.clear();
        }
    }

    /** 启动主窗探针巡检（幂等；中转与直连两条路径都会调）。 */
    private void startPFrameProbe() {
        // v1.67 beta4：巡检从 bgHandler 迁到主线程 Handler。beta3 挂在 camera-bg 上，
        // 只要 bgThread 被 quitSafely（停服务/重建）或 postDelayed 返回 false，
        // pProbeRunning 就永久停在 true，探针彻底沉默又没有任何提示——
        // 这就是「主窗卡住但探针面板一条记录都没有」的直接成因。
        if (pProbeRunning) return;
        pProbeRunning = true;
        if (!pProbeHandler.postDelayed(pProbeWatchdog, P_PROBE_WATCH_MS)) {
            pProbeRunning = false;
            serviceLog("主窗探针启动失败（主线程 Handler 拒绝投递）");
            return;
        }
        serviceLog("主窗探针已启动（周期 " + (P_PROBE_WATCH_MS / 1000L) + "s）");
    }

    /**
     * 供主界面每秒调用的兜底：服务还活着但探针没在跑就重启它。
     * 主界面面板刷新是用户唯一能看见探针的窗口，这里必须保证探针一定在跑。
     */
    public static void ensureProbeRunning() {
        final CameraOverlayService svc = sProbeOwner;
        if (svc == null) return;
        if (svc.pProbeRunning) return;
        svc.startPFrameProbe();
    }

    /** 探针巡检（主线程 Handler 驱动）。 */
    private final Runnable pProbeWatchdog = new Runnable() {
        @Override
        public void run() {
            try {
                checkPFrameProbe();
            } catch (Throwable t) {
                Log.w(TAG, "主窗探针异常", t);
                // 探针自身异常也要留痕：由「记录」这条通道暴露，否则面板依旧空白
                try {
                    recordProbe("探针巡检异常：" + t.getClass().getSimpleName()
                            + (t.getMessage() != null ? (" " + t.getMessage()) : ""));
                } catch (Throwable ignored) {
                }
            } finally {
                if (!pProbeHandler.postDelayed(this, P_PROBE_WATCH_MS)) {
                    pProbeRunning = false;
                }
            }
        }
    };

    /** v1.63：中转停帧看门狗启动（重复调用幂等）。 */
    private void startRelayWatchdog() {
        if (bgHandler == null) return;
        bgHandler.removeCallbacks(relayStallWatchdog);
        bgHandler.postDelayed(relayStallWatchdog, 800L);
        // v1.67 beta3：主窗更新探针随中转一起起来（幂等）
        startPFrameProbe();
    }

    /**
     * v1.67：中转资源压力处理。
     *
     * <p>分级降级：单路卡死由 GlCameraRelay 内部的「release 承载面 + 僵尸收尸」无感解决，
     * 不惊动上层；只有收不了（线程确实不退、僵尸超限、或连续解套无效）才整实例重建。
     * 整实例重建 = 丢掉 relay 实例 + 重建相机会话，代价约 1 秒黑屏，自动恢复。
     * 60 秒内超过 2 次则降级直连单窗：少一路画面，但绝不再反复黑屏。</p>
     */
    private void handleRelayLeakPressure() {
        GlCameraRelay relay = glRelay;
        if (!glRelayMode || relay == null) return;
        boolean request = relay.consumeFullRebuildRequest();
        int leaked = relay.getLeakedOutputs();
        if (!request && leaked <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastRelayFullRebuildMs < 60000L) {
            relayFullRebuildCount++;
        } else {
            relayFullRebuildCount = 1;
        }
        lastRelayFullRebuildMs = now;
        serviceLog("中转资源压力：泄漏输出 " + leaked + " 路，整实例重建（60s 内第 "
                + relayFullRebuildCount + " 次，解套有效=" + relay.isSurfaceReleaseEffective() + "）");
        if (relayFullRebuildCount > 2) {
            glRelayDisabled = true;
            serviceLog("60s 内整实例重建超过 2 次，降级直连（双窗少一路，但不再黑屏）");
        }
        glRelay = null;
        try {
            relay.release();
        } catch (Throwable t) {
            serviceLog("释放旧中转失败：" + t);
        }
        relayWatchFrames = -1L;
        relayWatchChangeMs = now;
        sessionTargetSig = "";
        sessionReqSeq++;
        requestSessionRebuild(0L);
    }

    /**
     * v1.63：中转停帧看门狗。
     * 中转线程一旦阻塞在 eglSwapBuffers 上就永不返回，之后 setOutputs / detachOutput /
     * release 全部排队不执行，画面永久冻在最后一帧。这里以「帧计数是否还在增长」为判据：
     * 1.5s 不增长即判定中转已死，立刻放弃中转、回退直连并重建会话，先把画面救回来。
     */
    private final Runnable relayStallWatchdog = new Runnable() {
        @Override
        public void run() {
            if (bgHandler == null) return;
            try {
                checkRelayStall();
                handleRelayLeakPressure();
            } catch (Throwable t) {
                serviceLog("中转看门狗异常：" + t);
            }
            if (bgHandler != null && glRelayMode && !glRelayDisabled && glRelay != null) {
                bgHandler.postDelayed(this, 800L);
            }
        }
    };

    private void checkRelayStall() {
        GlCameraRelay relay = glRelay;
        if (!glRelayMode || relay == null) return;
        // v1.65：每个巡检周期对账一次输出表——窗口纹理换了、某路被摘掉、显隐与表不符，
        // 都在这里被纠正，绝不把「表里没有这一路」留到下一次触发。
        refreshRelayOutputs();
        // v1.64：没有任何输出（窗口全关 → 上层已释放相机）时不该判停帧
        if (relay.outputCount() <= 0) {
            relayWatchChangeMs = System.currentTimeMillis();
            return;
        }
        // v1.67：以下状态本来就该没有新帧，绝不能判「中转停帧」并 abandon。
        // 旧实现的每一次误判都要整实例重建，泄漏的 EGLSurface 与线程正是从这里累积起来的。
        if (cameraDevice == null || cameraOpening || sessionCreating) {
            relayWatchChangeMs = System.currentTimeMillis();
            return;
        }
        if (relay.outputCountActive() <= 0) {
            // 所有输出都处于可见性暂停（面板淡出/收起），不算停帧
            relayWatchChangeMs = System.currentTimeMillis();
            return;
        }
        long now = System.currentTimeMillis();
        long fc = relay.getFrameCount();
        if (fc != relayWatchFrames) {
            relayWatchFrames = fc;
            relayWatchChangeMs = now;
            return;
        }
        if (relayWatchChangeMs <= 0L) {
            relayWatchChangeMs = now;
            return;
        }
        long stalled = now - relayWatchChangeMs;
        if (stalled < 1200L) return;
        relayStallCount++;
        relayLastStallMs = now;
        serviceLog("GL 中转 " + stalled + "ms 无新帧，重建中转（第 "
                + relayStallCount + " 次）");
        // v1.64：每路输出已在独立线程里画，单路阻塞不会再拖死相机输入，
        // 因此整体停帧只可能是输入线程自身异常。这里不切直连、不收起次级窗
        // （那等于牺牲双路画面），只把中转整个重建一次，窗口与画面参数原样保留。
        if (relayStallCount >= 4) {
            glRelayDisabled = true;
            serviceLog("GL 中转连续 4 次停帧，回退直连（单窗可出画面）");
        }
        glRelay = null;
        relay.abandon();
        relayWatchFrames = -1L;
        relayWatchChangeMs = now;
        sessionTargetSig = "";
        sessionReqSeq++;
        requestSessionRebuild(0L);
    }

    /** v1.61 及以前的多 target 直连实现：仅作为 GL 中转不可用时的兜底。 */
    /** 收集当前所有预览目标（主窗 / BSD 右窗 / 编辑窗 / 仪表投影），并生成签名。 */
    private String collectTargetsDirect(java.util.ArrayList<Surface> targets) {
        // v1.67 beta3：直连兜底路径也要把主窗探针带起来
        startPFrameProbe();
        StringBuilder sig = new StringBuilder();
        // v1.60_beta4：只把「当前真正可见」的槽位加入输出目标。
        // 旧实现不看 slotPShown/slotSShown，隐藏槽位（alpha=0 + 移到 -30000）的 TextureView
        // 仍然 available，于是它一直占着一路编码输出面：编辑窗出现时目标变成 3 路，
        // 车机 HAL 协商不过来 → 编辑窗拿不到帧（空白框），而拖动/缩放（View 层）却正常。
        if (textureView != null && textureView.isAvailable()
                && textureView.getSurfaceTexture() != null) {
            SurfaceTexture st = textureView.getSurfaceTexture();
            st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
            bufferW = BUFFER_W;
            bufferH = BUFFER_H;
            if (previewSurface == null || previewSurfaceTex != st) {
                if (previewSurface != null) {
                    pendingSurfaceRelease.add(previewSurface);
                    previewSurface = null;
                }
                previewSurface = new Surface(st);
                previewSurfaceTex = st;
            }
            // v1.60_beta4：Surface 保活复用，但只有槽位可见时才作为输出目标。
            // 隐藏槽位（alpha=0 + 移屏）不再占一路编码输出面——这是编辑窗/设置页预览
            // 出空白框的直接原因（3 路目标超出车机 HAL 协商能力，新窗拿不到帧）。
            if (slotPShown) {
                targets.add(previewSurface);
            // v1.60_beta1：签名必须包含 Surface 实例身份。窗口重建后 TextureView 会给出
            // 全新 SurfaceTexture，但旧签名只记“有没有主窗”，与 sessionTargetSig 相同，
            // doRebuildSession 直接跳过 → 会话仍向已失效的旧 Surface 出帧，画面永久冻在
            // 最后一帧。加入 identityHashCode 后，实例一换签名必变，强制重建会话。
            sig.append('M').append(Integer.toHexString(System.identityHashCode(previewSurface)));
            }
        } else if (previewSurface != null) {
            // 主窗已不可用：把旧 Surface 从会话摘除并延后释放
            pendingSurfaceRelease.add(previewSurface);
            if (previewSurfaceTex != null) pendingTexRelease.add(previewSurfaceTex);
            previewSurface = null;
            previewSurfaceTex = null;
        }
        if (bsdRightTextureView != null && bsdRightTextureView.isAvailable()
                && bsdRightTextureView.getSurfaceTexture() != null) {
            SurfaceTexture rst = bsdRightTextureView.getSurfaceTexture();
            if (bsdRightSurface == null || bsdRightSurfaceTex != rst) {
                if (bsdRightSurface != null) {
                    pendingSurfaceRelease.add(bsdRightSurface);
                    bsdRightSurface = null;
                }
                rst.setDefaultBufferSize(BUFFER_W, BUFFER_H);
                bsdRightSurface = new Surface(rst);
                bsdRightSurfaceTex = rst;
            }
            // v1.60_beta4：同主窗——隐藏次级窗不占输出面，仅保活 Surface
            if (slotSShown) {
                targets.add(bsdRightSurface);
                sig.append('R').append(Integer.toHexString(System.identityHashCode(bsdRightSurface)));
            }
        } else if (bsdRightSurface != null) {
            // 右窗已关闭：摘除并延后释放
            pendingSurfaceRelease.add(bsdRightSurface);
            if (bsdRightSurfaceTex != null) pendingTexRelease.add(bsdRightSurfaceTex);
            bsdRightSurface = null;
            bsdRightSurfaceTex = null;
        }
        if (slotEditP != null) {
            int before = targets.size();
            slotEditP.addSurfaceTarget(targets);
            if (targets.size() > before) {
                sig.append('L').append(Integer.toHexString(
                        System.identityHashCode(targets.get(targets.size() - 1))));
            }
        }
        if (slotEditS != null) {
            int before = targets.size();
            slotEditS.addSurfaceTarget(targets);
            if (targets.size() > before) {
                sig.append('E').append(Integer.toHexString(
                        System.identityHashCode(targets.get(targets.size() - 1))));
            }
        }
        if (iclProjectionEnabled) {
            Surface iclSurface = icProjector.getInputSurface();
            if (iclSurface != null && iclSurface.isValid()) {
                targets.add(iclSurface);
                sig.append('I').append(Integer.toHexString(System.identityHashCode(iclSurface)));
            }
        }
        return sig.toString();
    }

    /** 用给定目标创建 CaptureSession（bgHandler 线程）。 */
    private void startSession(final java.util.ArrayList<Surface> targets,
                              final String newSig, final int seq) {
        try {
            final CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            for (Surface target : targets) {
                builder.addTarget(target);
            }
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            cameraDevice.createCaptureSession(targets,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            sessionCreatingSinceMs = 0L;
                            sessionFailStreak = 0;
                            if (seq != sessionReqSeq) {
                                // 过期会话（期间又发起了新的重建）：直接关闭，不覆盖新状态
                                try {
                                    session.close();
                                } catch (Exception ignored) {
                                }
                                return;
                            }
                            sessionCreating = false;
                            if (cameraDevice == null) {
                                try {
                                    session.close();
                                } catch (Exception ignored) {
                                }
                                return;
                            }
                            captureSession = session;
                            sessionTargetSig = newSig;
                            try {
                                session.setRepeatingRequest(builder.build(), null, bgHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "setRepeatingRequest failed", e);
                            }
                            sessionRetryCount = 0;
                            // 新会话已生效，此时才可安全释放旧 Surface / SurfaceTexture
                            flushPendingSurfaceRelease();
                            runPendingSessionRebuild();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            sessionCreating = false;
                            sessionCreatingSinceMs = 0L;
                            if (seq != sessionReqSeq) return;
                            Log.e(TAG, "camera configure failed, targets=" + newSig);
                            sessionTargetSig = "";
                            if (sessionRetryCount < SESSION_RETRY_MAX) {
                                sessionRetryCount++;
                                Log.w(TAG, "会话 configure 失败，300ms 后第 "
                                        + sessionRetryCount + " 次重建");
                                bgHandler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (cameraDevice != null) {
                                            doRebuildSession();
                                        }
                                    }
                                }, 300L);
                            } else {
                                // v1.57 beta5：重试用尽不再静默留空框，整机重启相机自愈
                                Log.e(TAG, "会话 configure 重试用尽，重启相机自愈");
                                sessionRetryCount = 0;
                                releaseCamera();
                                bgHandler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (cameraDevice == null && hasAnyPreviewTarget()) {
                                            openCamera();
                                        }
                                    }
                                }, 350L);
                            }
                        }

                        @Override
                        public void onClosed(CameraCaptureSession session) {
                            // 旧会话关闭完成（API 29+ 回调），推进排队中的重建
                            runPendingSessionRebuild();
                        }
                    }, bgHandler);
        } catch (Exception e) {
            // v1.60_beta2：这里原来只捕获 CameraAccessException。一旦 Surface 已失效/重复，
            // createCaptureRequest / createCaptureSession 抛的是 IllegalArgumentException 或
            // IllegalStateException，漏出去后 sessionCreating 永久为 true，
            // sessionRebuildRunnable 每 60ms 只做 postDelayed 无限等待，会话再也不重建 → 永久卡死。
            sessionCreating = false;
            sessionCreatingSinceMs = 0L;
            sessionTargetSig = "";
            Log.e(TAG, "createCaptureSession failed", e);
            serviceLog("会话创建异常，已复位重建标志：" + e.getClass().getSimpleName());
            recoverOverlayTexture("createCaptureSession " + e.getClass().getSimpleName());
        }
    }

    /** 新会话生效后释放旧 Surface / SurfaceTexture。 */
    private void flushPendingSurfaceRelease() {
        for (Surface s : pendingSurfaceRelease) {
            try {
                s.release();
            } catch (Exception ignored) {
            }
        }
        pendingSurfaceRelease.clear();
        for (SurfaceTexture st : pendingTexRelease) {
            try {
                st.release();
            } catch (Exception ignored) {
            }
        }
        pendingTexRelease.clear();
    }

    /** v1.57 beta4：重建请求在飞行期间被吞时，配置完成后串行补建一次。 */
    private void runPendingSessionRebuild() {
        if (!sessionRebuildPending) return;
        sessionRebuildPending = false;
        if (cameraDevice == null) return;
        bgHandler.post(new Runnable() {
            @Override
            public void run() {
                createPreviewSession();
            }
        });
    }

    /**
     * 完整释放摄像头通道：关闭 CaptureSession / CameraDevice，
     * 释放预览 Surface 与 TextureView 的 SurfaceTexture，断开监听。
     * 幂等，可被 onDestroy 与 onSurfaceTextureDestroyed 重复调用。
     */
    private void releaseCamera() {
        // v1.67 beta8：记录释放时刻，供 openCamera 冷却判定
        lastCameraClosedMs = System.currentTimeMillis();
        cameraReopenPending = false;
        // v1.67 beta6：完整释放即作废任何在途的延迟释放计时（幂等）
        cancelCameraLinger();
        // v1.57 beta4：完整释放时同步复位会话重建状态
        sessionCreating = false;
        sessionCreatingSinceMs = 0L;
        sessionRebuildPending = false;
        // v1.57 beta5：作废在途会话回调、撤销排队重建、清空签名与待释放队列
        sessionReqSeq++;
        sessionTargetSig = "";
        cameraOpening = false;
        if (bgHandler != null) {
            bgHandler.removeCallbacks(sessionRebuildRunnable);
        }
        flushPendingSurfaceRelease();
        // v1.52：Camera1 回退实例一并释放（幂等）
        if (camera1Preview != null) {
            camera1Preview.close();
            camera1Preview = null;
        }
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        previewSurfaceTex = null;
        // v1.54：BSD 右浮窗 surface 一并释放
        if (bsdRightSurface != null) {
            bsdRightSurface.release();
            bsdRightSurface = null;
        }
        bsdRightSurfaceTex = null;
        // v1.54 beta4：BSD 编辑窗 surface 一并释放
        if (bsdEditLeft != null) {
            bsdEditLeft.releaseSurface();
        }
        if (bsdEditRight != null) {
            bsdEditRight.releaseSurface();
        }
        // v1.60_beta2：窗口仍然存在时绝不销毁主窗 TextureView / 纹理。
        // 旧实现无条件 setSurfaceTextureListener(null) + st.release() + textureView=null，
        // 而 overlayView 还在窗口上（ensureOverlay 见 overlayView != null 直接 return），
        // 结果主窗永久没有可用纹理：collectTargets 拿不到主窗目标，会话要么空、要么只剩
        // 已释放的死 Surface，画面永久冻结在最后一帧——这就是“画面还是会卡死”的主因。
        // 纹理只由 removeOverlay()（窗口真的移除）与 onDestroy() 负责销毁。
        if (overlayView == null && textureView != null) {
            textureView.setSurfaceTextureListener(null);
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st != null) {
                try {
                    st.release();
                } catch (Exception ignored) {
                }
            }
            textureView = null;
        }
        // v1.60_beta2：主窗仍然存在时，释放后必须立刻把纹理重新挂成会话目标并请求重连。
        // 否则 releaseCamera 之后 collectTargets 没有任何主窗目标（previewSurface=null，
        // 而 TextureView 非重建不会再次回调 onSurfaceTextureAvailable），
        // 画面就永久黑屏/冻结在最后一帧——这是“画面还是会卡死”的另一条主因。
        if (overlayView != null && textureView != null && textureView.isAvailable()) {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st != null) {
                try {
                    st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
                    previewSurfaceTex = st;
                    previewSurface = new Surface(st);
                    requestSessionRebuild(80L);
                    serviceLog("releaseCamera：主窗仍在，已重建纹理目标并请求重连会话");
                } catch (Exception e) {
                    Log.e(TAG, "releaseCamera 重建主窗 Surface 失败", e);
                }
            }
        }
    }

    /** v1.60_beta2：会话连续异常/纹理失效时的最后一层自愈——彻底重建主窗窗口与纹理。 */
    private void recoverOverlayTexture(String reason) {
        sessionFailStreak++;
        serviceLog("会话异常累计=" + sessionFailStreak + "（" + reason + "）");
        if (sessionFailStreak < 3) {
            return;
        }
        sessionFailStreak = 0;
        serviceLog("连续 3 次会话异常，强制重建主窗窗口与纹理");
        if (mainHandler == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                boolean hadOverlay = overlayView != null;
                removeOverlay();
                if (hadOverlay || direction != null) {
                    ensureOverlay();
                }
                openCamera();
            }
        });
    }

    @Override
    public void onDestroy() {
        if (monitor != null) {
            monitor.stop();
            monitor = null;
        }
        // v1.52：销毁兜底停止 SignalGate 慢变量轮询并摘除 BSD 窄条
        if (signalGate != null) {
            signalGate.stop();
        }
        if (sideWarning != null) {
            sideWarning.detach();
        }
        linkActive.set(false);
        manualVisible.set(false);
        // v1.54 beta4：服务销毁时隐藏 BSD 编辑窗（不保存，与主窗一并移除）
        if (bsdEditLeft != null) {
            bsdEditLeft.removeFromWindow();
            bsdEditLeft = null;
        }
        if (bsdEditRight != null) {
            bsdEditRight.removeFromWindow();
            bsdEditRight = null;
        }
        releaseCamera();
        if (bgHandler != null) {
            bgHandler.removeCallbacks(relayStallWatchdog);
        }
        // v1.62：释放 GL 中转（EGL 上下文 + 全部输出面）
        if (glRelay != null) {
            glRelay.release();
            glRelay = null;
        }
        removeOverlay();
        // v1.67 beta4：服务销毁必须把次级窗一并 removeView。否则旧 S 窗无引用留存，
        // 重开总开关后新旧两个 S 窗并存，旧的永远关不掉（关总开关后次级窗不消失的根因）。
        destroySecondaryWindow();
        resetSlotStateOnStop();
        // v1.67 beta4：先摘掉探针巡检，避免服务销毁后主线程 Handler 仍持有已死的服务引用
        pProbeHandler.removeCallbacksAndMessages(null);
        pProbeRunning = false;
        if (sProbeOwner == this) sProbeOwner = null;
        if (bgThread != null) bgThread.quitSafely();
        serviceLog("前台服务已停止");
        super.onDestroy();
    }

    // ---- v1.54 beta4：BSD 预警浮窗自定义编辑窗 ----

    /**
     * v1.54 beta4：BSD 预警浮窗独立编辑窗（左右各一）。
     * 复用 overlay_window 布局：单指拖动位置（松手保存 bsd_left/right_ 键）、
     * 双击进入编辑（单指平移画面 / 双指缩放 / 拖边改形状，保存 bsd_ 前缀独立键）、
     * 右上角关闭按钮单独关闭该侧并保存对应配置。
     * 画面渲染与位置解耦：默认未编辑时 fitCenter 完整显示，编辑后按 bsd_ 键自由渲染。
     */
    private class BsdEditWindow {
        final String side;          // "LEFT" / "RIGHT"
        final String bsdPrefix;     // "bsd_left_" / "bsd_right_"
        final String turnPrefix;    // v1.58：几何前缀（pa_ / pb_），左右状态共用
        final String geoPrefix;     // v1.58：窗口几何持久化前缀
        final String titleText;     // v1.58：窗内文案
        final View rootView;
        final TextureView textureView;
        final View closeView;
        final View borderView;
        final android.widget.TextView editHintView;
        final View cropHandleL, cropHandleR, cropHandleT, cropHandleB;
        final View cropHandleTL, cropHandleTR, cropHandleBL, cropHandleBR;
        boolean editMode = false;
        float camScale = CAM_SCALE_DEFAULT;
        float panX = 0f;
        float panY = 0f;
        Surface editSurface = null;
        // v1.60_beta3：记录 editSurface 对应的 SurfaceTexture，用于判断是否需要重建 Surface。
        SurfaceTexture editSurfaceTex = null;
        final WindowManager.LayoutParams lp;

        BsdEditWindow(String side) {
            this(side, "LEFT".equals(side) ? "bsd_left_" : "bsd_right_",
                    "LEFT".equals(side) ? "left_" : "right_",
                    "LEFT".equals(side) ? "BSD 左预警编辑" : "BSD 右预警编辑");
        }

        /**
         * v1.58：参数化编辑窗——同一套拖动/双击/缩放/拖边逻辑被复用到「优先窗 P / 次级窗 S × 左状态/右状态」。
         * @param side        语义侧（LEFT/RIGHT），影响默认 1/4 裁切初始值
         * @param framePrefix 画面配置前缀（pa_l_ / pa_r_ / pb_l_ / pb_r_）
         * @param geoPrefix   窗口几何前缀（pa_ / pb_），左右状态共用同一套几何
         * @param titleText   窗内标签/提示文案
         */
        BsdEditWindow(String side, String framePrefix, String geoPrefix, String titleText) {
            this.side = side;
            this.bsdPrefix = framePrefix;
            this.turnPrefix = geoPrefix;
            this.geoPrefix = geoPrefix;
            this.titleText = titleText;
            LayoutInflater li = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            rootView = li.inflate(R.layout.overlay_window, null);
            textureView = rootView.findViewById(R.id.overlay_texture);
            if (textureView != null) {
                textureView.setClipToOutline(true);
                textureView.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(24));
                    }
                });
                textureView.setSurfaceTextureListener(bsdEditSurfaceListener);
            }
            TextView label = rootView.findViewById(R.id.overlay_label);
            if (label != null) {
                label.setText(titleText);
            }
            closeView = rootView.findViewById(R.id.overlay_close);
            if (closeView != null) {
                closeView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        closeBsdWindow(CameraOverlayService.this, BsdEditWindow.this.side);
                    }
                });
            }
            borderView = rootView.findViewById(R.id.overlay_border);
            cropHandleL = rootView.findViewById(R.id.handle_left);
            cropHandleR = rootView.findViewById(R.id.handle_right);
            cropHandleT = rootView.findViewById(R.id.handle_top);
            cropHandleB = rootView.findViewById(R.id.handle_bottom);
            cropHandleTL = rootView.findViewById(R.id.handle_tl);
            cropHandleTR = rootView.findViewById(R.id.handle_tr);
            cropHandleBL = rootView.findViewById(R.id.handle_bl);
            cropHandleBR = rootView.findViewById(R.id.handle_br);
            editHintView = rootView.findViewById(R.id.overlay_edit_hint);
            android.graphics.drawable.GradientDrawable borderBg = new android.graphics.drawable.GradientDrawable();
            borderBg.setStroke(dp(2), 0xFFFFD98A);
            borderBg.setColor(0x00000000);
            if (borderView != null) borderView.setBackground(borderBg);
            if (editHintView != null) editHintView.setText(titleText);
            setupWindowEdgeHandle2(cropHandleL, "left");
            setupWindowEdgeHandle2(cropHandleR, "right");
            setupWindowEdgeHandle2(cropHandleT, "top");
            setupWindowEdgeHandle2(cropHandleB, "bottom");
            setupWindowCornerHandle2(cropHandleTL, "tl");
            setupWindowCornerHandle2(cropHandleTR, "tr");
            setupWindowCornerHandle2(cropHandleBL, "bl");
            setupWindowCornerHandle2(cropHandleBR, "br");

            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            // 窗口宽高：bsd_ 键优先，其次打灯对应键，最后默认
            int defW = (int) (dm.widthPixels * OVERLAY_DEF_W_RATIO);
            int defH = (int) (defW * 9f / 16f);
            int w = prefs.getInt(bsdPrefix + "overlay_w",
                    prefs.getInt(turnPrefix + "overlay_w", defW));
            int h = prefs.getInt(bsdPrefix + "overlay_h",
                    prefs.getInt(turnPrefix + "overlay_h", defH));
            int minW = (int) (dm.widthPixels * MIN_SIZE_RATIO);
            int minH = (int) (dm.heightPixels * MIN_SIZE_RATIO);
            if (w < minW) w = minW;
            if (w > dm.widthPixels) w = dm.widthPixels;
            if (h < minH) h = minH;
            if (h > dm.heightPixels) h = dm.heightPixels;
            lp = new WindowManager.LayoutParams(
                    w, h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            // v1.60：位置统一走 readSlotGeo（几何键 + recorded 校验 + 越界兜底），
            // 不再按 bsdPrefix/turnPrefix 分别读 recorded——saveConfigInternal 写的是
            // geoPrefix+"overlay_pos_recorded"，「窗×侧」前缀下该类键并不存在，导致编辑窗
            // 每次打开都落到硬编码贴边位置（左状态贴屏幕最左，右状态把共用几何搬到右侧）。
            int[] geoInit = readSlotGeo("pa_".equals(geoPrefix) ? "P" : "S", dm);
            lp.x = geoInit[2];
            lp.y = geoInit[3];
            // 画面配置：bsd_ 键优先，其次打灯对应键
            camScale = prefs.getFloat(bsdPrefix + "cam_scale",
                    prefs.getFloat(turnPrefix + "cam_scale", CAM_SCALE_DEFAULT));
            panX = prefs.getFloat(bsdPrefix + "pan_x",
                    prefs.getFloat(turnPrefix + "pan_x", 0f));
            panY = prefs.getFloat(bsdPrefix + "pan_y",
                    prefs.getFloat(turnPrefix + "pan_y", 0f));
            clampCamScale();
        }

        void clampCamScale() {
            if (camScale < CAM_SCALE_MIN) camScale = CAM_SCALE_MIN;
            if (camScale > CAM_SCALE_MAX) camScale = CAM_SCALE_MAX;
        }

        void addToWindow() {
            try {
                wm.addView(rootView, lp);
                makeDraggable2(rootView);
                applyRenderTransform();
            } catch (Exception e) {
                Log.e(TAG, "BSD 编辑窗 addView failed side=" + side, e);
            }
        }

        void removeFromWindow() {
            // v1.60：顺序修正——先把本窗 Surface 从预览会话摘除并作废会话签名，再移窗。
            // 旧顺序先 removeView 后 releaseSurface：removeView 触发的
            // onSurfaceTextureDestroyed 直接 return true 让系统释放纹理，而会话 output
            // 目标列表里仍挂着这个已失效 Surface，HAL 向死输出面提交请求后会静默停帧，
            // 表现为退出编辑后主窗永久冻结在"编辑时画面"。
            releaseSurface();
            sessionTargetSig = "";
            sessionRebuildPending = false;
            try {
                wm.removeView(rootView);
            } catch (Exception ignored) {
            }
            if (cameraDevice != null) {
                requestSessionRebuild(80L);
            }
        }

        boolean isTargetReady() {
            return textureView != null && textureView.isAvailable();
        }

        /** v1.62：本编辑窗作为 GL 中转的一个输出端（null 表示纹理未就绪）。 */
        GlCameraRelay.Target outputTarget(String key) {
            SurfaceTexture st = currentTexture();
            return st == null ? null : GlCameraRelay.Target.ofTexture(key, st);
        }

        /** v1.62：当前 TextureView 持有的 SurfaceTexture。 */
        SurfaceTexture currentTexture() {
            if (textureView == null || !textureView.isAvailable()) return null;
            return textureView.getSurfaceTexture();
        }

        /** 将本窗 TextureView 的 Surface 加入预览输出目标列表（配合 createPreviewSession）。 */
        void addSurfaceTarget(java.util.ArrayList<Surface> targets) {
            if (textureView == null || !textureView.isAvailable()
                    || textureView.getSurfaceTexture() == null) {
                return;
            }
            SurfaceTexture st = textureView.getSurfaceTexture();
            st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
            // v1.60_beta3：旧实现每次 collectTargets 都 release 旧 Surface 再 new 一个新的，
            // 后果有两个且都很致命：
            //  ① 会话签名里记的是 Surface 实例身份，每次都是新实例 → 签名永远不相等
            //    → doRebuildSession 认为"目标变了"，编辑态下无限重建会话；
            //  ② 被 release 的恰恰是上一条会话正在出帧的输出面 → HAL 提交失败 → 编辑窗
            //    画面全空（用户看到的"双击编辑后浮窗里没有影像"），还会连带主窗停帧。
            // 现在：SurfaceTexture 不变就复用同一个 Surface；换了纹理才换 Surface，且旧
            // Surface 只进延迟释放队列，等新会话 onConfigured 成功后再释放。
            if (editSurface == null || editSurfaceTex != st || !editSurface.isValid()) {
                if (editSurface != null) {
                    pendingSurfaceRelease.add(editSurface);
                    editSurface = null;
                }
                editSurface = new Surface(st);
                editSurfaceTex = st;
            }
            targets.add(editSurface);
        }

        void releaseSurface() {
            if (editSurface != null) {
                // v1.60_beta3：不能立即 release——当前会话可能仍在向它出帧，
                // 立刻释放会让 HAL 停帧。统一走延迟释放队列。
                pendingSurfaceRelease.add(editSurface);
                editSurface = null;
                editSurfaceTex = null;
            }
        }

        private final TextureView.SurfaceTextureListener bsdEditSurfaceListener =
                new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                        st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
                        applyRenderTransform();
                        // v1.54 beta6：条件放宽为 cameraDevice 就绪即重建会话——createPreviewSession
                        // 内部会聚合本编辑窗 surface 并在无目标时自动释放，避免编辑态独立预览时
                        // 主窗纹理未就绪导致黑屏（surface 初始化时序修复）
                        if (cameraDevice != null) {
                            // v1.60_beta4：作废签名强制真重建。旧实现在"隐藏主窗仍占目标"时
                            // 签名与编辑前的会话完全一致，doRebuildSession 直接 return，
                            // 编辑窗的 Surface 永远进不了会话 → 编辑窗永久空白。
                            sessionTargetSig = "";
                            createPreviewSession();
                        }
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                        st.setDefaultBufferSize(BUFFER_W, BUFFER_H);
                        applyRenderTransform();
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                                                // v1.62：GL 中转模式下该窗输出端由 relay 持有，先摘 EGLSurface 再释放纹理
                        detachRelayOutput("EDIT_P");
                        detachRelayOutput("EDIT_S");
// v1.60：与主窗/右窗一致——先摘出会话目标并作废签名，再放行系统释放，
                        // 避免会话继续向已销毁的编辑窗纹理出帧（会让整条会话停帧、主窗冻结）。
                        releaseSurface();
                        sessionTargetSig = "";
                        if (cameraDevice != null && hasAnyPreviewTarget()) {
                            requestSessionRebuild(80L);
                        }
                        return true;
                    }

                    @Override
                    public void onSurfaceTextureUpdated(SurfaceTexture st) {
                    }
                };

        /** v1.67_beta7：把编辑窗坐标钳制到屏幕内（0..屏宽-窗宽 / 0..屏高-窗高）。
         * 拖到边界即精确 0，这正是“最左上角”的合法坐标；隐藏坐标(-30000)不在此处理。 */
        void clampLpToScreen() {
            if (wm == null) return;
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            int maxX = Math.max(0, dm.widthPixels - lp.width);
            int maxY = Math.max(0, dm.heightPixels - lp.height);
            if (lp.x < 0) lp.x = 0;
            if (lp.y < 0) lp.y = 0;
            if (lp.x > maxX) lp.x = maxX;
            if (lp.y > maxY) lp.y = maxY;
        }

        /** 保存本窗编辑配置到 bsd_ 前缀独立键（位置/大小/画面缩放/平移）。 */
        void saveConfigInternal() {
            // v1.60_beta2：隐藏/非法坐标一律不落盘（与主窗 saveEditConfigInternal 同规则），
            // 否则 -30000 之类的隐藏坐标会被当成用户位置固化，主窗读回时又回退默认。
            // v1.60_beta3：贴边坐标 x=0 / y=0 是合法位置（上吸附线就是 y=0）。旧判断用
            // `<= 0` 会把"拖到左上角"当成非法直接放弃保存——用户关掉编辑窗后位置就丢了，
            // 主窗读回默认值落回左下角。现在只有隐藏坐标与负数才拒绝。
            // v1.67_beta7：只有真正的隐藏坐标(-30000)才不落位置；其余先钳制到屏内再落盘。
            // 旧实现把任何 x/y < -dp(4) 当作非法整包 return——用户把窗拖到左上角时窗口左上角
            // 必然越过屏幕缘（负几十像素），于是位置/尺寸/画面全部丢失，重新打开编辑就回退默认屏底。
            boolean hidden = lp.x <= SLOT_HIDDEN_X || lp.y <= SLOT_HIDDEN_Y;
            if (hidden) {
                serviceLog("编辑窗隐藏坐标，跳过位置落盘 pos=(" + lp.x + "," + lp.y + ")");
            } else {
                clampLpToScreen();
            }
            SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            // v1.58：几何写共用前缀（pa_/pb_，左右状态共用），画面写「窗×侧」前缀（pa_l_/pa_r_ ...）
            ed.putInt(geoPrefix + "overlay_w", lp.width);
            ed.putInt(geoPrefix + "overlay_h", lp.height);
            if (!hidden) {
                ed.putInt(geoPrefix + "overlay_pos_x", lp.x);
                ed.putInt(geoPrefix + "overlay_pos_y", lp.y);
                ed.putBoolean(geoPrefix + "overlay_pos_recorded", true);
                ed.putInt(geoPrefix + "last_pos_x", lp.x);
                ed.putInt(geoPrefix + "last_pos_y", lp.y);
            }
            ed.putFloat(bsdPrefix + "cam_scale", camScale);
            ed.putFloat(bsdPrefix + "pan_x", panX);
            ed.putFloat(bsdPrefix + "pan_y", panY);
            ed.apply();
            serviceLog("浮窗编辑已保存[" + titleText + "] w=" + lp.width + " h=" + lp.height
                    + " camScale=" + camScale + " pan=(" + (int) panX + "," + (int) panY
                    + ") pos=(" + lp.x + "," + lp.y + ") geo=" + geoPrefix + " frame=" + bsdPrefix);
        }

        /** 从 prefs 重新加载配置（同步转向灯后立即刷新用）。 */
        void reloadFromPrefs() {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            int defW = (int) (dm.widthPixels * OVERLAY_DEF_W_RATIO);
            int defH = (int) (defW * 9f / 16f);
            int w = prefs.getInt(bsdPrefix + "overlay_w",
                    prefs.getInt(turnPrefix + "overlay_w", defW));
            int h = prefs.getInt(bsdPrefix + "overlay_h",
                    prefs.getInt(turnPrefix + "overlay_h", defH));
            int minW = (int) (dm.widthPixels * MIN_SIZE_RATIO);
            int minH = (int) (dm.heightPixels * MIN_SIZE_RATIO);
            if (w < minW) w = minW;
            if (w > dm.widthPixels) w = dm.widthPixels;
            if (h < minH) h = minH;
            if (h > dm.heightPixels) h = dm.heightPixels;
            lp.width = w;
            lp.height = h;
            // v1.60：位置统一走 readSlotGeo，与构造器/主窗同一套取值规则
            int[] geoReload = readSlotGeo("pa_".equals(geoPrefix) ? "P" : "S", dm);
            lp.x = geoReload[2];
            lp.y = geoReload[3];
            camScale = prefs.getFloat(bsdPrefix + "cam_scale",
                    prefs.getFloat(turnPrefix + "cam_scale", CAM_SCALE_DEFAULT));
            panX = prefs.getFloat(bsdPrefix + "pan_x",
                    prefs.getFloat(turnPrefix + "pan_x", 0f));
            panY = prefs.getFloat(bsdPrefix + "pan_y",
                    prefs.getFloat(turnPrefix + "pan_y", 0f));
            clampCamScale();
            try {
                wm.updateViewLayout(rootView, lp);
            } catch (Exception ignored) {
            }
            setEditMode2(editMode);
        }

        /** 编辑窗渲染：与主窗同构但使用 bsd_ 前缀独立键（未编辑 fitCenter 完整显示，编辑后自由平移）。 */
        void applyRenderTransform() {
            if (textureView == null) return;
            int vw = lp.width;
            int vh = lp.height;
            if (vw <= 0) vw = 1;
            if (vh <= 0) vh = 1;
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            float defW = dm.widthPixels * OVERLAY_DEF_W_RATIO;
            float defH = defW * 9f / 16f;
            float cs = camScale;
            clampCamScale();
            cs = camScale;
            float Wv = defW * cs;
            float Hv = defH * cs;
            float scale = Wv / BUFFER_W;
            float dx;
            float dy;
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            boolean defaultCrop = !prefs.contains(bsdPrefix + "cam_scale") && !editMode;
            if (defaultCrop) {
                float fit = Math.min(vw / Wv, vh / Hv);
                scale = (Wv / BUFFER_W) * fit;
                dx = (vw - Wv * fit) / 2f;
                dy = (vh - Hv * fit) / 2f;
            } else {
                dx = (vw - Wv) / 2f + panX;
                dy = (vh - Hv) / 2f + panY;
            }
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.reset();
            m.postScale(scale, scale);
            m.postTranslate(dx, dy);
            textureView.setTransform(m);
            serviceLog("BSD编辑窗RT side=" + side + " vw=" + vw + " vh=" + vh
                    + " Wv=" + (int) Wv + " Hv=" + (int) Hv + " pan=(" + (int) panX + "," + (int) panY
                    + ") scale=" + String.format("%.4f", scale));
        }

        void setEditMode2(boolean on) {
            editMode = on;
            if (borderView != null) borderView.setVisibility(on ? View.VISIBLE : View.GONE);
            if (cropHandleL != null) cropHandleL.setVisibility(on ? View.VISIBLE : View.GONE);
            if (cropHandleR != null) cropHandleR.setVisibility(on ? View.VISIBLE : View.GONE);
            if (cropHandleT != null) cropHandleT.setVisibility(on ? View.VISIBLE : View.GONE);
            if (cropHandleB != null) cropHandleB.setVisibility(on ? View.VISIBLE : View.GONE);
            if (cropHandleTL != null) cropHandleTL.setVisibility(on ? View.VISIBLE : View.GONE);
            if (cropHandleTR != null) cropHandleTR.setVisibility(on ? View.VISIBLE : View.GONE);
            if (cropHandleBL != null) cropHandleBL.setVisibility(on ? View.VISIBLE : View.GONE);
            if (cropHandleBR != null) cropHandleBR.setVisibility(on ? View.VISIBLE : View.GONE);
            if (editHintView != null) {
                editHintView.setText(on ? "编辑中 · 单指平移画面，双指缩放，拖边改形状" : titleText);
            }
            if (on) applyRenderTransform();
        }

        /** 双击切换编辑模式：进入加载 bsd_ 键画面配置（从未编辑时以默认 1/4 裁切为基准），退出保存。 */
        void toggleEditMode2() {
            if (editMode) {
                saveConfigInternal();
                setEditMode2(false);
                serviceLog("BSD 预警编辑模式退出，配置已保存 side=" + side);
            } else {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                camScale = prefs.getFloat(bsdPrefix + "cam_scale",
                        prefs.getFloat(turnPrefix + "cam_scale", CAM_SCALE_DEFAULT));
                panX = prefs.getFloat(bsdPrefix + "pan_x",
                        prefs.getFloat(turnPrefix + "pan_x", 0f));
                panY = prefs.getFloat(bsdPrefix + "pan_y",
                        prefs.getFloat(turnPrefix + "pan_y", 0f));
                // 从未编辑过（无 bsd_cam_scale 键）时，以默认左下/右下 1/4 裁切区域作为编辑初始值
                if (!prefs.contains(bsdPrefix + "cam_scale")
                        && !prefs.contains(turnPrefix + "cam_scale")) {
                    DisplayMetrics dm = new DisplayMetrics();
                    wm.getDefaultDisplay().getRealMetrics(dm);
                    float defW = dm.widthPixels * OVERLAY_DEF_W_RATIO;
                    float defH = defW * 9f / 16f;
                    camScale = 2f;
                    panX = "LEFT".equals(side) ? defW / 2f : -defW / 2f;
                    panY = -defH / 2f;
                }
                clampCamScale();
                setEditMode2(true);
                serviceLog("BSD 预警编辑模式进入（" + side + "），双击保存退出");
            }
        }

        /** BSD 编辑窗触摸：非编辑单指拖动位置（松手保存 bsd_ 键），编辑模式平移画面/双指缩放，双击切换。 */
        void makeDraggable2(final View view) {
            if (view == null) return;
            view.setOnTouchListener(new View.OnTouchListener() {
                private int downX;
                private int downY;
                private int initX;
                private int initY;
                private float initPanX;
                private float initPanY;
                private boolean dragging = false;
                private long lastTapTime = 0L;
                private boolean doubleTapConsumed = false;
                private float startSpan = 0f;
                private float startCamScale = 0f;
                private boolean pinchActive = false;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (rootView == null) return false;
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = (int) event.getRawX();
                            downY = (int) event.getRawY();
                            initX = lp.x;
                            initY = lp.y;
                            initPanX = panX;
                            initPanY = panY;
                            dragging = false;
                            pinchActive = false;
                            long now = System.currentTimeMillis();
                            if (now - lastTapTime < 400L) {
                                lastTapTime = 0L;
                                doubleTapConsumed = true;
                                toggleEditMode2();
                            } else {
                                lastTapTime = 0L;
                                doubleTapConsumed = false;
                            }
                            return true;
                        case MotionEvent.ACTION_POINTER_DOWN:
                            if (editMode && event.getPointerCount() >= 2) {
                                pinchActive = true;
                                dragging = false;
                                startSpan = computeSpan(event);
                                startCamScale = camScale;
                            }
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (pinchActive && event.getPointerCount() >= 2) {
                                float span = computeSpan(event);
                                if (startSpan > 0f && span > 0f) {
                                    float newScale = startCamScale * span / startSpan;
                                    camScale = newScale;
                                    clampCamScale();
                                    applyRenderTransform();
                                }
                                return true;
                            }
                            if (editMode) {
                                int dx = (int) event.getRawX() - downX;
                                int dy = (int) event.getRawY() - downY;
                                if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
                                    dragging = true;
                                }
                                if (dragging) {
                                    panX = initPanX + dx;
                                    panY = initPanY + dy;
                                    applyRenderTransform();
                                }
                                return true;
                            }
                            {
                                int dx = (int) event.getRawX() - downX;
                                int dy = (int) event.getRawY() - downY;
                                if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
                                    dragging = true;
                                }
                                if (dragging) {
                                    // v1.67_beta7：拖动必须钳制在屏幕内。窗口带 FLAG_LAYOUT_NO_LIMITS，
                                    // 手按在窗内往左上拖时窗口左上角必然越过屏幕缘（负几十~几百像素），
                                    // 这些负值会被保存校验当成“非法坐标”整包丢弃 → 位置丢失回默认。
                                    DisplayMetrics dmDrag = new DisplayMetrics();
                                    wm.getDefaultDisplay().getRealMetrics(dmDrag);
                                    int dragMaxX = Math.max(0, dmDrag.widthPixels - lp.width);
                                    int dragMaxY = Math.max(0, dmDrag.heightPixels - lp.height);
                                    lp.x = Math.min(Math.max(initX + dx, 0), dragMaxX);
                                    lp.y = Math.min(Math.max(initY + dy, 0), dragMaxY);
                                    try {
                                        wm.updateViewLayout(rootView, lp);
                                    } catch (Exception ignored) {
                                    }
                                }
                                return true;
                            }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (pinchActive) {
                                pinchActive = false;
                                return true;
                            }
                            boolean wasDragging = dragging;
                            dragging = false;
                            if (wasDragging && !editMode) {
                                clampLpToScreen();
                                // v1.60_beta2：拖动结束必须写「窗口几何」前缀（pa_/pb_），
                                // 这是 readSlotGeo / applySlotGeometry 唯一读取的键。
                                // 旧实现只写 bsdPrefix(pa_l_/pa_r_) 位置，主窗永远读不到，
                                // 表现为“拖好的位置下次打灯/预警又回到默认”。
                                SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                                ed.putInt(geoPrefix + "overlay_pos_x", lp.x);
                                ed.putInt(geoPrefix + "overlay_pos_y", lp.y);
                                ed.putBoolean(geoPrefix + "overlay_pos_recorded", true);
                                // 旧键同步写一份，兼容历史版本与 bsd_ 前缀读取路径
                                ed.putInt(bsdPrefix + "overlay_pos_x", lp.x);
                                ed.putInt(bsdPrefix + "overlay_pos_y", lp.y);
                                ed.putBoolean(bsdPrefix + "overlay_pos_recorded", true);
                                ed.apply();
                                serviceLog("编辑窗位置拖动已保存[" + titleText + "] geo=" + geoPrefix
                                        + " pos=(" + lp.x + "," + lp.y + ")");
                            }
                            if (!wasDragging && !doubleTapConsumed) {
                                lastTapTime = System.currentTimeMillis();
                            }
                            doubleTapConsumed = false;
                            return wasDragging;
                    }
                    return false;
                }
            });
        }

        /** BSD 编辑窗边缘手柄：改变窗口形状（参照主窗 setupWindowEdgeHandle）。 */
        void setupWindowEdgeHandle2(final View handle, final String sside) {
            if (handle == null) return;
            handle.setOnTouchListener(new View.OnTouchListener() {
                private int startRaw = 0;
                private int startW = 0;
                private int startH = 0;
                private int startX = 0;
                private int startY = 0;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (!editMode) return false;
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startW = lp.width;
                            startH = lp.height;
                            startX = lp.x;
                            startY = lp.y;
                            boolean horizontal = "left".equals(sside) || "right".equals(sside);
                            startRaw = horizontal ? (int) event.getRawX() : (int) event.getRawY();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            boolean h = "left".equals(sside) || "right".equals(sside);
                            int cur = h ? (int) event.getRawX() : (int) event.getRawY();
                            int d = cur - startRaw;
                            DisplayMetrics dm = new DisplayMetrics();
                            wm.getDefaultDisplay().getRealMetrics(dm);
                            int minW = (int) (dm.widthPixels * MIN_SIZE_RATIO);
                            int minH = (int) (dm.heightPixels * MIN_SIZE_RATIO);
                            if ("left".equals(sside)) {
                                int newW = startW - d;
                                if (newW < minW) newW = minW;
                                if (newW > dm.widthPixels) newW = dm.widthPixels;
                                lp.width = newW;
                                lp.x = startX + (startW - newW);
                                if (lp.x < 0) {
                                    lp.x = 0;
                                    lp.width = startX + startW;
                                }
                            } else if ("right".equals(sside)) {
                                int newW = startW + d;
                                if (newW < minW) newW = minW;
                                if (newW > dm.widthPixels) newW = dm.widthPixels;
                                lp.width = newW;
                            } else if ("top".equals(sside)) {
                                int newH = startH - d;
                                if (newH < minH) newH = minH;
                                if (newH > dm.heightPixels) newH = dm.heightPixels;
                                lp.height = newH;
                                lp.y = startY + (startH - newH);
                                if (lp.y < 0) {
                                    lp.y = 0;
                                    lp.height = startY + startH;
                                }
                            } else {
                                int newH = startH + d;
                                if (newH < minH) newH = minH;
                                if (newH > dm.heightPixels) newH = dm.heightPixels;
                                lp.height = newH;
                            }
                            try {
                                wm.updateViewLayout(rootView, lp);
                            } catch (Exception ignored) {
                            }
                            applyRenderTransform();
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            return true;
                    }
                    return false;
                }
            });
        }

        /** BSD 编辑窗四角等比例缩放手柄（参照主窗 setupWindowCornerHandle）。 */
        void setupWindowCornerHandle2(final View handle, final String corner) {
            if (handle == null) return;
            handle.setOnTouchListener(new View.OnTouchListener() {
                private int startRawX = 0;
                private int startRawY = 0;
                private int startW = 0;
                private int startH = 0;
                private int startX = 0;
                private int startY = 0;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (!editMode) return false;
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startRawX = (int) event.getRawX();
                            startRawY = (int) event.getRawY();
                            startW = lp.width;
                            startH = lp.height;
                            startX = lp.x;
                            startY = lp.y;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            int dx = (int) event.getRawX() - startRawX;
                            int dy = (int) event.getRawY() - startRawY;
                            DisplayMetrics dm = new DisplayMetrics();
                            wm.getDefaultDisplay().getRealMetrics(dm);
                            float ratio = (float) startW / startH;
                            int newW = startW;
                            int newH = startH;
                            boolean horizontalDominant = Math.abs(dx) * startH >= Math.abs(dy) * startW;
                            if (horizontalDominant) {
                                if (corner.contains("r")) newW = startW + dx;
                                else newW = startW - dx;
                                newH = (int) (newW / ratio);
                            } else {
                                if (corner.contains("b")) newH = startH + dy;
                                else newH = startH - dy;
                                newW = (int) (newH * ratio);
                            }
                            if (newW < 1) newW = 1;
                            if (newH < 1) newH = 1;
                            int minW = (int) Math.max(dm.widthPixels * MIN_SIZE_RATIO,
                                    dm.heightPixels * MIN_SIZE_RATIO * ratio);
                            int maxW = (int) Math.min(dm.widthPixels, dm.heightPixels * ratio);
                            if (newW < minW) newW = minW;
                            if (newW > maxW) newW = maxW;
                            newH = (int) (newW / ratio);
                            if (newH < 1) newH = 1;
                            lp.width = newW;
                            lp.height = newH;
                            if ("tl".equals(corner)) {
                                lp.x = startX + startW - newW;
                                lp.y = startY + startH - newH;
                            } else if ("tr".equals(corner)) {
                                lp.x = startX;
                                lp.y = startY + startH - newH;
                            } else if ("bl".equals(corner)) {
                                lp.x = startX + startW - newW;
                                lp.y = startY;
                            } else { // br
                                lp.x = startX;
                                lp.y = startY;
                            }
                            if (lp.x < 0) {
                                int rightEdge = startX + startW;
                                lp.x = 0;
                                lp.width = rightEdge;
                                lp.height = (int) (rightEdge / ratio);
                                lp.y = ("tl".equals(corner) || "tr".equals(corner))
                                        ? (startY + startH - lp.height) : startY;
                            }
                            if (lp.y < 0) {
                                int bottomEdge = startY + startH;
                                lp.y = 0;
                                lp.height = bottomEdge;
                                lp.width = (int) (bottomEdge * ratio);
                                lp.x = ("tl".equals(corner) || "bl".equals(corner))
                                        ? (startX + startW - lp.width) : startX;
                            }
                            try {
                                wm.updateViewLayout(rootView, lp);
                            } catch (Exception ignored) {
                            }
                            applyRenderTransform();
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            return true;
                    }
                    return false;
                }
            });
        }
    }

    /** v1.54 beta4：编辑预警浮窗按钮——未召唤时同时召唤左右预警编辑窗；已召唤时同时关闭并保存。 */
    /**
     * v1.58：设置页「优先、次级浮窗位置设置」+「左状态/右状态」入口。
     * 进入后同一侧状态下 P、S 两窗同时以编辑窗呈现，可分别拖动位置、拖边改大小、双指缩放画面；
     * 退出即落盘两窗几何（pa_ / pb_）与该侧画面配置（pa_l_ / pa_r_ / pb_l_ / pb_r_）。
     */
    private void openSlotEditFromUi(String side) {
        if (slotEditActive) {
            closeSlotEditInternal();
            return;
        }
        openSlotEditor(null, normSide(side), true);
    }

    /**
     * 打开槽位编辑。
     * @param slot null 表示同时编辑 P/S 两窗（设置页入口），"P"/"S" 表示只编辑该窗（双击浮窗入口）
     * @param side 当前编辑的状态侧（LEFT/RIGHT），决定画面配置读写哪一套键
     * @param both 是否同时开两窗
     */
    private void openSlotEditor(String slot, String side, boolean both) {
        String sd = normSide(side);
        if (slotEditActive) {
            return;
        }
        slotEditActive = true;
        slotEditSide = sd;
        slotEditSlot = slot;
        hideSlotWindow("P");
        hideSlotWindow("S");
        String label = "LEFT".equals(sd) ? "左状态" : "右状态";
        if (both || "P".equals(slot)) {
            slotEditP = new BsdEditWindow(sd, framePrefixOf("P", sd), geoPrefixOf("P"), "优先窗 · " + label);
            slotEditP.addToWindow();
            if (slotEditP.closeView != null) {
                slotEditP.closeView.setVisibility(View.VISIBLE);
                slotEditP.closeView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        closeSlotEditInternal();
                    }
                });
            }
        }
        if (both || "S".equals(slot)) {
            slotEditS = new BsdEditWindow(sd, framePrefixOf("S", sd), geoPrefixOf("S"), "次级窗 · " + label);
            slotEditS.addToWindow();
            if (slotEditS.closeView != null) {
                slotEditS.closeView.setVisibility(View.VISIBLE);
                slotEditS.closeView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        closeSlotEditInternal();
                    }
                });
            }
        }
        if (cameraDevice != null) {
            createPreviewSession();
        } else {
            savedDirectionBeforeEdit = direction;
            direction = sd;
            bsdEditPreviewActive = true;
            openCamera();
        }
        serviceLog("浮窗编辑已进入（" + label + "）：拖动改位置，拖边改大小，双指缩放画面，点 ✕ 保存退出");
    }

    /** 退出编辑：两窗配置落盘并移除编辑窗，随后按当前信号恢复槽位显示。 */
    private void closeSlotEditInternal() {
        if (!slotEditActive) return;
        if (slotEditP != null) {
            slotEditP.saveConfigInternal();
            slotEditP.removeFromWindow();
            slotEditP = null;
        }
        if (slotEditS != null) {
            slotEditS.saveConfigInternal();
            slotEditS.removeFromWindow();
            slotEditS = null;
        }
        slotEditActive = false;
        // v1.60_beta2：编辑窗保存后必须立刻把主窗 lp 同步成最新几何。
        // 否则主窗仍拿着编辑前的旧坐标，随后熄灯/让位流程里的 saveEditConfigInternal
        // 会用这份陈旧坐标把刚保存的位置覆盖回去——用户表现就是“拖好的位置永远不记录”。
        syncSlotLpFromPrefs("P");
        syncSlotLpFromPrefs("S");
        serviceLog("浮窗位置/画面编辑已保存（" + ("LEFT".equals(slotEditSide) ? "左状态" : "右状态") + "）");
        if (bsdEditPreviewActive) {
            bsdEditPreviewActive = false;
            if (savedDirectionBeforeEdit != null) {
                direction = savedDirectionBeforeEdit;
                savedDirectionBeforeEdit = null;
            }
        }
        evaluateSlots("编辑退出恢复");
        // v1.60：编辑窗退出后强制作废会话签名并重建一次。仅靠 evaluateSlots 的可见性刷新
        // 在主窗始终可见时不会触发重建，会话仍停留在"含编辑窗 output"的旧配置上，
        // 主窗因此停帧在编辑时画面（Bug①）。
        if (cameraDevice != null && hasAnyPreviewTarget()) {
            sessionTargetSig = "";
            requestSessionRebuild(120L);
        }
    }

    /** v1.60_beta2：把 prefs 里最新的槽位几何同步进窗口 lp（不落盘、不改可见性）。
     * 用于编辑窗保存后同步主窗/次窗内存坐标，避免后续保存动作写回陈旧值。 */
    private void syncSlotLpFromPrefs(String slot) {
        View host = slotHost(slot);
        if (host == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int[] geo = readSlotGeo(slot, dm);
        if ("P".equals(slot)) {
            lastPGeom = new int[] {geo[0], geo[1], geo[2], geo[3]};
        }
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) host.getLayoutParams();
        lp.width = geo[0];
        lp.height = geo[1];
        // 隐藏态保持隐藏坐标，只同步尺寸，防止隐形浮窗重新跑回屏幕内
        if ("P".equals(slot) ? slotPShown : slotSShown) {
            lp.x = geo[2];
            lp.y = geo[3];
        }
        try {
            wm.updateViewLayout(host, lp);
        } catch (Exception ignored) {
        }
        serviceLog("槽位内存几何已同步[" + slot + "] pos=(" + lp.x + "," + lp.y + ")");
    }

    /** 一键同步大小：把优先窗 P 的当前宽高套用到次级窗 S（保持各自位置）。 */
    private void syncSlotSizeInternal() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int[] gp = readSlotGeo("P", dm);
        SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        ed.putInt("pb_overlay_w", gp[0]);
        ed.putInt("pb_overlay_h", gp[1]);
        ed.apply();
        if (bsdRightOverlayView != null) {
            applySlotGeometry("S", bsdRightOverlayView);
            applySlotTransform("S", slotSSide == null ? "RIGHT" : slotSSide);
        }
        if (slotEditS != null) slotEditS.reloadFromPrefs();
        serviceLog("一键同步大小：次级窗 S 已同步为 " + gp[0] + "x" + gp[1]);
    }

    /** 复位两窗几何与四套画面配置到默认值。 */
    private void resetSlotGeometryInternal() {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        String[] geoPrefixes = new String[] {"pa_", "pb_"};
        String[] geoKeys = new String[] {"overlay_w", "overlay_h", "overlay_pos_x",
                "overlay_pos_y", "overlay_pos_recorded"};
        for (String g : geoPrefixes) {
            for (String k : geoKeys) {
                ed.remove(g + k);
            }
        }
        String[] framePrefixes = new String[] {"pa_l_", "pa_r_", "pb_l_", "pb_r_"};
        String[] frameKeys = new String[] {"cam_scale", "pan_x", "pan_y"};
        for (String f : framePrefixes) {
            for (String k : frameKeys) {
                ed.remove(f + k);
            }
        }
        ed.apply();
        if (overlayView != null) {
            applySlotGeometry("P", overlayView);
            applySlotTransform("P", slotPSide == null ? "LEFT" : slotPSide);
        }
        if (bsdRightOverlayView != null) {
            applySlotGeometry("S", bsdRightOverlayView);
            applySlotTransform("S", slotSSide == null ? "RIGHT" : slotSSide);
        }
        if (slotEditP != null) slotEditP.reloadFromPrefs();
        if (slotEditS != null) slotEditS.reloadFromPrefs();
        serviceLog("浮窗位置/大小/画面已复位为默认");
    }

    /**
     * v1.60：几何键迁移辅助——只在源键真实存在时映射，并同步 overlay_pos_recorded 标记。
     * 旧实现用 prefs.getInt("..._pos_x", 0) 无条件写入，源键缺失时会把目标位置写成 (0,0)，
     * 浮窗因此贴到屏幕最左上角，且 recorded 标记始终为 false，编辑窗永远落到硬编码位置。
     */
    private void copyGeoKeys(SharedPreferences prefs, SharedPreferences.Editor ed,
                             String from, String to) {
        if (!prefs.contains(from + "overlay_w")) return;
        ed.putInt(to + "overlay_w", prefs.getInt(from + "overlay_w", 0));
        ed.putInt(to + "overlay_h", prefs.getInt(from + "overlay_h", 0));
        if (prefs.contains(from + "overlay_pos_x")) {
            int px = prefs.getInt(from + "overlay_pos_x", 0);
            int py = prefs.getInt(from + "overlay_pos_y", 0);
            if (px > 0 || py > 0) {
                ed.putInt(to + "overlay_pos_x", px);
                ed.putInt(to + "overlay_pos_y", py);
                ed.putBoolean(to + "overlay_pos_recorded", true);
            }
        }
    }

    /** v1.58 一次性迁移：旧键（left_/right_/bsd_left_/bsd_right_、overlay_*）映射到新键。 */
    private void migrateV158() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean("migrate_v158", false)) return;
        SharedPreferences.Editor ed = prefs.edit();
        // 优先窗几何：left_ 系列优先，回退 v1.30 通用 overlay_ 系列
        copyGeoKeys(prefs, ed, "left_", "pa_");
        copyGeoKeys(prefs, ed, "overlay_", "pa_");
        // 次级窗几何：bsd_right_ 系列优先（v1.54 编辑过的右预警窗）
        copyGeoKeys(prefs, ed, "bsd_right_", "pb_");
        copyFrameKeys(prefs, ed, "left_", "pa_l_", null);
        copyFrameKeys(prefs, ed, "right_", "pa_r_", null);
        copyFrameKeys(prefs, ed, "bsd_left_", "pb_l_", "left_");
        copyFrameKeys(prefs, ed, "bsd_right_", "pb_r_", "right_");
        ed.putBoolean("migrate_v158", true);
        ed.apply();
        serviceLog("v1.58 配置迁移完成：优先窗/次级窗几何与四套画面配置已映射");
    }

    /** 迁移单套画面配置；主键缺失时回退 fallbackPrefix 同名字段。 */
    private void copyFrameKeys(SharedPreferences prefs, SharedPreferences.Editor ed,
                               String fromPrefix, String toPrefix, String fallbackPrefix) {
        String[] keys = new String[] {"cam_scale", "pan_x", "pan_y"};
        for (String k : keys) {
            if (prefs.contains(fromPrefix + k)) {
                ed.putFloat(toPrefix + k, prefs.getFloat(fromPrefix + k, 0f));
            } else if (fallbackPrefix != null && prefs.contains(fallbackPrefix + k)) {
                ed.putFloat(toPrefix + k, prefs.getFloat(fallbackPrefix + k, 0f));
            }
        }
    }

    /** v1.54 beta4：预警浮窗同步转向灯浮窗——左对左、右对右复制位置/大小/画面 pan 缩放。
     * v1.54 beta5：同步范围增加 camera_id（left_camera_id -> bsd_left_camera_id、
     * right_camera_id -> bsd_right_camera_id），保证预警浮窗画面与转向灯浮窗使用同一摄像头。 */
    private void syncBsdToTurnInternal() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
        String[] from = new String[]{"left_", "right_"};
        String[] to = new String[]{"bsd_left_", "bsd_right_"};
        String[] keys = new String[]{"overlay_w", "overlay_h", "overlay_pos_x", "overlay_pos_y",
                "cam_scale", "pan_x", "pan_y", "camera_id"};
        for (int i = 0; i < from.length; i++) {
            for (String k : keys) {
                if (prefs.contains(from[i] + k)) {
                    if ("overlay_pos_x".equals(k) || "overlay_pos_y".equals(k)) {
                        ed.putInt(to[i] + k, prefs.getInt(from[i] + k, 0));
                    } else if ("overlay_w".equals(k) || "overlay_h".equals(k)) {
                        ed.putInt(to[i] + k, prefs.getInt(from[i] + k, 0));
                    } else if ("camera_id".equals(k)) {
                        ed.putString(to[i] + k, prefs.getString(from[i] + k, "8"));
                    } else {
                        ed.putFloat(to[i] + k, prefs.getFloat(from[i] + k, 0f));
                    }
                }
            }
            ed.putBoolean(to[i] + "overlay_pos_recorded", true);
        }
        ed.apply();
        if (bsdEditLeft != null) bsdEditLeft.reloadFromPrefs();
        if (bsdEditRight != null) bsdEditRight.reloadFromPrefs();
        serviceLog("BSD 预警浮窗已同步转向灯浮窗配置（左对左/右对右：位置/大小/画面/cameraId）");
    }

    /** v1.54 beta4：单独关闭某侧 BSD 预警编辑窗并保存该侧配置（编辑窗右上角 ✕）。 */
    private void closeBsdEditWindowInternal(String side) {
        if ("LEFT".equals(side) && bsdEditLeft != null) {
            bsdEditLeft.saveConfigInternal();
            bsdEditLeft.removeFromWindow();
            bsdEditLeft = null;
            serviceLog("BSD 左预警编辑窗已单独关闭并保存");
        } else if ("RIGHT".equals(side) && bsdEditRight != null) {
            bsdEditRight.saveConfigInternal();
            bsdEditRight.removeFromWindow();
            bsdEditRight = null;
            serviceLog("BSD 右预警编辑窗已单独关闭并保存");
        }
        if (bsdEditLeft == null && bsdEditRight == null) {
            bsdEditActive = false;
            // v1.54 beta6：最后一个编辑窗关闭后，若为编辑态独立启动的预览则完整释放
            if (bsdEditPreviewActive) {
                bsdEditPreviewActive = false;
                releaseCamera();
                if (savedDirectionBeforeEdit != null) {
                    direction = savedDirectionBeforeEdit;
                    savedDirectionBeforeEdit = null;
                }
            }
        }
    }

    /** v1.54 beta4：BSD 左预警弹窗时应用用户编辑的 bsd_left_ 键（位置/大小/画面），未编辑时保持 beta3 现状。 */
    private void applyBsdLeftConfigIfPresent() {
        if (overlayView == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.contains("bsd_left_cam_scale")
                && !prefs.contains("bsd_left_overlay_w")) {
            return;
        }
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            int minW = (int) (dm.widthPixels * MIN_SIZE_RATIO);
            int minH = (int) (dm.heightPixels * MIN_SIZE_RATIO);
            int w = prefs.getInt("bsd_left_overlay_w", lp.width);
            int h = prefs.getInt("bsd_left_overlay_h", lp.height);
            if (w < minW) w = minW;
            if (w > dm.widthPixels) w = dm.widthPixels;
            if (h < minH) h = minH;
            if (h > dm.heightPixels) h = dm.heightPixels;
            lp.width = w;
            lp.height = h;
            // v1.60_beta2：位置一律以槽位几何（pa_ + recorded）为准。
            // 旧实现读 bsd_left_overlay_pos_*，会覆盖用户/编辑窗刚保存的 pa_ 位置，
            // 表现为“设置页拖好的位置，BSD 左预警一弹就跳回旧位置”。
            int[] geoBsd = readSlotGeo("P", dm);
            lp.x = geoBsd[2];
            lp.y = geoBsd[3];
            wm.updateViewLayout(overlayView, lp);
            applyBsdLeftRenderTransform();
            serviceLog("BSD 左预警弹窗应用 bsd_left_ 自定义配置 w=" + w + " h=" + h);
        } catch (Exception e) {
            Log.e(TAG, "applyBsdLeftConfigIfPresent failed", e);
        }
    }

    /** v1.54 beta4：BSD 左预警画面渲染——使用 bsd_left_ 独立键（含默认初始左下 1/4 裁切与编辑后的自由平移）。 */
    private void applyBsdLeftRenderTransform() {
        if (overlayView == null || textureView == null) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlayView.getLayoutParams();
        int vw = lp.width;
        int vh = lp.height;
        if (vw <= 0) vw = 1;
        if (vh <= 0) vh = 1;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        float defW = dm.widthPixels * OVERLAY_DEF_W_RATIO;
        float defH = defW * 9f / 16f;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        float cs = prefs.getFloat("bsd_left_cam_scale",
                prefs.getFloat("left_cam_scale", CAM_SCALE_DEFAULT));
        if (cs < CAM_SCALE_MIN) cs = CAM_SCALE_MIN;
        if (cs > CAM_SCALE_MAX) cs = CAM_SCALE_MAX;
        float Wv = defW * cs;
        float Hv = defH * cs;
        float scale = Wv / BUFFER_W;
        float dx;
        float dy;
        boolean defaultCrop = !prefs.contains("bsd_left_cam_scale");
        if (defaultCrop) {
            float fit = Math.min(vw / Wv, vh / Hv);
            scale = (Wv / BUFFER_W) * fit;
            dx = (vw - Wv * fit) / 2f;
            dy = (vh - Hv * fit) / 2f;
        } else {
            float panX = prefs.getFloat("bsd_left_pan_x",
                    prefs.getFloat("left_pan_x", 0f));
            float panY = prefs.getFloat("bsd_left_pan_y",
                    prefs.getFloat("left_pan_y", 0f));
            dx = (vw - Wv) / 2f + panX;
            dy = (vh - Hv) / 2f + panY;
        }
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.reset();
        m.postScale(scale, scale);
        m.postTranslate(dx, dy);
        textureView.setTransform(m);
        serviceLog("BSD左窗RT vw=" + vw + " vh=" + vh + " Wv=" + (int) Wv + " Hv=" + (int) Hv
                + " scale=" + String.format("%.4f", scale) + " dx=" + (int) dx + " dy=" + (int) dy);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
