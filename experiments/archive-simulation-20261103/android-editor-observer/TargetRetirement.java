package com.xiaopeng.qa.camera;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/** Conservative attachment ledger. Call attach BEFORE submitting a target to camera.
 * Failure to observe onClosed retains resources; neither timeout nor session failure
 * authorizes release. Identity semantics avoid equality aliasing of native wrappers.
 */
final class TargetRetirement<S,D> {
    private final Map<S,Entry<D>> targets = new IdentityHashMap<>();
    private static final class Entry<D> {
        final Set<D> owners = Collections.newSetFromMap(new IdentityHashMap<D,Boolean>());
        boolean retired;
        Runnable cleanup;
    }
    synchronized void attach(S surface,D device) {
        if(surface==null||device==null)throw new IllegalArgumentException("null ownership");
        Entry<D> entry=targets.get(surface);
        if(entry==null){entry=new Entry<>();targets.put(surface,entry);}
        if(entry.retired)throw new IllegalStateException("attach after retire");
        entry.owners.add(device);
    }
    void retire(S surface,Runnable cleanup) {
        if(cleanup==null)return;
        Runnable ready=null;
        synchronized(this) {
            Entry<D> entry=targets.get(surface);
            if(entry==null){ready=cleanup;}
            else {
                if(entry.retired)throw new IllegalStateException("duplicate retire");
                entry.retired=true; entry.cleanup=cleanup;
                if(entry.owners.isEmpty()){targets.remove(surface);ready=cleanup;}
            }
        }
        if(ready!=null)ready.run();
    }
    void onClosed(D device) {
        ArrayList<Runnable> ready=new ArrayList<>();
        synchronized(this) {
            java.util.Iterator<Entry<D>> it=targets.values().iterator();
            while(it.hasNext()) {
                Entry<D> e=it.next();e.owners.remove(device);
                if(e.retired&&e.owners.isEmpty()){ready.add(e.cleanup);it.remove();}
            }
        }
        for(Runnable r:ready)r.run();
    }
    synchronized boolean hasPending() {
        for(Entry<D> e:targets.values())if(e.retired)return true;
        return false;
    }
}
