package com.xiaopeng.qa.camera;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * v1.64：相机单路输入 → GL 分发到多路输出的中转器（每路独立 EGL 线程版）。
 *
 * <p>为什么必须"每路独立线程"：TextureView 的 BufferQueue 是同步队列，只要 hwui 那一帧
 * 没能把这个 buffer 消费掉，生产端的 {@code eglSwapBuffers} 就会一直阻塞。v1.62/v1.63 把
 * 所有输出放在同一个 GL 线程里画，只要有一路卡住，整个线程（包括相机输入的
 * {@code updateTexImage}）一起停摆——表现就是主窗和次窗同时冻在最后一帧。</p>
 *
 * <p>本实现的线程模型：</p>
 * <ul>
 *   <li><b>输入线程</b>（{@code CameraGlRelayIn}）：独占相机输出面，只做
 *       {@code updateTexImage} + 取变换矩阵 + 向各输出线程派发，永不做 swap。</li>
 *   <li><b>输出线程</b>（{@code RelayOut-P} / {@code RelayOut-S} …）：每路一个线程、
 *       一个与输入线程共享的 EGL 上下文、一块自己的 EGLSurface，只负责自己那一路的
 *       draw + swap。</li>
 * </ul>
 *
 * <p>于是某一路的 swap 阻塞只会冻住它自己：相机继续出帧、其他窗口继续刷新；
 * 看门狗发现某路占用超过 1.5s 即把该路隔离并回调，由服务层换掉那个 TextureView
 * （新队列 = 新读端）自愈。</p>
 */
public class GlCameraRelay {

    private static final String TAG = "CameraRelay";

    /** 某一路占用超过该时长即判定阻塞（swap 未返回）。 */
    private static final long STALL_MS = 700L;
    /** 看门狗巡检间隔。 */
    private static final long WATCH_MS = 250L;
    /** 单帧 swap 超过该时长视为下游慢，连续 2 次即暂停该路，稍后再试。 */
    private static final long SLOW_SWAP_MS = 200L;
    /** v1.65：某一路持续该时长没有成功 swap，判定它在出图链路上已静默死掉（画面冻结）。 */
    private static final long SILENT_STALL_MS = 1200L;
    /**
     * v1.69：新输出（尚未成功 swap 过一帧）的静默宽限。
     *
     * <p>一个刚建出来的输出要等：相机 open（含 CAMERA_IN_USE 重试 300/600/900ms）+ 会话配置
     * + 首帧到达 + 输出线程建 EGL，1.68 用的 SILENT_STALL_MS*2=2400ms 经常不够，
     * 于是新纹理还没出画就被判死换掉，换完又来不及出画，形成「判死→换纹理→再判死」的
     * 自愈风暴——正是「更容易卡死、只是能跳出来」的体感来源。
     */
    private static final long NEW_OUTPUT_STALL_GRACE_MS = 4500L;
    /** v1.65：同一块纹理连续重建该次数仍异常时，通知上层换纹理（绝不禁用该路）。 */
    private static final int REVIVE_NOTIFY_MAX = 4;

    /** 某一路被判定阻塞时的回调（在主线程 Handler 上触发，调用方可直接操作 UI）。 */
    public interface OutputStallListener {
        void onOutputStalled(String key);
    }

    /** 单个显示端：TextureView 的 SurfaceTexture，或 MediaCodec / VirtualDisplay 的 Surface。 */
    public static final class Target {
        public final String key;
        public final SurfaceTexture texture;
        public final Surface surface;
        /**
         * v1.67：软可见性。false 表示该窗当前视觉上不可见（面板淡出 alpha→0、收起过渡），
         * 但它依然能正常接收帧。此时输出保留在输出表里、只跳过绘制，
         * 避免「淡出摘表 → 淡入重建」反复拆装线程与 EGL 资源。
         */
        public volatile boolean paused = false;

        private Target(String key, SurfaceTexture texture, Surface surface) {
            this.key = key;
            this.texture = texture;
            this.surface = surface;
        }

        public static Target ofTexture(String key, SurfaceTexture st) {
            return st == null ? null : new Target(key, st, null);
        }

        public static Target ofSurface(String key, Surface s) {
            return s == null ? null : new Target(key, null, s);
        }

        String identity() {
            return key + "#" + (texture != null
                    ? "T" + System.identityHashCode(texture)
                    : "S" + System.identityHashCode(surface));
        }
    }

    private static final float[] QUAD = new float[]{
            // x, y, u, v
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
    };

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n"
                    + "attribute vec4 aTexCoord;\n"
                    + "uniform mat4 uTexMatrix;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "    gl_Position = aPosition;\n"
                    + "    vTexCoord = (uTexMatrix * aTexCoord).xy;\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "uniform samplerExternalOES uTexture;\n"
                    + "void main() {\n"
                    + "    gl_FragColor = texture2D(uTexture, vTexCoord);\n"
                    + "}\n";

    /** 一路独立输出：自己的线程、共享上下文、自己的 EGLSurface 与 program。 */
    private static final class Output {
        final Target target;
        final String identity;
        final FloatBuffer quadBuf;

        EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        Surface ownedSurface = null;
        int width = 0;
        int height = 0;

        HandlerThread thread;
        Handler handler;
        EGLContext ctx = EGL14.EGL_NO_CONTEXT;

        int program = 0;
        int aPosition = -1;
        int aTexCoord = -1;
        int uTexMatrix = -1;
        int uTexture = -1;

        /** 主线程每帧写入，输出线程读取；busy 为 false 时才写入，故无需额外同步。 */
        final float[] texMatrix = new float[16];
        volatile long framePtsNs = 0L;

        /** true 表示已派发一帧且尚未返回，主线程据此丢帧而不排队。 */
        volatile boolean busy = false;
        volatile long busySinceMs = 0L;
        /** 判定卡死后置 true，之后不再派发、不再销毁（线程可能永久阻塞）。 */
        volatile boolean dead = false;

