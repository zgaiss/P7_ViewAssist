package com.xiaopeng.qa.camera;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;

/**
 * v1.57 beta3：仪表 H.264 投影模块（ICLinkProjector）。
 *
 * 车机侧把浮窗当前影像（Camera2 预览会话中追加的编码器 input Surface）经
 * MediaCodec H.264 硬编 + TCP 推流到仪表侧（仪表 172.20.1.40:9000，仪表侧独立解码写屏）。
 *
 * 分帧协议：4 字节大端长度头 + H.264 Annex-B ES（MediaCodec 输出即 Annex-B，
 * 直接切帧加长度头发送）。
 *
 * 容错红线：编码器创建失败 / socket 连接失败 / 推流中 IO 异常，一律 try-catch
 * 静默降级（仅记录日志），绝不 crash、绝不阻塞或影响浮窗主流程。
 */
public class ICLinkProjector {

    private static final String TAG = "ICLinkProjector";

    // ---- 编码参数（与仪表侧联调后可能调整）----
    private static final int ENC_WIDTH = 1280;
    private static final int ENC_HEIGHT = 720;
    private static final int ENC_BIT_RATE = 2_000_000;      // 2 Mbps
    private static final int ENC_FRAME_RATE = 15;
    private static final int ENC_I_FRAME_INTERVAL = 1;      // 1 秒一个 IDR 关键帧
    private static final int DEQUEUE_TIMEOUT_US = 10_000;

    // ---- 帧通道参数（待仪表侧锁定帧通道后对齐）----
    public static String DEFAULT_HOST = "172.20.1.40";
    public static int DEFAULT_PORT = 9000;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    /** 发送队列容量：防背压（队列满丢最旧帧保最新），不阻塞编码线程。 */
    private static final int QUEUE_CAPACITY = 8;
    private static final long SEND_POLL_MS = 200L;

    /** The owner must ban new producers before acknowledging real producer detachment. */
    public interface ProducerReleaseGate {
        void retire(Surface surface, Runnable producersDetached);
    }

    private final Object lock = new Object();
    private final ProducerReleaseGate releaseGate;
    private final Runnable onReady;
    private Session current;

    private static final class Session {
        final EncoderRetirement retirement;
        final AtomicBoolean running = new AtomicBoolean(false);
        final CountDownLatch detached = new CountDownLatch(1);
        final ArrayBlockingQueue<byte[]> frameQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        MediaCodec codec;
        Surface inputSurface;
        Socket socket;
        OutputStream out;
        Thread initThread;
        Thread pushThread;
        Thread sendThread;
        Session(EncoderRetirement retirement) { this.retirement = retirement; }
    }

    public ICLinkProjector(ProducerReleaseGate releaseGate, Runnable onReady) {
        this.releaseGate = releaseGate;
        this.onReady = onReady;
    }

    public ICLinkProjector(ProducerReleaseGate releaseGate) { this(releaseGate, null); }

    public static boolean areAllResourcesReleased() { return EncoderRetirement.isFree(); }

    /** Without an owner handshake, published surfaces are deliberately retained on stop. */
    public ICLinkProjector() { this(null); }

    public Surface getInputSurface() {
        synchronized (lock) {
            if (current == null || !current.running.get() || !current.retirement.publish()) return null;
            return current.inputSurface;
        }
    }

    public boolean isRunning() {
        synchronized (lock) { return current != null && current.running.get(); }
    }

    /** One process-admitted init worker; native driver calls never hold the status lock. */
    public void start(String host, int port) {
        Session failed = null;
        synchronized (lock) {
            if (current != null) return;
            EncoderRetirement admission = EncoderRetirement.acquire();
            if (admission == null) return;
            Session run = new Session(admission);
            admission.retainOwner(run);
            current = run;
            try {
                run.initThread = new Thread(() -> initialize(run, host, port), "icl-init");
                run.initThread.start();
            } catch (Throwable t) {
                failed = run;
            }
        }
        if (failed != null) releaseInternal(failed);
    }

    private void initialize(Session run, String host, int port) {
        boolean ready = false;
        try {
            // Record each acquisition immediately so partial initialization cannot leak it.
            run.codec = MediaCodec.createEncoderByType("video/avc");
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", ENC_WIDTH, ENC_HEIGHT);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, ENC_BIT_RATE);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, ENC_FRAME_RATE);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, ENC_I_FRAME_INTERVAL);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            run.codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            run.inputSurface = run.codec.createInputSurface();
            run.codec.start();
            run.socket = new Socket();
            synchronized (lock) {
                if (!run.retirement.initialized()) return; // Stop won; never publish late init.
                run.running.set(true);
                run.pushThread = new Thread(() -> {
                    try { pushLoop(run); } finally { releaseInternal(run); }
                }, "icl-push");
                run.sendThread = new Thread(() -> {
                    try { sendLoop(run, host, port); } finally { releaseInternal(run); }
                }, "icl-send");
                run.pushThread.start();
                run.sendThread.start();
                ready = true;
            }
            Log.i(TAG, "ICLinkProjector started: " + host + ":" + port
                    + " enc=" + ENC_WIDTH + "x" + ENC_HEIGHT + "@" + ENC_FRAME_RATE + "fps");
            // Notification only; receiver must re-read current state on its own actor.
            if (onReady != null && run.running.get()) onReady.run();
        } catch (Throwable t) {
            ready = false;
            Log.w(TAG, "start failed, silent fallback: " + t.getMessage());
        } finally {
            if (!ready) releaseInternal(run);
        }
    }

