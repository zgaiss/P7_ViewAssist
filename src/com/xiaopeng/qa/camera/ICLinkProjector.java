package com.xiaopeng.qa.camera;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Experimental native-VPU receiver client. Raw Camera2 stream, NOT TextureView crop.
 * A session belongs to exactly one side; switching closes it and starts at a new IDR.
 * No arbitrary P-frame dropping: congestion abandons the GOP and requests an IDR.
 */
public class ICLinkProjector {
    private static final String TAG = "ICLinkProjector";
    public static final String DEFAULT_HOST = "172.20.1.40";
    public static final int DEFAULT_PORT = 9000;
    private volatile Session session;
    private volatile Runnable failureListener;
    public void setFailureListener(Runnable listener) { failureListener=listener; }
    private static final class Frame {
        final byte[] data; final long pts, queuedNs = System.nanoTime();
        Frame(byte[] b, long p) { data=b; pts=p; }
    }
    private static final class Session {
        volatile boolean running = true, needIdr = true, networkFailed;
        final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(3);
        final Socket socket = new Socket();
        MediaCodec codec; Surface surface; Thread push, send, watchdog;
        volatile long writeStartedNs, connectedNs;
        int side;
    }
    private static final class Opened {
        final MediaCodec codec; final Surface surface;
        Opened(MediaCodec c, Surface s) { codec=c; surface=s; }
    }
    /** The vendor c2.qti encoder ignores the Baseline hint and emits High/CABAC,
     *  which the instrument gate rejects before touching the VPU. Prefer the
     *  platform software encoder, which honors Baseline; fall back to the vendor
     *  encoder only if neither software codec can be configured. */
    private static MediaFormat encoderFormat() {
        MediaFormat fmt=MediaFormat.createVideoFormat("video/avc",320,240);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE,800_000);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE,30);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
        fmt.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        fmt.setInteger(MediaFormat.KEY_LEVEL,MediaCodecInfo.CodecProfileLevel.AVCLevel31);
        fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES,0);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        return fmt;
    }
    private static Opened openEncoder() throws IOException {
        Throwable last=null;
        for(String name:new String[]{"c2.android.avc.encoder","OMX.google.h264.encoder",null}) {
            MediaCodec c=null;
            try {
                c = name==null ? MediaCodec.createEncoderByType("video/avc") : MediaCodec.createByCodecName(name);
                c.configure(encoderFormat(),null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
                Surface surface=c.createInputSurface();
                c.start();
                Log.i(TAG,"instrument encoder="+(name==null?"default":name));
                return new Opened(c,surface);
            } catch(Throwable e) {
                last=e;
                if(c!=null) { try { c.release(); } catch(Throwable ignored) {} }
            }
        }
        throw new IOException("no usable AVC encoder",last);
    }
    /** Profile/level of the first SPS, or null when no complete SPS is present. */
    private static int[] spsProfileLevel(byte[] annexB) {
        for(int i=0;i+3<annexB.length;) {
            int sc = annexB[i]==0&&annexB[i+1]==0&&annexB[i+2]==1 ? 3
                    : annexB[i]==0&&annexB[i+1]==0&&annexB[i+2]==0&&annexB[i+3]==1 ? 4 : 0;
            if(sc==0) { i++; continue; }
            int o=i+sc;
            if(o+3>=annexB.length) return null;
            if((annexB[o]&31)==7) return new int[]{annexB[o+1]&255,annexB[o+3]&255};
            i=o+1;
        }
        return null;
    }
    private static String headHex(byte[] b) {
        StringBuilder s=new StringBuilder();
        for(int i=0;i<b.length&&i<32;i++)s.append(String.format("%02x",b[i]&255));
        return s.toString();
    }
    public Surface getInputSurface() { Session s=session; return s == null || !s.running ? null : s.surface; }
    public boolean isRunning() { Session s=session; return s != null && s.running; }
    public boolean isForSide(int side) { Session s=session; return s != null && s.running && s.side==side; }

    public synchronized void start(String host, int port, int side, int[] crop, String token) {
        if(session!=null)throw new IllegalStateException("retire old camera target before starting another encoder");
        Session s = new Session(); s.side=side;
        try {
            ICLinkProtocol.validateCrop(crop); ICLinkProtocol.token(token);
            Opened opened=openEncoder();
            s.codec=opened.codec; s.surface=opened.surface; session=s;
            s.push=new Thread(() -> pushLoop(s),"icl-encode");
            s.send=new Thread(() -> sendLoop(s,host,port,crop.clone(),token),"icl-network");
            s.watchdog=new Thread(() -> watchdog(s),"icl-write-lease");
            s.push.start(); s.send.start(); s.watchdog.start();
        } catch (Throwable e) { fail(s,e); if(session==s)session=null; dispose(s); }
    }
    private static void requestIdr(Session s) {
        try { Bundle b=new Bundle(); b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0); s.codec.setParameters(b); }
        catch(Throwable e) { Log.w(TAG,"IDR request failed",e); }
    }
    private void pushLoop(Session s) {
        byte[] config=new byte[0]; MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        boolean checked=false;
        try {
            while(s.running) {
                int index=s.codec.dequeueOutputBuffer(info,10_000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat fmt=s.codec.getOutputFormat();
                    byte[] all=new byte[0];
                    for(String k:new String[]{"csd-0","csd-1"}) {
                        ByteBuffer b=fmt.getByteBuffer(k);
                        if(b!=null) { b=b.duplicate(); byte[] part=new byte[b.remaining()]; b.get(part); all=ICLinkProtocol.concat(all,ICLinkProtocol.annexB(part)); }
                    }
                    if(all.length>0)config=ICLinkProtocol.config(all);
                }
                if(index<0)continue;
                byte[] data; int flags=info.flags; long pts=info.presentationTimeUs;
                try {
                    ByteBuffer b=s.codec.getOutputBuffer(index);
                    if(b==null || info.size<=0)continue;
                    if(info.size>ICLinkProtocol.MAX_AU)throw new IOException("oversize codec AU");
                    b.position(info.offset); b.limit(info.offset+info.size);
                    data=new byte[info.size]; b.get(data);
                } finally { s.codec.releaseOutputBuffer(index,false); }
                // Keep draining the encoder after TCP failure: an undrained Surface
                // can backpressure the shared Camera2 session and freeze local preview.
                if(s.networkFailed)continue;
                data=ICLinkProtocol.annexB(data);
                byte[] found=ICLinkProtocol.config(data);
                if(found.length>0)config=ICLinkProtocol.mergeConfig(config,found);
                if(!checked&&config.length>0) {
                    int[] pl=spsProfileLevel(config);
                    if(pl!=null) {
                        checked=true;
                        Log.i(TAG,"encoder SPS profile="+pl[0]+" level="+pl[1]+" head="+headHex(config));
                        if(pl[0]!=66)throw new IOException("encoder profile "+pl[0]+" is not Baseline 66");
                        if(pl[1]>31)throw new IOException("encoder level "+pl[1]+" exceeds verified 3.1");
                    }
                }
                boolean idr=ICLinkProtocol.hasNal(data,5), vcl=idr||ICLinkProtocol.hasNal(data,1);
                if(!vcl) {
                    if(!ICLinkProtocol.isControlOnly(data))throw new IOException("unsupported non-control NAL; refusing silent video drop");
                    continue;
                }
                if(s.needIdr&&!idr)continue;
                if(idr) {
                    if(!ICLinkProtocol.hasNal(data,7)||!ICLinkProtocol.hasNal(data,8))data=ICLinkProtocol.concat(config,data);
                    if(!ICLinkProtocol.hasNal(data,7)||!ICLinkProtocol.hasNal(data,8))continue;
                    s.needIdr=false;
                }
                if(!s.queue.offer(new Frame(data,pts))) {
                    s.queue.clear(); s.needIdr=true; requestIdr(s);
                }
            }
        } catch(Throwable e) {
            fail(s,e);
            Runnable listener=failureListener;
            if(listener!=null&&session==s)listener.run();
        }
    }
    private static void sendLoop(Session s,String host,int port,int[] crop,String token) {
        try {
            s.socket.setTcpNoDelay(true); s.socket.setSendBufferSize(64*1024); s.socket.connect(new InetSocketAddress(host,port),2000);
            s.socket.setSoTimeout(2000);
            s.writeStartedNs=System.nanoTime();
            ICLinkProtocol.hello(s.socket.getOutputStream(),s.side,crop,token);
            s.writeStartedNs=0;
            if(s.socket.getInputStream().read()!=1)throw new IOException("receiver rejected session");
            s.connectedNs=System.nanoTime();
            s.queue.clear(); s.needIdr=true; requestIdr(s);
            boolean first=true;
            while(s.running) {
                Frame f=s.queue.poll(200,TimeUnit.MILLISECONDS); if(f==null)continue;
                if(System.nanoTime()-f.queuedNs>2_000_000_000L)throw new IOException("stale AU; end session");
                if(first&&!ICLinkProtocol.hasNal(f.data,5))continue;
                first=false; s.writeStartedNs=System.nanoTime();
                ICLinkProtocol.video(s.socket.getOutputStream(),f.data,f.pts); s.writeStartedNs=0;
            }
        } catch(Throwable e) { networkFailure(s,e); }
        finally { try{s.socket.close();}catch(IOException ignored){} }
    }
    private static void watchdog(Session s) {
        try {
            while(s.running) {
                long now=System.nanoTime();
                long t=s.writeStartedNs;
                /* VPU setup on the instrument can stop reading for seconds; keep the
                 * first write lease generous instead of aborting a healthy session. */
                if(t!=0&&now-s.connectedNs>8_000_000_000L&&now-t>750_000_000L) { networkFailure(s,new IOException("TCP write timeout")); break; }
                Thread.sleep(100);
            }
        } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
    private static void networkFailure(Session s,Throwable e) {
        s.networkFailed=true; s.writeStartedNs=0; s.queue.clear();
        try{s.socket.close();}catch(IOException ignored){}
        Log.w(TAG,"Instrument network ended; encoder remains draining until camera closes: "+e.getMessage());
    }
    private static void fail(Session s,Throwable e) {
        s.running=false; try{s.socket.close();}catch(IOException ignored){}
        Log.w(TAG,"Instrument encoder stopped; caller must detach its camera target: "+e.getMessage());
    }
    /** Detach logically now, release ONLY after the referencing CameraDevice onClosed.
     * Keep dequeueing while the old Camera2 target is retiring. */
    public synchronized Runnable retire() {
        Session s=session; session=null; if(s==null)return null;
        networkFailure(s,new IOException("camera target retiring"));
        return () -> dispose(s);
    }
    private static void dispose(Session s) {
        s.running=false; try{s.socket.close();}catch(IOException ignored){}
        for(Thread t:new Thread[]{s.push,s.send,s.watchdog})if(t!=null&&t!=Thread.currentThread()) {
            t.interrupt(); try{t.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
        if(s.push!=null&&s.push.isAlive()) {
            Log.e(TAG,"Codec thread did not exit; retaining codec instead of concurrent release"); return;
        }
        release(s);
    }
    private static void release(Session s) {
        if(s.codec!=null) {try{s.codec.stop();}catch(Throwable ignored){} try{s.codec.release();}catch(Throwable ignored){} }
        if(s.surface!=null)try{s.surface.release();}catch(Throwable ignored){}
        s.queue.clear();
    }
}