        long lastSwapMs = 0L;
        /** v1.65：创建时刻，首帧还没出来时用它做静默判定锚点。 */
        long createdMs = System.currentTimeMillis();
        long minIntervalMs = 0L;
        int slowStreak = 0;
        int failStreak = 0;
        int stallCount = 0;
        /** v1.67 beta2：最近一次 eglSwapBuffers 耗时（ms）。输出线程写、看门狗读。 */
        volatile long swapCostMs = 0L;
        /** v1.67 beta2：本实例内该路单次 swap 最大耗时（ms）。 */
        volatile long swapMaxMs = 0L;
        /** v1.67 beta2：该路 swap 耗时 >= STALL_MS 的累计次数。 */
        volatile int swapOver700 = 0;
        /** v1.67 beta2：该路被判死隔离（自愈一次）的累计次数。 */
        volatile int recoverCount = 0;
        /** v1.65b：连续被判「静默无 swap」的巡检轮数，swap 成功即清零。 */
        int silentStreak = 0;
        /** v1.67：是否已计入 leakedOutputs，防止重复计数。 */
        boolean counted = false;
        /** v1.67：软可见性暂停。paused 的输出留在表里但不 swap——不制造背压，也不必重建。 */
        volatile boolean paused = false;

        Output(Target target, String identity) {
            this.target = target;
            this.identity = identity;
            this.quadBuf = ByteBuffer.allocateDirect(QUAD.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            this.quadBuf.put(QUAD).position(0);
        }
    }

    private final int bufW;
    private final int bufH;

    /** 输入线程：持有相机输出面，只取帧不 swap。 */
    private HandlerThread thread;
    private Handler handler;

    /** 供所有输出线程共享的上下文（各输出线程以此作为 share context）。 */
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig = null;
    private EGLSurface eglIdle = EGL14.EGL_NO_SURFACE;

    private SurfaceTexture inputTexture;
    private Surface inputSurface;
    private int inputTexId = 0;

    private final float[] texMatrix = new float[16];
    private volatile long lastPtsNs = 0L;

    private final LinkedHashMap<String, Output> outputs = new LinkedHashMap<>();
    /** 当前有效输出路数（volatile，供其他线程安全读取）。 */
    private volatile int liveCount = 0;
    /** 每个 key 重建次数，防止某块纹理反复卡死导致线程无限堆积。 */
    private final java.util.HashMap<String, Integer> reviveCount = new java.util.HashMap<>();
    /** GL 未就绪时暂存待应用的输出表。 */
    private ArrayList<Target> pendingOutputs = null;

    private volatile boolean alive = false;
    private volatile long frameCount = 0L;
    private volatile long lastFrameMs = 0L;
    private long lastSlowLogMs = 0L;
    /** 多路并发时每路的基准节流间隔，由服务侧按输出路数下发。 */
    private volatile long baseIntervalMs = 0L;
    /** 输出阻塞回调（服务层用来换掉出问题的那个 TextureView）。 */
    private volatile OutputStallListener stallListener = null;
    /** v1.65：逐路最近一次成功 swap 的时刻，供上层诊断/看门狗跨线程读取。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastSwapSnapshot =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** v1.65：上一次静默巡检时的输入帧计数，用于识别「输入本来就没帧」。 */
    private long lastStallCheckFrame = -1L;

    // ---------------- v1.67 泄漏治理：僵尸输出表 ----------------
    /** 新自愈逻辑总开关（false 时回退到 v1.66 的旧销毁路径）。 */
    public static volatile boolean selfHealV2 = true;
    /** 僵尸输出上限：超过即请求整实例重建。 */
    private static final int ZOMBIE_MAX = 8;
    /** 单个僵尸最长存活时间：到期仍不退出即放弃并请求整实例重建。 */
    private static final long ZOMBIE_TTL_MS = 10000L;
    /** 已异常摘表、但线程尚未退出因而无法回收 EGL 的输出。只在输入线程访问。 */
    private final java.util.ArrayList<Zombie> zombies = new java.util.ArrayList<>();
    /** 对外可见的泄漏路数（供服务层判断资源压力）。 */
    private volatile int leakedOutputs = 0;
    /** release 承载 Surface 后线程仍存活的累计次数（用于判定驱动是否支持解套）。 */
    private int releaseIneffective = 0;
    /** release 承载 Surface 已验证可解套。 */
    private volatile boolean surfaceReleaseWorks = false;
    /** 是否已请求上层整实例重建。 */
    private volatile boolean needFullRebuild = false;
    /** display 已损坏（initGl 失败过），release 时才允许 eglTerminate。 */
    private boolean displayPoisoned = false;

    /** 一路待回收的输出：线程退出前绝不触碰它的 EGL。 */
    private static final class Zombie {
        final Output o;
        final long sinceMs;
        final boolean surfaceReleased;
        boolean ineffectiveCounted = false;

        Zombie(Output o, long sinceMs, boolean surfaceReleased) {
            this.o = o;
            this.sinceMs = sinceMs;
            this.surfaceReleased = surfaceReleased;
        }
    }

    public GlCameraRelay(int bufferWidth, int bufferHeight) {
        this.bufW = bufferWidth > 0 ? bufferWidth : 1280;
        this.bufH = bufferHeight > 0 ? bufferHeight : 720;
    }

    public boolean isAlive() {
        return alive;
    }

    public long getFrameCount() {
        return frameCount;
    }

    public long getLastFrameMs() {
        return lastFrameMs;
    }

    public int outputCount() {
        return liveCount;
    }

    public int getOutputCount() {
        return liveCount;
    }

    /** v1.65：某一路最近一次成功 swap 的时刻（0 表示还没出过帧）。 */
    public long getLastSwapMs(String key) {
        Long v = lastSwapSnapshot.get(key);
        return v == null ? 0L : v.longValue();
    }

    /** v1.67 beta3：某一路当前是否在输出表里（主窗探针用）。 */
    public boolean hasOutput(String key) {
        return key != null && outputs.containsKey(key);
    }