    /** Asynchronous stop. No UI/camera actor joins, and no timeout masquerades as detachment. */
    public void stop() {
        Session run;
        synchronized (lock) { run = current; }
        if (run != null) releaseInternal(run);
    }

    /** 编码输出循环：dequeue 编码帧，SPS/PPS 缓存并在关键帧前补发，帧数据入队（防背压）。 */
    private void pushLoop(Session run) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] config = null;
        while (run.running.get()) {
            try {
                int idx = run.codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (idx < 0) {
                    continue;
                }
                boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                byte[] data = new byte[info.size];
                run.codec.getOutputBuffer(idx).get(data, 0, info.size);
                run.codec.releaseOutputBuffer(idx, false);
                if (isConfig) {
                    // SPS/PPS：缓存，待下一个关键帧前补发（解码器必须先收到 SPS/PPS 才能解 IDR）
                    config = data;
                    continue;
                }
                if (isKey && config != null) {
                    enqueue(run, config);
                }
                enqueue(run, data);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            } catch (Throwable t) {
                Log.w(TAG, "push loop error, silent: " + t.getMessage());
                break;
            }
        }
    }

    /** 入队：队列满则丢最旧帧保最新（防背压，发送慢时不阻塞编码线程）。 */
    private void enqueue(Session run, byte[] data) {
        if (!run.frameQueue.offer(data)) {
            run.frameQueue.poll();
            run.frameQueue.offer(data);
        }
    }

    /** 发送循环：后台连接仪表，随后取帧封装 4 字节大端长度头 + Annex-B ES 写 socket。 */
    private void sendLoop(Session run, String host, int port) {
        try {
            run.socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            run.socket.setTcpNoDelay(true);
            run.out = run.socket.getOutputStream();
            Log.i(TAG, "ICLinkProjector connected: " + host + ":" + port);
        } catch (Throwable t) {
            // 连接失败：由 finally 统一退休，等待 producer 真正脱离后释放编码器
            Log.w(TAG, "connect failed, projection drops: " + t.getMessage());
            return;
        }
        byte[] head = new byte[4];
        while (run.running.get()) {
            try {
                byte[] frame = run.frameQueue.poll(SEND_POLL_MS, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    continue;
                }
                int len = frame.length;
                head[0] = (byte) ((len >>> 24) & 0xFF);
                head[1] = (byte) ((len >>> 16) & 0xFF);
                head[2] = (byte) ((len >>> 8) & 0xFF);
                head[3] = (byte) (len & 0xFF);
                run.out.write(head);
                run.out.write(frame);
                run.out.flush();
            } catch (Throwable t) {
                // 推流中 IO 异常：静默降级
                Log.w(TAG, "send loop error, silent: " + t.getMessage());
                break;
            }
        }
    }

    /** All callers, including failed initialization and worker errors, use this barrier. */
    private void releaseInternal(Session run) {
        synchronized (lock) {
            if (!run.retirement.retire()) return;
            run.running.set(false);
            run.frameQueue.clear();
        }
        Runnable detached = () -> {
            run.retirement.producerDetached();
            run.detached.countDown();
        };
        if (!run.retirement.needsProducerBarrier()) {
            detached.run();
        } else if (releaseGate != null) {
            try { releaseGate.retire(run.inputSurface, detached); }
            catch (Throwable t) { Log.w(TAG, "producer retirement failed; retaining encoder", t); }
        } else {
            Log.w(TAG, "no producer release gate; retaining published encoder");
        }
        // One process encoder admission also bounds this worker if a driver never returns.
        try {
            Thread cleanup = new Thread(() -> finishRetirement(run), "icl-retire");
            cleanup.start();
        } catch (Throwable t) {
            Log.w(TAG, "retirement worker failed; retaining encoder", t);
        }
    }

    private void finishRetirement(Session run) {
        try {
            // No resource reads/cleanup race a blocked or partially completed initializer.
            if (run.initThread != null) run.initThread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (run.socket != null) run.socket.close();
        } catch (Throwable t) {
            Log.w(TAG, "socket close failed", t);
        }
        try {
            run.detached.await();
            // Worker exit, not a timed join, protects codec from in-flight dequeue calls.
            if (run.pushThread != null) run.pushThread.join();
            if (run.sendThread != null) run.sendThread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "retirement interrupted; retaining encoder");
            return;
        }
        if (!run.retirement.claimCleanup(true, true)) return;
        boolean released = true;
        if (run.codec != null) {
            try { run.codec.stop(); } catch (Throwable ignored) { /* May not have started. */ }
            try { run.codec.release(); }
            catch (Throwable t) { released = false; Log.w(TAG, "codec release uncertain", t); }
        }
        if (run.inputSurface != null) {
            try { run.inputSurface.release(); }
            catch (Throwable t) { released = false; Log.w(TAG, "surface release uncertain", t); }
        }
        if (!released) return; // Quarantined: never silently allow replacement allocation.
        run.frameQueue.clear();
        synchronized (lock) {
            if (current == run) current = null;
            run.retirement.nativeReleasedByCurrentWorker();
        }
        Log.i(TAG, "ICLinkProjector stopped after producer and worker retirement");
    }
}
