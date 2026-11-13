package com.xiaopeng.qa.camera;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
/** Platform-independent production admission and completion policy. No native calls under its lock. */
final class RelayLifecycle {
    static final int MAX_OUTPUTS = 8, MAX_ROOTS = 2;
    static final class Token {
        final long id;
        final boolean root;
        final Object target;
        final AtomicBoolean retiring = new AtomicBoolean();
        volatile boolean nativeClean, threadExited, contextGone;
        volatile Object owner;
        Token(long id, boolean root, Object target) {
            this.id=id;
            this.root=root;
            this.target=target;
        }
    }
    static final class Stats {
        final long attempts, successes, lastSuccess, cost;
        final int error;
        Stats(long a,long s,long last,long cost,int error) {
            attempts=a;
            successes=s;
            lastSuccess=last;
            this.cost=cost;
            this.error=error;
        }
        Stats result(boolean success,long now,long cost,int error) {
            return new Stats(attempts+1,successes+(success?1:0),success?now:lastSuccess,cost,error);
        }
    }
    /** Owner-local v1.70 bounded pacing policy; never allocates or requests recovery. */
    static final class Pacing {
        final long minIntervalMs;
        final int slowStreak;
        Pacing(long minIntervalMs,int slowStreak) {
            this.minIntervalMs=minIntervalMs;
            this.slowStreak=slowStreak;
        }
        Pacing successfulSwap(long swapMs,boolean multipleVisibleViews) {
            long interval=minIntervalMs;
            int streak=slowStreak;
            if (swapMs>60L&&multipleVisibleViews) {
                interval=interval<=0?16L:Math.min(200L,interval+16L);
            }
            else if (swapMs<12L&&interval>0L) {
                interval=Math.max(0L,interval-16L);
            }
            if (swapMs>200L&&multipleVisibleViews) {
                streak++;
                if (streak>=2) {
                    streak=0;
                    interval=Math.min(120L,Math.max(33L,interval*2));
                }
            }
            else {
                streak=0;
            }
            return new Pacing(interval,streak);
        }
    }
    /** A same-target producer can retire normally, but cannot leave a view waiting forever. */
    static final class TargetWait {
        static final long GRACE_MS = 2000L;
        final Object target;
        final long relayGeneration, sinceElapsedMs;
        private TargetWait(Object target,long relayGeneration,long now) {
            this.target=target;
            this.relayGeneration=relayGeneration;
            sinceElapsedMs=now;
        }
        static TargetWait observe(TargetWait previous,Object target,long relayGeneration,long now) {
            if (previous!=null&&previous.target==target&&previous.relayGeneration==relayGeneration)return previous;
            return new TargetWait(target,relayGeneration,now);
        }
        boolean expired(long now) {
            return now-sinceElapsedMs>=GRACE_MS;
        }
    }
    private long sequence;
    private final List<Token> tokens = new ArrayList<Token>();
    private final IdentityHashMap<Object,Token> targets = new IdentityHashMap<Object,Token>();
    synchronized Token reserve(boolean root,Object target) {
        int count=0;
        for (Token t:tokens) if (t.root==root) count++;
        if (count >= (root?MAX_ROOTS:MAX_OUTPUTS) || (!root && targets.containsKey(target))) return null;
        Token t=new Token(++sequence,root,target);
        tokens.add(t);
        if (!root) targets.put(target,t);
        return t;
    }
    synchronized boolean finish(Token t) {
        if (!t.nativeClean || !t.threadExited) return false;
        if (!tokens.remove(t)) return false;
        if (!t.root && targets.get(t.target)==t) targets.remove(t.target);
        t.owner=null;
        return true;
    }
    synchronized List<Token> snapshot() {
        return new ArrayList<Token>(tokens);
    }
    synchronized Token target(Object target) {
        return targets.get(target);
    }
    synchronized boolean empty() {
        return tokens.isEmpty();
    }
    static boolean accepts(Token t,long currentId) {
        return t.id==currentId && !t.retiring.get();
    }
    /** Root depends on native child completion, not child budget/thread bookkeeping. */
    static boolean canFinalizeRoot(int inputLeases,List<Token> children) {
        if (inputLeases!=0)return false;
        for (Token child:children) {
            if (!child.contextGone||!child.nativeClean)return false;
        }
        return true;
    }
}