    /**
     * v1.67 beta3：单路输出快照（主窗更新探针用）。
     * 只在看门狗/巡检线程调用，与 dumpOutputStatus 同一访问口径，不加锁。
     */
    public String snapshotOutput(String key, long now) {
        Output o = outputs.get(key);
        if (o == null) return "表内无此路";
        return "暂停=" + (o.paused ? 1 : 0)
                + " 死=" + (o.dead ? 1 : 0)
                + " busy=" + (o.busy ? (now - o.busySinceMs) + "ms" : "-")
                + " 末次swap=" + (o.lastSwapMs > 0L ? (now - o.lastSwapMs) + "ms前" : "从未")
                + " 本次=" + o.swapCostMs + "ms"
                + " 峰值=" + o.swapMaxMs + "ms"
                + " 超700=" + o.swapOver700
                + " 判死=" + o.stallCount
                + " 救=" + o.recoverCount
                + " 面=" + o.width + "x" + o.height;
    }

    /** 设置每路基准节流间隔（多路时压低每路出帧节奏，给显示消费留余量）。 */
    public void setBaseIntervalMs(long ms) {
        this.baseIntervalMs = ms > 0L ? ms : 0L;
    }

    public void setOutputStallListener(OutputStallListener l) {
        this.stallListener = l;
    }

