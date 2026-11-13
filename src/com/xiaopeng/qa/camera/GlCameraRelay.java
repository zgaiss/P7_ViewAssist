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
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
/**
* Camera input with independently owned output workers. Resource retirement is asynchronous,
* process bounded, and never terminates a shared EGL display. Legacy shared OES sampling is
* retained: cross-context GPU synchronization remains an explicitly unvalidated limitation.
*/
public class GlCameraRelay {
    private static final String TAG="CameraRelay";
    private static final long STALL_MS=700L, SILENT_MS=1200L, INITIAL_GRACE_MS=4500L;
    private static final RelayLifecycle RESOURCES=new RelayLifecycle();
    private static final Object TARGET_LOCK=new Object();
    private static final List<WeakReference<Object>> BLOCKED=new ArrayList<WeakReference<Object>>();
    private static final IdentityHashMap<Object,PendingTarget> PENDING=new IdentityHashMap<Object,PendingTarget>();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ScheduledExecutorService REAPER=Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t=new Thread(r,"RelayResourceReaper");
            t.setDaemon(true);
            return t;
        }
    });
    static {
        REAPER.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                try {
                    reapProcess();
                }
                catch (Throwable t) {
                    Log.e(TAG,"resource bookkeeping",t);
                }
            }
        },250,250,TimeUnit.MILLISECONDS);
    }
    /** Compatibility only: disabling lifecycle accounting is no longer supported. */
    public static volatile boolean selfHealV2=true;
    public interface OutputStallListener {
        void onOutputStalled(String key,Object targetIdentity);
    }
    public interface OutputUnavailableListener {
        void onOutputUnavailable(String key, Object targetIdentity, String reason);
    }
    public static final class Target {
        public final String key;
        public final SurfaceTexture texture;
        public final Surface surface;
        public volatile boolean paused;
        private Target(String key,SurfaceTexture texture,Surface surface) {
            this.key=key;
            this.texture=texture;
            this.surface=surface;
        }
        public static Target ofTexture(String key,SurfaceTexture st) {
            return st==null?null:new Target(key,st,null);
        }
        public static Target ofSurface(String key,Surface s) {
            return s==null?null:new Target(key,null,s);
        }
        Object identity() {
            return texture!=null?texture:surface;
        }
        Target copy() {
            Target t=new Target(key,texture,surface);
            t.paused=paused;
            return t;
        }
    }
    private static final float[] QUAD = new float[] {
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
    private static final class PendingTarget {
        final SurfaceTexture texture;
        boolean scheduled;
        final List<Runnable> callbacks=new ArrayList<Runnable>();
        PendingTarget(SurfaceTexture texture) {
            this.texture=texture;
        }
    }
    private static boolean isBlockedLocked(Object target) {
        boolean found=false;
        for (int i=BLOCKED.size()-1;         i>=0;         i--) {
            Object o=BLOCKED.get(i).get();
            if (o==null)BLOCKED.remove(i);
            else if (o==target)found=true;
        }
        return found;
    }
    /** Called only when TextureView transfers destruction responsibility (return false). */
    public static void releaseTextureWhenUnused(SurfaceTexture st) {
        if (st==null)return;
        blockTarget(st,st,null);
    }
    /** Permanently excludes this exact borrowed surface; callback never precedes producer exit. */
    public static void retireBorrowedSurface(Surface surface,Runnable complete) {
        if (surface==null) {
            if (complete!=null)MAIN.post(complete);
            return;
        }
        blockTarget(surface,null,complete);
    }
    private static void blockTarget(Object target,SurfaceTexture texture,Runnable callback) {
        RelayLifecycle.Token token;
        synchronized (TARGET_LOCK) {
            boolean already=isBlockedLocked(target);
            if (!already)BLOCKED.add(new WeakReference<Object>(target));
            PendingTarget p=PENDING.get(target);
            if (p==null && (!already || callback!=null)) {
                p=new PendingTarget(texture);
                PENDING.put(target,p);
            }
            if (p!=null && callback!=null)p.callbacks.add(callback);
            token=RESOURCES.target(target);
        }
        if (token!=null && token.owner instanceof Output)((Output)token.owner).retire("target destroyed",false);
    }
    public static boolean areAllResourcesReleased() {
        synchronized (TARGET_LOCK) {
            return RESOURCES.empty()&&PENDING.isEmpty();
        }
    }
    private static void reapProcess() {
        for (RelayLifecycle.Token token:RESOURCES.snapshot()) {
            Object owner=token.owner;
            if (owner instanceof Output) {
                Output o=(Output)owner;
                if (o.thread!=null && o.thread.getState()==Thread.State.TERMINATED)token.threadExited=true;
                if (RESOURCES.finish(token)) {
                    o.relay.children.remove(o);
                    o.relay.scheduleMaintenance();
                }
                else if (token.retiring.get() && SystemClock.elapsedRealtime()-o.retireMs>10000L)o.quarantined=true;
            }
            else if (owner instanceof GlCameraRelay) {
                GlCameraRelay r=(GlCameraRelay)owner;
                if (r.thread!=null && r.thread.getState()==Thread.State.TERMINATED)token.threadExited=true;
                if (RESOURCES.finish(token))r.cleaned=true;
                else r.scheduleMaintenance();
            }
        }
        final List<PendingTarget> ready=new ArrayList<PendingTarget>();
        synchronized (TARGET_LOCK) {
            for (Map.Entry<Object,PendingTarget> e:new ArrayList<Map.Entry<Object,PendingTarget>>(PENDING.entrySet())) {
                if (RESOURCES.target(e.getKey())==null&&!e.getValue().scheduled) {
                    e.getValue().scheduled=true;
                    ready.add(e.getValue());
                }
            }
        }
        // The bookkeeping thread never calls native cleanup or arbitrary consumer code.
        for (final PendingTarget p:ready) {
            boolean accepted=MAIN.post(new Runnable() {
                public void run() {
                    try {
                        if (p.texture!=null)p.texture.release();
                        List<Runnable> callbacks;
                        synchronized (TARGET_LOCK) {
                            callbacks=new ArrayList<Runnable>(p.callbacks);
                            for (Map.Entry<Object,PendingTarget> e:new ArrayList<Map.Entry<Object,PendingTarget>>(PENDING.entrySet()))if (e.getValue()==p)PENDING.remove(e.getKey());
                        }
                        for (Runnable callback:callbacks) {
                            try {
                                callback.run();
                            }
                            catch (Throwable failure) {
                                Log.e(TAG,"producer detached callback failed; consumer retains ownership",failure);
                            }
                        }
                    }
                    catch (Throwable t) {
                        Log.e(TAG,"target release remains pending",t);
                    }
                }
            });
            if (!accepted) {
                synchronized (TARGET_LOCK) {
                    p.scheduled=false;
                }
                Log.e(TAG,"main rejected target cleanup; retained for retry");
            }
        }
    }
    private static final class Output {
        final GlCameraRelay relay;
        final Target target;
        final RelayLifecycle.Token token;
        final EGLDisplay display;
        final EGLContext share;
        final EGLConfig config;
        final FloatBuffer quad=ByteBuffer.allocateDirect(QUAD.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        final AtomicBoolean cleanupQueued=new AtomicBoolean(),busy=new AtomicBoolean();
        volatile HandlerThread thread;
        volatile Handler handler;
        volatile boolean active,paused,quarantined,failed;
        volatile long busySince,retireMs,created=SystemClock.elapsedRealtime();
        volatile RelayLifecycle.Pacing pacing=new RelayLifecycle.Pacing(0,0);
        volatile RelayLifecycle.Stats stats=new RelayLifecycle.Stats(0,0,0,0,0);
        volatile int program;
        volatile boolean glObjectsClean;
        EGLContext context=EGL14.EGL_NO_CONTEXT;
        EGLSurface window=EGL14.EGL_NO_SURFACE,cleanupSurface=EGL14.EGL_NO_SURFACE;
        Surface ownedSurface;
        int width,height,aPosition,aTexCoord,uTexMatrix,uTexture;
        int failures;
        Output(GlCameraRelay relay,Target target,RelayLifecycle.Token token) {
            this.relay=relay;
            this.target=target;
            this.token=token;
            display=relay.display;
            share=relay.context;
            config=relay.config;
            paused=target.paused;
            quad.put(QUAD).position(0);
            token.owner=this;
        }
        void start() {
            try {
                thread=new HandlerThread("RelayOut-"+target.key+"-"+token.id);
                thread.start();
                handler=new Handler(thread.getLooper());
                if (!handler.post(new Runnable() {
                    public void run() {
                        try {
                            if (!relay.releasing.get() && !token.retiring.get() && initialize() && !relay.releasing.get())active=true;
                            else retire("initialization failed",true);
                        }
                        catch (Throwable t) {
                            Log.e(TAG,"output initialization",t);
                            retire("initialization exception",true);
                        }
                        finally {
                            if (token.retiring.get())queueCleanup();
                            relay.scheduleMaintenance();
                        }
                    }
                }))retire("initialization post rejected",true);
            }
            catch (Throwable t) {
                token.retiring.set(true);
                retireMs=SystemClock.elapsedRealtime();
                // No native work was posted. Thread exit is still separately observed.
                token.contextGone=true;
                token.nativeClean=true;
                if (thread==null || !thread.isAlive())token.threadExited=true;
                if (thread!=null)thread.quitSafely();
                relay.signalFailure(this,"worker start failed");
            }
        }
        boolean initialize() {
            context=EGL14.eglCreateContext(display,config,share,new int[] {
                EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE
            },0);
            if (context==null||context==EGL14.EGL_NO_CONTEXT)return false;
            cleanupSurface=EGL14.eglCreatePbufferSurface(display,config,new int[] {
                EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE
            },0);
            if (cleanupSurface==null||cleanupSurface==EGL14.EGL_NO_SURFACE)return false;
            if (token.retiring.get())return false;
            Surface win=target.surface;
            if (target.texture!=null) {
                target.texture.setDefaultBufferSize(relay.bufW,relay.bufH);
                ownedSurface=new Surface(target.texture);
                win=ownedSurface;
            }
            if (win==null||!win.isValid())return false;
            window=EGL14.eglCreateWindowSurface(display,config,win,new int[] {
                EGL14.EGL_NONE
            },0);
            if (window==null||window==EGL14.EGL_NO_SURFACE)return false;
            if (!EGL14.eglMakeCurrent(display,window,window,context))return false;
            int[] size=new int[1];
            if (!EGL14.eglQuerySurface(display,window,EGL14.EGL_WIDTH,size,0))return false;
            width=size[0];
            if (!EGL14.eglQuerySurface(display,window,EGL14.EGL_HEIGHT,size,0))return false;
            height=size[0];
            program=createProgram();
            if (program==0)return false;
            aPosition=GLES20.glGetAttribLocation(program,"aPosition");
            aTexCoord=GLES20.glGetAttribLocation(program,"aTexCoord");
            uTexMatrix=GLES20.glGetUniformLocation(program,"uTexMatrix");
            uTexture=GLES20.glGetUniformLocation(program,"uTexture");
            return width>0&&height>0&&!token.retiring.get();
        }
        void retire(String reason,boolean failure) {
            if (token.retiring.compareAndSet(false,true)) {
                active=false;
                failed=failure;
                retireMs=SystemClock.elapsedRealtime();
                if (failure)relay.signalFailure(this,reason);
            }
            queueCleanup();
        }
        void queueCleanup() {
            Handler h=handler;
            if (h!=null&&cleanupQueued.compareAndSet(false,true)) {
                if (!h.post(new Runnable() {
                    public void run() {
                        cleanup();
                    }
                }))quarantined=true;
            }
        }
        void cleanup() {
            boolean ok=true;
            try {
                if (context!=null&&context!=EGL14.EGL_NO_CONTEXT) {
                    boolean current=EGL14.eglMakeCurrent(display,cleanupSurface,cleanupSurface,context);
                    if (current) {
                        GLES20.glUseProgram(0);
                        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,0);
                        if (program!=0) {
                            GLES20.glDeleteProgram(program);
                            if (GLES20.glGetError()==GLES20.GL_NO_ERROR)program=0;
                        }
                    }
                    if (!EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT))ok=false;
                }
                if (window!=null&&window!=EGL14.EGL_NO_SURFACE) {
                    if (EGL14.eglDestroySurface(display,window))window=EGL14.EGL_NO_SURFACE;
                    else ok=false;
                }
                if (cleanupSurface!=null&&cleanupSurface!=EGL14.EGL_NO_SURFACE) {
                    if (EGL14.eglDestroySurface(display,cleanupSurface))cleanupSurface=EGL14.EGL_NO_SURFACE;
                    else ok=false;
                }
                if (context!=null&&context!=EGL14.EGL_NO_CONTEXT) {
                    if (EGL14.eglDestroyContext(display,context))context=EGL14.EGL_NO_CONTEXT;
                    else ok=false;
                }
                token.contextGone=context==null||context==EGL14.EGL_NO_CONTEXT;
                if (ownedSurface!=null && token.contextGone && (window==null||window==EGL14.EGL_NO_SURFACE)) {
                    ownedSurface.release();
                    ownedSurface=null;
                }
                glObjectsClean=ok&&token.contextGone&&ownedSurface==null;
                token.nativeClean=glObjectsClean&&program==0;
            }
            catch (Throwable t) {
                Log.e(TAG,"output cleanup remains charged",t);
                quarantined=true;
            }
            finally {
                if (!token.nativeClean)quarantined=true;
                relay.scheduleMaintenance();
                if (thread!=null)thread.quitSafely();
            }
        }
        void draw(final float[] matrix,final long pts) {
            if (!active||token.retiring.get()||paused||!busy.compareAndSet(false,true))return;
            busySince=SystemClock.elapsedRealtime();
            if (!handler.post(new Runnable() {
                public void run() {
                    try {
                        if (!active||token.retiring.get()||relay.releasing.get())return;
                        if (!EGL14.eglMakeCurrent(display,window,window,context)) {
                            int error=EGL14.eglGetError();
                            failed(error,0);
                            return;
                        }
                        GLES20.glViewport(0,0,width,height);
                        GLES20.glClearColor(0,0,0,1);
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                        GLES20.glUseProgram(program);
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,relay.inputTexId);
                        GLES20.glUniform1i(uTexture,0);
                        GLES20.glUniformMatrix4fv(uTexMatrix,1,false,matrix,0);
                        quad.position(0);
                        GLES20.glVertexAttribPointer(aPosition,2,GLES20.GL_FLOAT,false,16,quad);
                        GLES20.glEnableVertexAttribArray(aPosition);
                        quad.position(2);
                        GLES20.glVertexAttribPointer(aTexCoord,2,GLES20.GL_FLOAT,false,16,quad);
                        GLES20.glEnableVertexAttribArray(aTexCoord);
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
                        if (target.surface!=null&&pts>0&&!EGLExt.eglPresentationTimeANDROID(display,window,pts))Log.w(TAG,"presentation timestamp failed: "+EGL14.eglGetError());
                        long start=System.nanoTime();
                        boolean swapped=EGL14.eglSwapBuffers(display,window);
                        int error=swapped?EGL14.EGL_SUCCESS:EGL14.eglGetError();
                        long cost=(System.nanoTime()-start)/1000000L;
                        if (!swapped) {
                            failed(error,cost);
                            return;
                        }
                        if (RelayLifecycle.accepts(token,token.id)) {
                            stats=stats.result(true,SystemClock.elapsedRealtime(),cost,error);
                            pacing=pacing.successfulSwap(cost,relay.viewOutputCount()>1);
                            failures=0;
                        }
                    }
                    catch (Throwable t) {
                        Log.w(TAG,"draw failure",t);
                        failed(EGL14.EGL_BAD_ACCESS,0);
                    }
                    finally {
                        busy.set(false);
                        if (token.retiring.get())queueCleanup();
                    }
                }
            })) {
                busy.set(false);
                retire("draw post rejected",true);
            }
        }
        void failed(int error,long cost) {
            stats=stats.result(false,SystemClock.elapsedRealtime(),cost,error);
            if (error==EGL14.EGL_CONTEXT_LOST) {
                relay.rebuild.set(true);
                // One relay-scoped fault, not a second output-replacement recovery.
                relay.release();
            }
            else if (error==EGL14.EGL_BAD_SURFACE||error==EGL14.EGL_BAD_NATIVE_WINDOW||++failures>=5)retire("EGL error "+error,true);
        }
    }
    public static final class OutputSnapshot {
        public final long generation,lastSuccessElapsedMs,successes,swapCostMs;
        public final int error;
        public final boolean active,paused,retiring;
        OutputSnapshot(Output output) {
            RelayLifecycle.Stats stats=output.stats;
            generation=output.token.id;
            lastSuccessElapsedMs=stats.lastSuccess;
            successes=stats.successes;
            swapCostMs=stats.cost;
            error=stats.error;
            active=output.active;
            paused=output.paused;
            retiring=output.token.retiring.get();
        }
    }
    public static final class RelaySnapshot {
        public final long generation,frameCount,lastFrameElapsedMs;
        public final boolean retiring;
        public final Map<String,OutputSnapshot> outputs;
        RelaySnapshot(long generation,long frameCount,long lastFrameElapsedMs,boolean retiring,Map<String,OutputSnapshot> outputs) {
            this.generation=generation;
            this.frameCount=frameCount;
            this.lastFrameElapsedMs=lastFrameElapsedMs;
            this.retiring=retiring;
            this.outputs=Collections.unmodifiableMap(new LinkedHashMap<String,OutputSnapshot>(outputs));
        }
    }
    private final int bufW,bufH;
    private final RelayLifecycle.Token root;
    private final AtomicBoolean releasing=new AtomicBoolean(),initStarted=new AtomicBoolean(),maintenanceQueued=new AtomicBoolean(),desiredQueued=new AtomicBoolean(),rebuild=new AtomicBoolean();
    private final AtomicReference<List<Target>> desired=new AtomicReference<List<Target>>(Collections.<Target>emptyList());
    private final CopyOnWriteArrayList<Output> children=new CopyOnWriteArrayList<Output>();
    private final Map<String,Object> failedTargets=new LinkedHashMap<String,Object>();
    private final LinkedHashMap<String,Output> outputs=new LinkedHashMap<String,Output>();
    // input owner only
    private volatile Map<String,Output> published=Collections.emptyMap();
    private volatile RelaySnapshot snapshot=new RelaySnapshot(-1,0,0,false,Collections.<String,OutputSnapshot>emptyMap());
    private final CountDownLatch initialized=new CountDownLatch(1);
    private volatile HandlerThread thread;
    private volatile Handler handler;
    private volatile boolean alive,cleaned;
    private volatile long frameCount,lastFrameMs,baseIntervalMs;
    private volatile OutputStallListener stallListener;
    private volatile OutputUnavailableListener unavailableListener;
    private final Map<String,RelayLifecycle.TargetWait> targetWaits=new LinkedHashMap<String,RelayLifecycle.TargetWait>();
    private final Map<String,Object> admissionFailures=new LinkedHashMap<String,Object>();
    private EGLDisplay display=EGL14.EGL_NO_DISPLAY;
    private EGLContext context=EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface idle=EGL14.EGL_NO_SURFACE;
    private volatile SurfaceTexture inputTexture;
    private volatile Surface inputSurface;
    private int inputTexId;
    private int inputLeases;
    // guarded by this; never hold while native code executes
    private boolean rootCleanupStarted;
    private long lastInputWatchCount=-1;
    public GlCameraRelay(int width,int height) {
        bufW=width>0?width:1280;
        bufH=height>0?height:720;
        root=RESOURCES.reserve(true,null);
        if (root==null) {
            releasing.set(true);
            cleaned=true;
            initialized.countDown();
        }
        else root.owner=this;
        publishSnapshot();
    }
    public long getGeneration() {
        return root==null?-1:root.id;
    }
    public boolean isAlive() {
        return alive&&!releasing.get();
    }
    public boolean isReleaseRequested() {
        return releasing.get();
    }
    public boolean isCleanupComplete() {
        return cleaned;
    }
    public long getFrameCount() {
        return frameCount;
    }
    public long getLastFrameMs() {
        return lastFrameMs;
    }
    public int outputCount() {
        return getOutputCount();
    }
    public int getOutputCount() {
        return published.size();
    }
    public RelaySnapshot getSnapshot() {
        return snapshot;
    }
    public long getLastSwapMs(String key) {
        OutputSnapshot output=snapshot.outputs.get(key);
        return output==null||output.retiring?0:output.lastSuccessElapsedMs;
    }
    public boolean hasOutput(String key) {
        OutputSnapshot output=snapshot.outputs.get(key);
        return !releasing.get()&&output!=null&&!output.retiring;
    }
    public String snapshotOutput(String key,long ignoredNow) {
        OutputSnapshot output=snapshot.outputs.get(key);
        if (output==null)return "no output";
        return "generation="+output.generation+" active="+output.active+" paused="+output.paused
        +" retiring="+output.retiring+" successes="+output.successes
        +" lastSuccessElapsed="+output.lastSuccessElapsedMs+" error="+output.error
        +" swapMs="+output.swapCostMs;
    }
    private void publishSnapshot() {
        Map<String,OutputSnapshot> copy=new LinkedHashMap<String,OutputSnapshot>();
        for (Map.Entry<String,Output> entry:outputs.entrySet())copy.put(entry.getKey(),new OutputSnapshot(entry.getValue()));
        snapshot=new RelaySnapshot(getGeneration(),frameCount,lastFrameMs,releasing.get(),copy);
    }
    public void setBaseIntervalMs(long ms) {
        baseIntervalMs=Math.max(0,ms);
    }
    public void setOutputStallListener(OutputStallListener listener) {
        stallListener=listener;
    }
    public void setOutputUnavailableListener(OutputUnavailableListener listener) {
        unavailableListener=listener;
    }
    public int viewOutputCount() {
        int n=0;
        for (Output o:published.values())if (o.active&&!o.paused&&!o.token.retiring.get()&&!"ICL".equals(o.target.key))n++;
        return n;
    }
    public int outputCountActive() {
        int n=0;
        for (Output o:published.values())if (o.active&&!o.paused&&!o.token.retiring.get())n++;
        return n;
    }
    public int getLeakedOutputs() {
        int n=0;
        for (Output o:children)if (o.quarantined)n++;
        return n;
    }
    public boolean needsFullRebuild() {
        return rebuild.get();
    }
    public boolean consumeFullRebuildRequest() {
        return rebuild.getAndSet(false);
    }
    public boolean isSurfaceReleaseEffective() {
        return false;
    }
    /** Bounded, one-shot acquisition. Caller MUST be the camera owner, never UI/input owner. */
    public Surface obtainInputSurface() {
        if (Looper.myLooper()==Looper.getMainLooper() || (thread!=null&&Thread.currentThread()==thread))throw new IllegalStateException("input acquisition must run on camera owner");
        boolean start;
        synchronized (this) {
            if (releasing.get())return null;
            start=initStarted.compareAndSet(false,true);
        }
        if (start) {
            try {
                thread=new HandlerThread("CameraGlRelayIn-"+getGeneration());
                thread.start();
                handler=new Handler(thread.getLooper());
                if (!handler.post(new Runnable() {
                    public void run() {
                        try {
                            if (!releasing.get() && initializeInput() && !releasing.get()) {
                                alive=true;
                                queueDesired();
                            }
                            else release();
                        }
                        catch (Throwable t) {
                            Log.e(TAG,"input initialization",t);
                            release();
                        }
                        finally {
                            initialized.countDown();
                            if (releasing.get())scheduleMaintenance();
                        }
                    }
                })) {
                    initialized.countDown();
                    release();
                }
            }
            catch (Throwable t) {
                Log.e(TAG,"input worker start",t);
                initialized.countDown();
                releasing.set(true);
                root.retiring.set(true);
                root.nativeClean=true;
                root.contextGone=true;
                if (thread==null||!thread.isAlive())root.threadExited=true;
                if (thread!=null)thread.quitSafely();
            }
        }
        boolean done=false;
        try {
            done=initialized.await(2000L,TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!done) {
            release();
            return null;
        }
        return isAlive()?inputSurface:null;
    }
    public Surface retainInputSurface() {
        Surface surface=obtainInputSurface();
        synchronized (this) {
            if (surface==null||surface!=inputSurface||releasing.get())return null;
            inputLeases++;
            return surface;
        }
    }
    public void releaseInputSurface(Surface surface) {
        synchronized (this) {
            if (surface!=inputSurface||inputLeases==0) {
                Log.w(TAG,"unmatched input lease release");
                return;
            }
            inputLeases--;
        }
        scheduleMaintenance();
    }
    private boolean initializeInput() {
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display==EGL14.EGL_NO_DISPLAY)return false;
        int[] versions=new int[2];
        if (!EGL14.eglInitialize(display,versions,0,versions,1))return false;
        EGLConfig[] configs=new EGLConfig[1];
        int[] count=new int[1];
        int[] attrs= {
            EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,0,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT|EGL14.EGL_PBUFFER_BIT,EGL14.EGL_NONE
        };
        if (!EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)||count[0]==0)return false;
        config=configs[0];
        context=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,new int[] {
            EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE
        },0);
        if (context==null||context==EGL14.EGL_NO_CONTEXT)return false;
        idle=EGL14.eglCreatePbufferSurface(display,config,new int[] {
            EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE
        },0);
        if (idle==null||idle==EGL14.EGL_NO_SURFACE||!EGL14.eglMakeCurrent(display,idle,idle,context))return false;
        int[] ids=new int[1];
        GLES20.glGenTextures(1,ids,0);
        inputTexId=ids[0];
        if (inputTexId==0)return false;
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,inputTexId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        inputTexture=new SurfaceTexture(inputTexId);
        inputTexture.setDefaultBufferSize(bufW,bufH);
        inputTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            public void onFrameAvailable(SurfaceTexture st) {
                if (st==inputTexture)drawFrame();
            }
        },handler);
        inputSurface=new Surface(inputTexture);
        return inputSurface.isValid();
    }
    public void setOutputs(List<Target> targets) {
        if (releasing.get())return;
        ArrayList<Target> copy=new ArrayList<Target>();
        if (targets!=null) {
            for (Target t:targets) {
                if (t==null)continue;
                if (!validKey(t.key)||copy.size()>=5)throw new IllegalArgumentException("unsupported output set");
                for (Target old:copy)if (old.key.equals(t.key)||old.identity()==t.identity())throw new IllegalArgumentException("duplicate output");
                copy.add(t.copy());
            }
        }
        desired.set(Collections.unmodifiableList(copy));
        queueDesired();
    }
    private static boolean validKey(String key) {
        return "P".equals(key)||"S".equals(key)||"EDIT_P".equals(key)||"EDIT_S".equals(key)||"ICL".equals(key);
    }
    private void queueDesired() {
        Handler h=handler;
        if (h==null||releasing.get())return;
        if (desiredQueued.compareAndSet(false,true)&&!h.post(new Runnable() {
            public void run() {
                desiredQueued.set(false);
                if (isAlive())applyOutputs(desired.get());
            }
        }))desiredQueued.set(false);
    }
    private void applyOutputs(List<Target> targets) {
        for (Output o:new ArrayList<Output>(outputs.values())) {
            Target match=null;
            for (Target t:targets)if (t.key.equals(o.target.key)&&t.identity()==o.target.identity())match=t;
            if (match==null||o.token.retiring.get()) {
                if (o.failed)failedTargets.put(o.target.key,o.target.identity());
                outputs.remove(o.target.key);
                o.retire("target removed",false);
            }
            else o.paused=match.paused;
        }
        for (Target t:targets) {
            if (outputs.containsKey(t.key)||failedTargets.get(t.key)==t.identity())continue;
            Output created=null;
            String denied=null;
            synchronized (TARGET_LOCK) {
                if (releasing.get()||isBlockedLocked(t.identity()))continue;
                RelayLifecycle.Token token=RESOURCES.reserve(false,t.identity());
                if (token==null) {
                    RelayLifecycle.Token holder=RESOURCES.target(t.identity());
                    if (holder==null) {
                        denied="process output capacity exhausted";
                    }
                    else {
                        long now=SystemClock.elapsedRealtime();
                        RelayLifecycle.TargetWait wait=RelayLifecycle.TargetWait.observe(targetWaits.get(t.key),t.identity(),getGeneration(),now);
                        targetWaits.put(t.key,wait);
                        Object owner=holder.owner;
                        boolean overdue=owner instanceof Output && ((Output)owner).quarantined;
                        if (overdue||wait.expired(now))denied="previous producer retirement unconfirmed";
                    }
                }
                else {
                    try {
                        targetWaits.remove(t.key);
                        created=new Output(this,t,token);
                        children.add(created);
                        outputs.put(t.key,created);
                    }
                    catch (Throwable failure) {
                        // Construction has not started a worker or allocated EGL/GL objects.
                        children.remove(created);
                        outputs.remove(t.key);
                        token.nativeClean=true;
                        token.contextGone=true;
                        token.threadExited=true;
                        RESOURCES.finish(token);
                        created=null;
                        denied="output allocation failed";
                        Log.e(TAG,denied,failure);
                    }
                }
            }
            if (created!=null)created.start();
            if (denied!=null)signalUnavailable(t,denied);
        }
        published=Collections.unmodifiableMap(new LinkedHashMap<String,Output>(outputs));
        publishSnapshot();
    }
    public void detachOutput(final String key,final SurfaceTexture st) {
        // Producer detachment is distinct from TextureView destruction ownership transfer.
        for (Output o:children)if (o.target.key.equals(key)&&(st==null||o.target.texture==st))o.retire("explicit detach",false);
        List<Target> before,after;
        do {
            before=desired.get();
            after=new ArrayList<Target>();
            for (Target t:before)if (!t.key.equals(key)||(st!=null&&t.texture!=st))after.add(t);
        }
        while (!desired.compareAndSet(before,Collections.unmodifiableList(after)));
        scheduleMaintenance();
    }
    private void drawFrame() {
        if (!isAlive()||inputTexture==null)return;
        try {
            if (!EGL14.eglMakeCurrent(display,idle,idle,context)) {
                int error=EGL14.eglGetError();
                Log.w(TAG,"input makeCurrent "+error);
                rebuild.set(true);
                return;
            }
            inputTexture.updateTexImage();
            float[] matrix=new float[16];
            inputTexture.getTransformMatrix(matrix);
            long pts=inputTexture.getTimestamp();
            frameCount++;
            lastFrameMs=SystemClock.elapsedRealtime();
            GLES20.glFlush();
            // Legacy OES shared-image race is not resolved by this flush. Fence/FBO redesign deferred.
            for (Output o:outputs.values())if (lastFrameMs-o.stats.lastSuccess>=Math.max(baseIntervalMs,o.pacing.minIntervalMs))o.draw(matrix,pts);
        }
        catch (Throwable t) {
            Log.w(TAG,"input frame failed",t);
        }
    }
    private void signalUnavailable(final Target target,final String reason) {
        if (admissionFailures.get(target.key)==target.identity())return;
        admissionFailures.put(target.key,target.identity());
        failedTargets.put(target.key,target.identity());
        final OutputUnavailableListener listener=unavailableListener;
        Log.e(TAG,"output unavailable: "+target.key+" "+reason);
        if (listener==null)return;
        if (!MAIN.post(new Runnable() {
            public void run() {
                if (releasing.get())return;
                for (Target current:desired.get()) {
                    if (current.key.equals(target.key)&&current.identity()==target.identity()) {
                        listener.onOutputUnavailable(target.key,target.identity(),reason);
                        return;
                    }
                }
            }
        }))Log.e(TAG,"main rejected unavailable notification");
    }
    private void signalFailure(final Output o,String reason) {
        Log.w(TAG,"output unavailable "+o.target.key+" generation="+o.token.id+" "+reason);
        final OutputStallListener listener=stallListener;
        if (listener!=null)MAIN.post(new Runnable() {
            public void run() {
                if (releasing.get())return;
                Output current=published.get(o.target.key);
                // Old producer failures cannot ask the service to replace a new producer's view.
                boolean stillDesired=false;
                for (Target t:desired.get())if (t.key.equals(o.target.key)&&t.identity()==o.target.identity())stillDesired=true;
                if (stillDesired&&(current==null||current==o))listener.onOutputStalled(o.target.key,o.target.identity());
            }
        });
    }
    private void scheduleMaintenance() {
        Handler h=handler;
        if (h==null)return;
        if (maintenanceQueued.compareAndSet(false,true)) {
            if (!h.post(new Runnable() {
                public void run() {
                    maintenanceQueued.set(false);
                    maintain();
                }
            }))maintenanceQueued.set(false);
        }
    }
    private void maintain() {
        long now=SystemClock.elapsedRealtime();
        boolean inputProgress=lastInputWatchCount!=frameCount;
        lastInputWatchCount=frameCount;
        for (Output o:children) {
            if (!o.token.retiring.get()) {
                if (!o.active&&now-o.created>=2000)o.retire("initialization overdue",true);
                else if (o.busy.get()&&now-o.busySince>=STALL_MS)o.retire("draw/swap overdue",true);
                else if (o.active&&!o.paused&&inputProgress&&now-(o.stats.lastSuccess>0?o.stats.lastSuccess:o.created)>(o.stats.lastSuccess>0?SILENT_MS:INITIAL_GRACE_MS))o.retire("no successful swap",true);
            }
            if (o.token.contextGone && o.program!=0 && context!=null&&context!=EGL14.EGL_NO_CONTEXT) {
                // Child context is physically gone: root can delete its shared program without a cycle.
                try {
                    if (EGL14.eglMakeCurrent(display,idle,idle,context)) {
                        GLES20.glDeleteProgram(o.program);
                        if (GLES20.glGetError()==GLES20.GL_NO_ERROR) {
                            o.program=0;
                            if (o.glObjectsClean)o.token.nativeClean=true;
                        }
                    }
                }
                catch (Throwable t) {
                    Log.w(TAG,"shared program remains charged",t);
                }
            }
        }
        if (releasing.get()) {
            int leases;
            synchronized (this) {
                leases=inputLeases;
            }
            List<RelayLifecycle.Token> childTokens=new ArrayList<RelayLifecycle.Token>();
            for (Output o:children)childTokens.add(o.token);
            if (!RelayLifecycle.canFinalizeRoot(leases,childTokens))return;
            cleanupInput();
        }
        else if (isAlive()) {
            // No automatic same-target reconnect: old reservation remains until true cleanup.
            applyOutputs(desired.get());
        }
    }
    public void abandon() {
        release();
    }
    public void release() {
        boolean neverStarted;
        List<Output> retiring;
        synchronized (TARGET_LOCK) {
            synchronized (this) {
                if (!releasing.compareAndSet(false,true))return;
                neverStarted=!initStarted.get();
            }
            retiring=new ArrayList<Output>(children);
        }
        alive=false;
        if (root==null)return;
        root.retiring.set(true);
        desired.set(Collections.<Target>emptyList());
        for (Output o:retiring)o.retire("relay released",false);
        if (neverStarted) {
            root.nativeClean=true;
            root.contextGone=true;
            root.threadExited=true;
            RESOURCES.finish(root);
            cleaned=true;
            initialized.countDown();
        }
        else scheduleMaintenance();
    }
    private void cleanupInput() {
        if (rootCleanupStarted)return;
        rootCleanupStarted=true;
        outputs.clear();
        published=Collections.emptyMap();
        publishSnapshot();
        boolean ok=true;
        try {
            if (inputTexture!=null)inputTexture.setOnFrameAvailableListener(null);
            if (context!=null&&context!=EGL14.EGL_NO_CONTEXT) {
                if (inputTexId!=0) {
                    if (EGL14.eglMakeCurrent(display,idle,idle,context)) {
                        GLES20.glDeleteTextures(1,new int[] {
                            inputTexId
                        },0);
                        if (GLES20.glGetError()==GLES20.GL_NO_ERROR)inputTexId=0;
                        else ok=false;
                    }
                    else ok=false;
                }
                if (!EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT))ok=false;
            }
            if (inputSurface!=null) {
                inputSurface.release();
                inputSurface=null;
            }
            if (inputTexture!=null) {
                inputTexture.release();
                inputTexture=null;
            }
            if (idle!=null&&idle!=EGL14.EGL_NO_SURFACE) {
                if (EGL14.eglDestroySurface(display,idle))idle=EGL14.EGL_NO_SURFACE;
                else ok=false;
            }
            if (context!=null&&context!=EGL14.EGL_NO_CONTEXT) {
                if (EGL14.eglDestroyContext(display,context))context=EGL14.EGL_NO_CONTEXT;
                else ok=false;
            }
            root.contextGone=context==null||context==EGL14.EGL_NO_CONTEXT;
            root.nativeClean=ok&&root.contextGone&&inputTexId==0;
        }
        catch (Throwable t) {
            Log.e(TAG,"input cleanup remains charged",t);
        }
        finally {
            if (thread!=null)thread.quitSafely();
        }
    }
    private static int compileShader(int type,String source) {
        int shader=GLES20.glCreateShader(type);
        if (shader==0)return 0;
        boolean transferred=false;
        try {
            GLES20.glShaderSource(shader,source);
            GLES20.glCompileShader(shader);
            int[] status=new int[1];
            GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0);
            if (status[0]!=GLES20.GL_TRUE)return 0;
            transferred=true;
            return shader;
        }
        finally {
            if (!transferred)GLES20.glDeleteShader(shader);
        }
    }
    private static int createProgram() {
        int vertex=0,fragment=0,program=0;
        boolean transferred=false;
        try {
            vertex=compileShader(GLES20.GL_VERTEX_SHADER,VERTEX_SHADER);
            fragment=compileShader(GLES20.GL_FRAGMENT_SHADER,FRAGMENT_SHADER);
            if (vertex==0||fragment==0)return 0;
            program=GLES20.glCreateProgram();
            if (program==0)return 0;
            GLES20.glAttachShader(program,vertex);
            GLES20.glAttachShader(program,fragment);
            GLES20.glLinkProgram(program);
            int[] status=new int[1];
            GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,status,0);
            if (status[0]!=GLES20.GL_TRUE)return 0;
            transferred=true;
            return program;
        }
        finally {
            if (vertex!=0)GLES20.glDeleteShader(vertex);
            if (fragment!=0)GLES20.glDeleteShader(fragment);
            if (!transferred&&program!=0)GLES20.glDeleteProgram(program);
        }
    }
}