    /**
     * 放弃本实例：某些输出线程可能已永久阻塞在 eglSwapBuffers 里，绝不能在此等待它们，
     * 只置失效并让线程自行退出（阻塞的自然结束后由系统回收）。
     */
    public void abandon() {
        alive = false;
        try {
            if (inputTexture != null) {
                inputTexture.setOnFrameAvailableListener(null);
            }
        } catch (Throwable ignored) {
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        for (Output o : outputs.values()) {
            o.dead = true;
            if (o.handler != null) o.handler.removeCallbacksAndMessages(null);
            // v1.67：abandon 也必须断开承载 Surface，否则卡在 eglSwapBuffers 里的线程
            // 永不返回，该线程与它的 EGLSurface 会永久留在进程里——这是「越用越漏」的主源
            if (o.ownedSurface != null) {
                try {
                    o.ownedSurface.release();
                } catch (Throwable ignored) {
                }
                o.ownedSurface = null;
            }
            if (o.thread != null) {
                try {
                    o.thread.quitSafely();
                } catch (Throwable ignored) {
                }
            }
        }
        outputs.clear();
        for (Zombie z : zombies) {
            if (z.o.counted) {
                z.o.counted = false;
                leakedOutputs = Math.max(0, leakedOutputs - 1);
            }
        }
        zombies.clear();
        try {
            if (thread != null) thread.quitSafely();
        } catch (Throwable ignored) {
        }
        handler = null;
    }

    /**
     * 取相机输出面（唯一一路）。首次调用会同步完成 EGL/GL 初始化。
     */
    public Surface obtainInputSurface() {
        ensureThread();
        if (inputSurface != null && inputSurface.isValid()) {
            return inputSurface;
        }
        if (handler == null) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                        initGl();
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "initGl failed", t);
                    eglDisplay = EGL14.EGL_NO_DISPLAY;
                    displayPoisoned = true;
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(2000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        if (inputSurface != null && inputSurface.isValid()) {
            return inputSurface;
        }
        return null;
    }

    /** 设置当前需要显示的输出列表（差量应用）。相机侧完全不受影响。 */
    public void setOutputs(List<Target> targets) {
        final ArrayList<Target> copy = new ArrayList<>();
        if (targets != null) {
            for (Target t : targets) {
                if (t != null) copy.add(t);
            }
        }
        if (handler == null) {
            pendingOutputs = copy;
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    applyOutputs(copy);
                } catch (Throwable t) {
                    Log.e(TAG, "applyOutputs failed", t);
                }
            }
        });
    }

    /**
     * 摘除某输出并释放其 EGLSurface 与纹理（在 GL 就绪前调用也只记账）。
     * 供 TextureView.onSurfaceTextureDestroyed 使用。
     */
    public void detachOutput(final String key, final SurfaceTexture st) {
        Runnable task = new Runnable() {
            @Override
            public void run() {
                Output o = outputs.get(key);
                if (o != null && (st == null || o.target.texture == st)) {
                    outputs.remove(key);
                    // v1.67：统一走 discardOutput——先 release 承载 Surface 解套，
                    // 再进僵尸表等线程退出，绝不把 busy 中的输出直接丢引用
                    destroyOutput(o);
                }
                if (st != null) {
                    try {
                        st.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        };
        if (handler == null) {
            task.run();
            return;
        }
        handler.post(task);
    }

    /** 完整释放：销毁全部输出、EGL 上下文、线程。幂等，且不会等待可能已阻塞的输出线程。 */
    public void release() {
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    releaseGl();
                } catch (Throwable t) {
                    Log.e(TAG, "releaseGl failed", t);
                }
            }
        };
        if (handler == null) {
            task.run();
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(1000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        handler = null;
    }

    // ---------------- 输入线程 ----------------

    private void ensureThread() {
        if (thread != null) return;
        thread = new HandlerThread("CameraGlRelayIn");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    private void initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay 失败");
            return;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize 失败");
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            return;
        }
        int[] configAttrib = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfig = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttrib, 0, configs, 0, 1, numConfig, 0)
                || numConfig[0] <= 0) {
            Log.e(TAG, "eglChooseConfig 失败");
            return;
        }
        eglConfig = configs[0];
        int[] ctxAttrib = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                ctxAttrib, 0);
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext 失败");
            return;
        }
        int[] pbAttrib = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        eglIdle = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbAttrib, 0);
        if (eglIdle == null || eglIdle == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreatePbufferSurface 失败");
            return;
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglIdle, eglIdle, eglContext)) {
            Log.e(TAG, "eglMakeCurrent(idle) 失败");
            return;
        }
        inputTexId = createExternalTexture();
        if (inputTexId == 0) {
            Log.e(TAG, "外部纹理创建失败");
            return;
        }
        inputTexture = new SurfaceTexture(inputTexId);
        inputTexture.setDefaultBufferSize(bufW, bufH);
        inputTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                drawFrame();
            }
        }, handler);
        inputSurface = new Surface(inputTexture);
        alive = true;
        Log.i(TAG, "GL 中转就绪（多输出独立线程）buffer=" + bufW + "x" + bufH);
        if (pendingOutputs != null) {
            ArrayList<Target> p = pendingOutputs;
            pendingOutputs = null;
            applyOutputs(p);
        }
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCH_MS);
    }

    private int createExternalTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        if (tex[0] == 0) return 0;
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return tex[0];
    }

    /**
     * 输入线程：取一帧，然后把这一帧派发给所有空闲的输出。
     *
     * <p>这里绝不调用 eglSwapBuffers——取帧不被任何一路的下游拖住，相机可持续出帧。</p>
     */
    private void drawFrame() {
        if (!alive || inputTexture == null) return;
        try {
            if (EGL14.eglGetCurrentContext() != eglContext) {
                EGL14.eglMakeCurrent(eglDisplay, eglIdle, eglIdle, eglContext);
            }
            inputTexture.updateTexImage();
            inputTexture.getTransformMatrix(texMatrix);
        } catch (Throwable t) {
            return;
        }
        frameCount++;
        lastFrameMs = System.currentTimeMillis();
        long pts = 0L;
        try {
            pts = inputTexture.getTimestamp();
        } catch (Throwable ignored) {
        }
        lastPtsNs = pts;
        // 让纹理更新对其他（共享）上下文可见
        try {
            GLES20.glFlush();
        } catch (Throwable ignored) {
        }
        if (outputs.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Output o : outputs.values()) {
            if (o.dead || o.handler == null) continue;
            if (o.busy) continue;  // 上一帧还没画完：丢帧，绝不排队堆积
            // v1.67：可见性暂停中的输出保留在表里但不绘制。既不制造 BufferQueue 背压，
            // 也不必为一次面板淡入淡出反复重建线程与 EGL 资源。
            if (o.paused) continue;
            long interval = Math.max(o.minIntervalMs, baseIntervalMs);
            if (interval > 0L && now - o.lastSwapMs < interval) continue;
            o.busy = true;
            o.busySinceMs = now;
            System.arraycopy(texMatrix, 0, o.texMatrix, 0, 16);
            o.framePtsNs = pts;
            o.handler.post(new DrawTask(o));
        }
    }

    /** 一路输出的一帧绘制任务（运行在该路自己的线程上）。 */
    private final class DrawTask implements Runnable {
        private final Output o;

        DrawTask(Output o) {
            this.o = o;
        }

        @Override
        public void run() {
            if (!alive || o.dead) {
                o.busy = false;
                return;
            }
            try {
                if (o.eglSurface == null || o.eglSurface == EGL14.EGL_NO_SURFACE) return;
                // v1.64b：承载面已失效（TextureView 被销毁/移除）时不进 swap
                // ——往失效 BufferQueue 里投帧必然永久阻塞
                if (o.ownedSurface != null && !o.ownedSurface.isValid()) {
                    o.dead = true;
                    notifyStall(o.target.key);
                    return;
                }
                if (o.target.surface != null && !o.target.surface.isValid()) {
                    o.dead = true;
                    notifyStall(o.target.key);
                    return;
                }
                if (EGL14.eglGetCurrentContext() != o.ctx) {
                    if (!EGL14.eglMakeCurrent(eglDisplay, o.eglSurface, o.eglSurface, o.ctx)) {
                        if (++o.failStreak >= 5) {
                            o.dead = true;
                            Log.w(TAG, "输出 makeCurrent 连续失败，隔离该路 key=" + o.target.key);
                            notifyStall(o.target.key);
                        }
                        return;
                    }
                }
                o.failStreak = 0;
                GLES20.glViewport(0, 0, o.width, o.height);
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glUseProgram(o.program);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexId);
                GLES20.glUniform1i(o.uTexture, 0);
                GLES20.glUniformMatrix4fv(o.uTexMatrix, 1, false, o.texMatrix, 0);
                o.quadBuf.position(0);
                GLES20.glVertexAttribPointer(o.aPosition, 2, GLES20.GL_FLOAT, false, 16, o.quadBuf);
                GLES20.glEnableVertexAttribArray(o.aPosition);
                o.quadBuf.position(2);
                GLES20.glVertexAttribPointer(o.aTexCoord, 2, GLES20.GL_FLOAT, false, 16, o.quadBuf);
                GLES20.glEnableVertexAttribArray(o.aTexCoord);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                if (o.target.surface != null && o.framePtsNs > 0L) {
                    // 编码器输入面需要时间戳，否则 MediaCodec 会等不到有效帧
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, o.eglSurface, o.framePtsNs);
                }
                long t0 = System.nanoTime();
                EGL14.eglSwapBuffers(eglDisplay, o.eglSurface);
                long swapMs = (System.nanoTime() - t0) / 1000000L;
                o.lastSwapMs = System.currentTimeMillis();
                o.silentStreak = 0;
                // v1.67 beta2：逐路 swap 耗时打点。必须在 relay 自己的输出线程里取时，
                // 且以 nanoTime 差值换算（currentTimeMillis 的 1~10ms 精度测不出短时抖动）。
                o.swapCostMs = swapMs;
                if (swapMs > o.swapMaxMs) o.swapMaxMs = swapMs;
                if (swapMs >= STALL_MS) o.swapOver700++;
                lastSwapSnapshot.put(o.target.key, o.lastSwapMs);
                // v1.64b：只有多路并存时才自适应节流；单路满帧输出（节流救不了下游慢消费，
                // 只会让单路画面更不连贯）
                if (swapMs > 60L && viewOutputCount() > 1) {
                    // v1.69：起步 16ms、步长 16ms（原起步直接跳 33ms，一次就吃掉两帧间隔，
                    // 单看画面就是「一顿」）；上限仍 200ms
                    o.minIntervalMs = o.minIntervalMs <= 0L
                            ? 16L : Math.min(200L, o.minIntervalMs + 16L);
                    long slowNow = System.currentTimeMillis();
                    if (slowNow - lastSlowLogMs > 5000L) {
                        lastSlowLogMs = slowNow;
                        Log.w(TAG, "输出 swap 耗时 " + swapMs + "ms，节流间隔调至 "
                                + o.minIntervalMs + "ms key=" + o.target.key);
                    }
                } else if (swapMs < 12L && o.minIntervalMs > 0L) {
                    // v1.69：允许回落到 0（原实现硬地板 33ms，一旦被拉上去就永远回不到满帧）
                    o.minIntervalMs = Math.max(0L, o.minIntervalMs - 16L);
                }
                // v1.64b：下游明显跟不上时，连续两次慢 swap 就先停一拍（丢帧），
                // 让 BufferQueue 有机会把积压的帧消费掉，画面停一拍也好过一路拖旧
                if (swapMs > SLOW_SWAP_MS && viewOutputCount() > 1) {
                    o.slowStreak++;
                    if (o.slowStreak >= 2) {
                        o.slowStreak = 0;
                        o.minIntervalMs = Math.min(120L, Math.max(33L, o.minIntervalMs * 2));
                    }
                } else {
                    o.slowStreak = 0;
                }
            } catch (Throwable t) {
                Log.w(TAG, "draw 失败 key=" + o.target.key, t);
            } finally {
                o.busy = false;
            }
        }
    }

    // ---------------- 输出表维护（输入线程） ----------------

    private void applyOutputs(ArrayList<Target> targets) {
        if (!alive) {
            pendingOutputs = new ArrayList<>(targets);
            return;
        }
        // 1) 摘除已不在列表中的输出
        ArrayList<String> drop = new ArrayList<>();
        for (Map.Entry<String, Output> e : outputs.entrySet()) {
            Output o = e.getValue();
            boolean keep = false;
            for (Target t : targets) {
                if (t.identity().equals(o.identity)) {
                    keep = true;
                    break;
                }
            }
            if (!keep) drop.add(e.getKey());
        }
        for (String key : drop) {
            Output o = outputs.remove(key);
            if (o != null) destroyOutput(o);
        }
        liveCount = outputs.size();
        // 2) 新增 / 重建发生变化的输出
        for (Target t : targets) {
            Output o = outputs.get(t.key);
            String id = t.identity();
            boolean sameTarget = (o != null && id.equals(o.identity));
            if (sameTarget && !o.dead && o.eglSurface != EGL14.EGL_NO_SURFACE) {
                // v1.67：纹理没变，只同步软可见性（identity 不含 paused，必须在这里刷进来）
                o.paused = t.paused;
                continue;
            }
            // v1.65：复活计数只统计「同一块纹理反复被判死」这一种异常。
            // 纹理本身换了（窗口重建 / 上层自愈换纹理）属于正常重建，计数必须清零。
            // 旧实现无条件累加、满 4 次后直接 continue，把该路从输出表里永久剔除，
            // 表现就是「打灯两次之后主窗永远停在那一帧」——这是本次的核心根因。
            int revived = 0;
            if (sameTarget) {
                Integer v = reviveCount.get(t.key);
                revived = (v == null ? 0 : v.intValue()) + 1;
            }
            if (o != null) {
                outputs.remove(t.key);
                destroyOutput(o);
            }
            if (revived >= REVIVE_NOTIFY_MAX) {
                // 同一块纹理连坏多次：唯一出路是上层换掉这块纹理，通知它，但绝不再跳过创建。
                Log.w(TAG, "输出 " + t.key + " 同一纹理连续重建 " + revived + " 次仍异常，请求上层换纹理");
                notifyStall(t.key);
                revived = 0;
            }
            reviveCount.put(t.key, revived);
            Output created = new Output(t, id);
            created.paused = t.paused;
            if (createOutput(created)) {
                outputs.put(t.key, created);
                liveCount = outputs.size();
                Log.i(TAG, "输出已就绪 key=" + t.key + " " + created.width + "x" + created.height
                        + " 线程=" + (created.thread == null ? "-" : created.thread.getName())
                        + " 输出表=" + outputs.size());
            } else {
                Log.w(TAG, "输出创建失败 key=" + t.key);
            }
        }
        if (handler != null && alive && !handler.hasCallbacks(watchdog)) {
            // v1.67 beta2：旧实现每次输出表变动都 remove + post，把巡检一直往后推。
            // 打灯 / 显隐切换会高频调用 refreshRelayOutputs，watchdog 因此可能长期轮不到执行，
            // 症状正是「某一路冻结在最后一帧，既不判死也不自愈，只有重开总开关才恢复」。
            // 改为只在没有排队时补一次，绝不再重置已有计时。
            handler.postDelayed(watchdog, WATCH_MS);
        }
    }

    /** 为一路输出创建线程 + 共享上下文 + EGLSurface + program。 */
    private boolean createOutput(final Output o) {
        o.thread = new HandlerThread("RelayOut-" + o.target.key);
        o.thread.start();
        o.handler = new Handler(o.thread.getLooper());
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] okFlag = new boolean[]{false};
        final Runnable init = new Runnable() {
            @Override
            public void run() {
                try {
                    okFlag[0] = initOutputGl(o);
                } catch (Throwable t) {
                    Log.w(TAG, "initOutputGl 异常 key=" + o.target.key, t);
                } finally {
                    latch.countDown();
                }
            }
        };
        o.handler.post(init);
        try {
            latch.await(1200L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        if (!okFlag[0]) {
            destroyOutput(o);
            return false;
        }
        return true;
    }

    private boolean initOutputGl(Output o) {
        int[] ctxAttrib = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        o.ctx = EGL14.eglCreateContext(eglDisplay, eglConfig, eglContext, ctxAttrib, 0);
        if (o.ctx == null || o.ctx == EGL14.EGL_NO_CONTEXT) {
            Log.w(TAG, "共享上下文创建失败 key=" + o.target.key + "，该路改用独立上下文");
            o.ctx = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                    ctxAttrib, 0);
            if (o.ctx == null || o.ctx == EGL14.EGL_NO_CONTEXT) return false;
            // 独立上下文看不到输入纹理，无法出画面
            Log.w(TAG, "独立上下文无法共享输入纹理，放弃该路 key=" + o.target.key);
            return false;
        }
        Surface win;
        if (o.target.texture != null) {
            // 固定输出 buffer 尺寸，保证与直连相机时 720p buffer 一致，
            // View 层 applySlotTransform / applyRenderTransform 的缩放平移参数继续有效
            o.target.texture.setDefaultBufferSize(bufW, bufH);
            o.ownedSurface = new Surface(o.target.texture);
            if (!o.ownedSurface.isValid()) {
                o.ownedSurface = null;
                return false;
            }
            win = o.ownedSurface;
        } else {
            if (o.target.surface == null || !o.target.surface.isValid()) return false;
            win = o.target.surface;
        }
        int[] attrib = {EGL14.EGL_NONE};
        o.eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, win, attrib, 0);
        if (o.eglSurface == null || o.eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "eglCreateWindowSurface 失败 key=" + o.target.key);
            o.eglSurface = EGL14.EGL_NO_SURFACE;
            if (o.ownedSurface != null) {
                try {
                    o.ownedSurface.release();
                } catch (Throwable ignored) {
                }
                o.ownedSurface = null;
            }
            return false;
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, o.eglSurface, o.eglSurface, o.ctx)) {
            Log.w(TAG, "输出上下文 makeCurrent 失败 key=" + o.target.key);
            return false;
        }
        int[] w = new int[1];
        int[] h = new int[1];
        EGL14.eglQuerySurface(eglDisplay, o.eglSurface, EGL14.EGL_WIDTH, w, 0);
        EGL14.eglQuerySurface(eglDisplay, o.eglSurface, EGL14.EGL_HEIGHT, h, 0);
        o.width = w[0] > 0 ? w[0] : bufW;
        o.height = h[0] > 0 ? h[0] : bufH;
        return initProgram(o);
    }

    /** 在指定输出上下文中编译一份 program（program 属于各自的上下文）。 */
    private boolean initProgram(Output o) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        if (vs == 0 || fs == 0) return false;
        int prog = GLES20.glCreateProgram();
        if (prog == 0) return false;
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        int[] status = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "program link 失败: " + GLES20.glGetProgramInfoLog(prog));
            GLES20.glDeleteProgram(prog);
            return false;
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        o.program = prog;
        o.aPosition = GLES20.glGetAttribLocation(prog, "aPosition");
        o.aTexCoord = GLES20.glGetAttribLocation(prog, "aTexCoord");
        o.uTexMatrix = GLES20.glGetUniformLocation(prog, "uTexMatrix");
        o.uTexture = GLES20.glGetUniformLocation(prog, "uTexture");
        return true;
    }

    private int compileShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        if (shader == 0) return 0;
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "shader 编译失败: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    /** 销毁一路输出。若该路线程可能已阻塞，则不等待、不销毁其 EGL（避免二次卡住）。 */
    private void destroyOutput(final Output o) {
        if (o == null) return;
        o.dead = true;
        lastSwapSnapshot.remove(o.target.key);
        if (!selfHealV2) {
            // 旧路径（v1.66）：busy 时直接丢引用，什么都不做——会漏线程与 EGL
            if (o.handler == null || o.busy) return;
        } else {
            // v1.67：统一走僵尸表——先 release 承载 Surface 解套，再等线程退出后回收 EGL
            discardOutput(o);
            return;
        }
        o.handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    releaseOutputGl(o);
                } finally {
                    if (o.thread != null) {
                        try {
                            o.thread.quitSafely();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        });
    }

    private void releaseOutputGl(Output o) {
        try {
            if (o.eglSurface != null && o.eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(eglDisplay, o.eglSurface);
                o.eglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (o.ownedSurface != null) {
                o.ownedSurface.release();
                o.ownedSurface = null;
            }
            if (o.ctx != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, o.ctx);
                o.ctx = EGL14.EGL_NO_CONTEXT;
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- v1.67 僵尸输出表：延迟回收 ----------------

    /**
     * 丢弃一路输出——所有「异常摘表」与「正常销毁」的唯一出口。
     *
     * <p>为什么顺序不能换：卡在 eglSwapBuffers 里的线程，只要它写向的 BufferQueue
     * 还有效，就永远等不到消费端重绘。而 Surface.release() 会立即断开这条 producer
     * 链路，swap 立刻返回错误，线程随即跑完 finally 自行退出。
     * 所以必须「先 release 承载 Surface 解套 → 等线程退出 → 最后才碰 EGL」。
     * 线程没退出之前绝不许 eglDestroySurface/eglDestroyContext（那会二次卡死甚至崩溃），
     * 这正是 v1.66 一直在漏资源、越打灯越黑的根本原因。</p>
     */
    private void discardOutput(final Output o) {
        if (o == null) return;
        o.dead = true;
        lastSwapSnapshot.remove(o.target.key);
        boolean released = false;
        if (o.ownedSurface != null) {
            try {
                o.ownedSurface.release();
                released = true;
            } catch (Throwable ignored) {
            }
            o.ownedSurface = null;
        }
        if (o.thread != null) {
            try {
                o.thread.quitSafely();
            } catch (Throwable ignored) {
            }
        }
        boolean threadDead = (o.thread == null || !o.thread.isAlive());
        if (threadDead && !o.busy) {
            // 线程本来就没卡：当场在本线程回收 EGL，不会有竞争者
            destroyEglNow(o);
            return;
        }
        if (!o.counted) {
            o.counted = true;
            leakedOutputs++;
        }
        zombies.add(new Zombie(o, System.currentTimeMillis(), released));
        Log.w(TAG, "输出 " + o.target.key + " 已摘表并进入僵尸队列（承载面已释放=" + released
                + "）、当前僵尸=" + zombies.size() + " 泄漏=" + leakedOutputs);
        if (zombies.size() > ZOMBIE_MAX) {
            needFullRebuild = true;
            Log.w(TAG, "僵尸输出 " + zombies.size() + " 路，超过上限 " + ZOMBIE_MAX + "，请求整实例重建");
        }
    }

    /** 在本线程销毁某路的 EGLSurface/Context。调用前必须确认该路输出线程已退出。 */
    private void destroyEglNow(Output o) {
        if (o == null) return;
        try {
            if (o.eglSurface != null && o.eglSurface != EGL14.EGL_NO_SURFACE
                    && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglDestroySurface(eglDisplay, o.eglSurface);
            }
        } catch (Throwable ignored) {
        }
        o.eglSurface = EGL14.EGL_NO_SURFACE;
        try {
            if (o.ctx != null && o.ctx != EGL14.EGL_NO_CONTEXT
                    && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglDestroyContext(eglDisplay, o.ctx);
            }
        } catch (Throwable ignored) {
        }
        o.ctx = EGL14.EGL_NO_CONTEXT;
        if (o.ownedSurface != null) {
            try {
                o.ownedSurface.release();
            } catch (Throwable ignored) {
            }
            o.ownedSurface = null;
        }
        if (o.counted) {
            o.counted = false;
            leakedOutputs = Math.max(0, leakedOutputs - 1);
        }
    }

    /**
     * 僵尸收尸：线程退出后才回收 EGL。
     * 三态自适应：release 后线程退出 → 判定该驱动支持解套（正常路径）；
     * 连续 3 次 release 后线程仍存活 → 判定不支持解套，之后遇到卡死直接整实例重建。
     */
    private void reapZombies(long now) {
        if (zombies.isEmpty()) return;
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || !alive) {
            // 实例已废弃：EGL 侧不可再操作，直接放弃引用（不触碰，避免二次崩）
            for (Zombie z : zombies) {
                if (z.o.counted) {
                    z.o.counted = false;
                    leakedOutputs = Math.max(0, leakedOutputs - 1);
                }
            }
            zombies.clear();
            return;
        }
        for (int i = zombies.size() - 1; i >= 0; i--) {
            Zombie z = zombies.get(i);
            boolean threadDead = (z.o.thread == null || !z.o.thread.isAlive());
            if (threadDead) {
                destroyEglNow(z.o);
                if (z.surfaceReleased) surfaceReleaseWorks = true;
                zombies.remove(i);
                Log.i(TAG, "僵尸输出已回收 key=" + z.o.target.key
                        + " 存活 " + (now - z.sinceMs) + "ms，剩余僵尸=" + zombies.size());
                continue;
            }
            long age = now - z.sinceMs;
            if (z.surfaceReleased && age > 1500L && !z.ineffectiveCounted) {
                z.ineffectiveCounted = true;
                releaseIneffective++;
                Log.w(TAG, "release 承载 Surface 后输出线程仍未退出 key=" + z.o.target.key
                        + "（累计 " + releaseIneffective + " 次）");
                if (releaseIneffective >= 3) {
                    surfaceReleaseWorks = false;
                    needFullRebuild = true;
                    Log.w(TAG, "连续 3 次 release 解套无效，判定该驱动不支持此路径，转整实例重建");
                }
            }
            if (age > ZOMBIE_TTL_MS) {
                // 到期仍不退出：放弃这路（绝不碰它的 EGL），请求整实例重建兜底
                if (z.o.counted) {
                    z.o.counted = false;
                    leakedOutputs = Math.max(0, leakedOutputs - 1);
                }
                zombies.remove(i);
                needFullRebuild = true;
                Log.w(TAG, "僵尸输出超过 " + ZOMBIE_TTL_MS + "ms 仍未退出，放弃 key="
                        + z.o.target.key + "，请求整实例重建");
            }
        }
    }

    /** 当前已泄漏（异常摘表但线程未退出）的输出路数。 */
    public int getLeakedOutputs() {
        return leakedOutputs;
    }

    /** 是否有待处理的整实例重建请求。 */
    public boolean needsFullRebuild() {
        return needFullRebuild;
    }

    /** 取一次整实例重建请求（取完即清）。 */
    public boolean consumeFullRebuildRequest() {
        boolean b = needFullRebuild;
        needFullRebuild = false;
        return b;
    }

    /**
     * v1.69：真正「在屏幕上出画」的窗口路数——只数 P/S/EDIT_P/EDIT_S，不含 ICL 编码输入面。
     *
     * <p>节流门限只能用这个：ICL 是持续被消费的编码输入面，它一直在表里，一旦把它算进
'
     * 门限，只要开了投屏的单窗工况就会恒等于 2，节流从「多路并存才启用」变成「常开」。
'
     * </p>
     */
    public int viewOutputCount() {
        int n = 0;
        try {
            for (Output o : outputs.values()) {
                if (o.dead || o.paused) continue;
                String k = o.target.key;
                if (k == null) continue;
                if (k.startsWith("EDIT") || "P".equals(k) || "S".equals(k)) n++;
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** 当前真正在出帧的输出路数（可见性暂停中的不计）。 */
    public int outputCountActive() {
        int n = 0;
        try {
            for (Output o : outputs.values()) {
                if (!o.dead && !o.paused) n++;
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** release 承载 Surface 是否已被验证可解套（供上层诊断）。 */
    public boolean isSurfaceReleaseEffective() {
        return surfaceReleaseWorks;
    }

    // ---------------- 看门狗 ----------------

    private long lastDumpMs = 0L;

    /** v1.67 beta2：每 5 秒落一条逐路状态快照——卡死时能直接看出是哪一路、为什么没判死。 */
    private void dumpOutputStatus(long now) {
        if (now - lastDumpMs < 5000L) return;
        lastDumpMs = now;
        StringBuilder sb = new StringBuilder();
        sb.append("输出状态 表=").append(outputs.size())
                .append(" 活=").append(outputCountActive())
                .append(" 僵尸=").append(zombies.size())
                .append(" 泄漏=").append(leakedOutputs)
                .append(" 帧=").append(frameCount);
        for (Output o : outputs.values()) {
            sb.append(" || ").append(o.target.key)
                    .append(" busy=").append(o.busy ? (now - o.busySinceMs) + "ms" : "-")
                    .append(" 暂停=").append(o.paused ? 1 : 0)
                    .append(" 死=").append(o.dead ? 1 : 0)
                    .append(" 末次swap=").append(o.lastSwapMs > 0L ? (now - o.lastSwapMs) + "ms前" : "从未")
                    .append(" 本次=").append(o.swapCostMs).append("ms")
                    .append(" 峰值=").append(o.swapMaxMs).append("ms")
                    .append(" 超700=").append(o.swapOver700)
                    .append(" 判死=").append(o.stallCount)
                    .append(" 救=").append(o.recoverCount);
        }
        Log.i(TAG, sb.toString());
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!alive) return;
            try {
                long nowMs = System.currentTimeMillis();
                reapZombies(nowMs);
                checkOutputStalls();
                dumpOutputStatus(nowMs);
            } catch (Throwable t) {
                Log.w(TAG, "看门狗异常", t);
            }
            if (handler != null && alive) {
                handler.postDelayed(this, WATCH_MS);
            }
        }
    };

    /**
     * 逐路检查「占用时长」：某路 busy 超过 STALL_MS 说明它阻塞在 swap 里。
     * 隔离该路并回调，其余路与相机输入完全不受影响。
     */
    private void checkOutputStalls() {
        long now = System.currentTimeMillis();
        // v1.65：输入线程自己就没新帧（相机会话正在重建 / 相机没在出帧）时，
        // 所有输出「没 swap」都是正常的，绝不能据此把各路全判死换纹理。
        boolean inputAlive = (frameCount != lastStallCheckFrame);
        lastStallCheckFrame = frameCount;
        ArrayList<String> stalled = null;
        for (Output o : outputs.values()) {
            if (o.dead) continue;
            // v1.67：可见性暂停中的输出本来就不该 swap，绝不能算「静默死」
            if (o.paused) {
                o.silentStreak = 0;
                continue;
            }
            long held = o.busy ? (now - o.busySinceMs) : 0L;
            boolean hung = o.busy && held >= STALL_MS;
            if (!hung && inputAlive) {
                // v1.65：静默死 —— 线程没卡在 swap（busy=false），但很久没成功 swap 过，
                // 说明这一路出图链路已经断了（纹理失效 / 下游队列不再消费），
                // 上层必须换纹理才能救回，否则画面永久停在最后一帧。
                // v1.65b：分两种宽限，且必须连续 2 轮巡检都静默才判死，避免会话重建 /
                // 窗口切换瞬间被误杀（误杀的代价是画面换纹理重来一遍）。
                long anchor = o.lastSwapMs > 0L ? o.lastSwapMs : o.createdMs;
                long limit = o.lastSwapMs > 0L ? SILENT_STALL_MS : NEW_OUTPUT_STALL_GRACE_MS;
                long idle = now - anchor;
                if (anchor > 0L && idle >= limit) {
                    o.silentStreak++;
                    if (o.silentStreak >= 2) {
                        hung = true;
                        held = idle;
                    }
                } else {
                    o.silentStreak = 0;
                }
            }
            if (!hung) continue;
            o.dead = true;
            o.stallCount++;
            o.recoverCount++;
            Log.w(TAG, "输出 " + o.target.key + " 阻塞/静默 " + held + "ms，已隔离该路（其他输出继续）"
                    + " 本路判死=" + o.stallCount + " 本次swap=" + o.swapCostMs
                    + "ms 峰值=" + o.swapMaxMs + "ms 超700=" + o.swapOver700);
            if (stalled == null) stalled = new ArrayList<>();
            stalled.add(o.target.key);
        }
        if (stalled == null) return;
        for (String key : stalled) {
            Output o = outputs.remove(key);
            // v1.67 关键修复：旧实现只把 busy 抹成 false 就把对象丢掉——线程、EGLSurface、
            // EGLContext 全部永久留在进程里。每判死一次漏一整套，累积后 EGL 配额耗尽，
            // 所有窗口一起黑且无法自愈。现在改为统一走 discardOutput 真正回收。
            if (o != null) {
                discardOutput(o);
            } else {
                liveCount = outputs.size();
            }
            notifyStall(key);
        }
        liveCount = outputs.size();
    }

    private void notifyStall(String key) {
        OutputStallListener l = stallListener;
        if (l == null) return;
        try {
            l.onOutputStalled(key);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 主线程释放 ----------------

    private void releaseGl() {
        for (Output o : new ArrayList<>(outputs.values())) {
            destroyOutput(o);
        }
        outputs.clear();
        liveCount = 0;
        reviveCount.clear();
        // v1.67：先给僵尸表一轮回收机会（线程已退出的当场 eglDestroy）
        reapZombies(System.currentTimeMillis());
        // 仍有僵尸存活：它们的 EGLSurface 无法单独安全销毁（线程还在里面），
        // 只能用 eglTerminate 连根释放整个 display。宁可重建一次 display，
        // 也不能把 EGL 配额漏死——配额漏死的结局是所有窗口一起黑。
        final boolean forceTerminate = !zombies.isEmpty();
        if (inputSurface != null) {
            try {
                inputSurface.release();
            } catch (Throwable ignored) {
            }
            inputSurface = null;
        }
        if (inputTexture != null) {
            try {
                inputTexture.setOnFrameAvailableListener(null);
                inputTexture.release();
            } catch (Throwable ignored) {
            }
            inputTexture = null;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglIdle != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglIdle);
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                }
                // v1.67：不再无条件 terminate。EGL_DEFAULT_DISPLAY 是进程级共享的，
                // terminate 会连带打掉同进程 hwui 与其他中转实例的 EGL 状态
                // （表现为重启后界面短暂卡顿/黑屏，且新实例更容易 createWindowSurface 失败）。
                // 只有 display 已被证实损坏（initGl 失败过）时才显式重置。
                if (displayPoisoned || forceTerminate) {
                    EGL14.eglTerminate(eglDisplay);
                    displayPoisoned = false;
                    if (forceTerminate) {
                        Log.w(TAG, "存在未退出僵尸输出，已 eglTerminate 连根释放 display");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        eglIdle = EGL14.EGL_NO_SURFACE;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglConfig = null;
        alive = false;
    }
}
