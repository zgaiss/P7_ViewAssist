"""Explicit HOST-ONLY custody bridge, not production integration or hardware authority.
The retained mmap file and its writers are a cooperative virtual device. No /dev paths.
"""
import contextlib, fcntl, json, os, pathlib, signal, socket, stat, struct
import sealed as s
import supervisor as m

CTX={'device_id':'synthetic-p7-composed','boot_id':'host-boot-A','session_id':'host-session-A','abi':'linux-fb-arm32-le'}

class Hook(m.Hook):
    def __init__(self, failure=None, log=None):
        super().__init__(); self.failure=failure; self.log=log; self.index=0
    def call(self,label,fn,*args,**kw):
        i=self.index; self.index+=1; self.trace.append(label)
        if self.log:
            with open(self.log,'a') as f: f.write(json.dumps({'i':i,'label':label,'side':'before'})+'\n')
        self.trip(i,'before')
        result=fn(*args,**kw)
        if self.log:
            with open(self.log,'a') as f: f.write(json.dumps({'i':i,'label':label,'side':'after'})+'\n')
        self.trip(i,'after')
        return result
    def trip(self,i,side):
        if self.failure and self.failure[:2]==(i,side):
            if self.failure[2]=='kill': os.kill(os.getpid(),signal.SIGKILL)
            if self.failure[2]=='exit': os._exit(91)
            raise OSError(5,'injected uncertain host boundary')

def line(sock):
    data=bytearray()
    while len(data)<4096:
        b=sock.recv(1)
        if not b: raise EOFError('bridge peer closed')
        data+=b
        if b==b'\n': return bytes(data)
    raise s.Refused('oversized bridge message')

def read_blobs(prefix):
    """Exact files the paused production C display persisted, no synthetic re-creation."""
    p=pathlib.Path(prefix); d=s.open_dir(str(p.parent))
    try:
        blobs={}
        for n in s.NAMES:
            s.require(n.startswith(p.name+'.'),'unexpected snapshot prefix')
            blobs[n]=s.read_file(d,n,0o600,s.MAX_PIXELS if n.endswith('pixels') else 160,sync=True)
        s.validate_payload(blobs); os.fsync(d); return blobs
    finally: os.close(d)

class SharedDisplay:
    """Trusted host mmap-file model. Byte readback is NOT physical display readback."""
    def __init__(self,path,layout,context=CTX,writer_lock=None):
        self.path=str(path); self.layout=layout; self.context=dict(context); self.writer_lock=str(writer_lock) if writer_lock is not None else None
        fd=os.open(self.path,os.O_RDWR|os.O_NOFOLLOW|os.O_CLOEXEC)
        try:
            st=os.fstat(fd)
            s.require(stat.S_ISREG(st.st_mode) and st.st_uid==os.geteuid() and stat.S_IMODE(st.st_mode)==0o600,'virtual device metadata')
            s.require(st.st_size==layout['size'],'virtual device size')
            self.identity=(st.st_dev,st.st_ino)
        finally: os.close(fd)
    def bind(self,fd):
        st=os.fstat(fd); live=os.stat(self.path,follow_symlinks=False)
        s.require((st.st_dev,st.st_ino)==self.identity==(live.st_dev,live.st_ino),'virtual device replaced')
    def read(self,name):
        entry=self.layout['fields'][name]; fd=os.open(self.path,os.O_RDONLY|os.O_NOFOLLOW)
        try:
            self.bind(fd); b=os.pread(fd,entry['size'],entry['offset']); s.require(len(b)==entry['size'],'virtual read short'); return b
        finally: os.close(fd)
    def write(self,name,data):
        entry=self.layout['fields'][name]; s.require(len(data)<=entry['size'],'virtual field bound')
        fd=os.open(self.path,os.O_RDWR|os.O_NOFOLLOW)
        try:
            self.bind(fd); s.require(os.pwrite(fd,data,entry['offset'])==len(data),'virtual write short'); os.fsync(fd); self.bind(fd)
        finally: os.close(fd)
    def integer(self,name): return int.from_bytes(self.read(name),'little',signed=name not in ('admission_epoch',))
    def set_integer(self,name,value): self.write(name,value.to_bytes(self.layout['fields'][name]['size'],'little',signed=name not in ('admission_epoch',)))
    def baseline_matches(self,blobs):
        return all(self.read(field)[:len(blobs[name])]==blobs[name] for name,field in self.layout['payload_fields'].items() if not name.endswith('modes')) and self.integer('fbblank')==4 and self.integer('modewide')==0
    def read_payloads(self):
        blobs={n:self.read(f)[:s.SIZES[n] if n in s.SIZES else 460800] for n,f in self.layout['payload_fields'].items() if not n.endswith('modes')}
        blobs['fg.original.modes']=self.layout['original_modes'].encode(); return blobs
    @contextlib.contextmanager
    def recovery_lock(self):
        # Same actual flock inode held by production C prepare_backup/release.
        # Cooperative exclusion only: hostile inode replacement remains out of scope.
        s.require(self.writer_lock is not None,'explicit production writer lock required')
        p=pathlib.Path(self.writer_lock); d=s.open_dir(str(p.parent)); fd=None
        try:
            s.metadata(d,'dir',0o700)
            fd=os.open(p.name,os.O_RDWR|os.O_NONBLOCK|s.O_SAFE,dir_fd=d)
            s.metadata(fd,'file',0o600); fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
            original=os.fstat(fd); epoch=self.integer('admission_epoch'); context=dict(self.context)
            def guard():
                current=os.stat(p.name,dir_fd=d,follow_symlinks=False)
                path_current=os.stat(self.writer_lock,follow_symlinks=False)
                s.require(s.same_inode(original,current) and s.same_inode(original,path_current),'writer lock namespace changed')
                s.require(self.integer('owner_pid')==0,'virtual writer ownership changed during recovery')
                s.require(self.integer('admission_epoch')==epoch,'virtual epoch changed during recovery')
                s.require(self.context==context,'virtual context changed during recovery')
            guard(); yield guard; guard()
        finally:
            if fd is not None: os.close(fd)
            os.close(d)

    def restore_virtual(self,plan,hook,guard=None):
        s.require(callable(guard),'held writer-lock guard required'); guard()
        s.require(plan.status=='VERIFIED_HOST_BYTES_ONLY_PHYSICAL_REPLAY_BLOCKED','wrong restore plan')
        blobs=dict(plan.payloads); s.validate_payload(blobs)
        # Only the single proven simulated baseline is supported. No general replay invented.
        s.require(len(blobs['fg.original.pixels'])==460800 and blobs['fg.original.modes']==self.layout['original_modes'].encode(),'unsupported virtual baseline')
        s.require(self.integer('owner_pid')==0,'writer still owns virtual device')
        for n,field in self.layout['payload_fields'].items():
            if not n.endswith('modes'): hook.call('virtual-restore:'+field,self.write,field,blobs[n])
        hook.call('virtual-restore:blank',self.set_integer,'fbblank',4)
        hook.call('virtual-restore:mode',self.set_integer,'modewide',0)
        hook.call('virtual-restore:retired-maps',self.set_integer,'maps',0)
        hook.call('virtual-restore:readback',lambda:s.require(self.baseline_matches(blobs),'retained virtual readback mismatch'))

class CustodyBridge:
    def __init__(self,snapshots,journal,display,context=CTX,hook=None):
        self.snapshots=str(snapshots); self.journal=str(journal); self.display=display; self.context=dict(context); self.hook=hook or Hook(); self.a=None
        s.require(display.context==context,'asserted host context mismatch')
    def supervisor(self,hook=None):
        hw=m.MockHardware(self.context,self.display.read_payloads()); hw.epoch=self.display.integer('admission_epoch')
        return m.Supervisor(self.journal,self.snapshots,self.context,hw,hook or self.hook)
    def admit(self,prefix,sock):
        try:
            blobs=self.hook.call('bridge:read-production-snapshot',read_blobs,prefix)
            s.require(self.display.baseline_matches(blobs),'live synthetic baseline differs from captured bytes')
            receipt=s.Store(self.snapshots,self.hook).capture(blobs,self.context)
            self.hook.call('bridge:receipt-returned',lambda:None)
            self.a=self.supervisor()
            epoch=self.a.start(receipt)
            s.require(self.a.j.last['kind']=='INTENT' and self.a.j.last['epoch']==epoch,'missing intent')
            self.hook.call('bridge:set-admission-epoch',self.display.set_integer,'admission_epoch',epoch)
            self.hook.call('bridge:send-admit',sock.sendall,b'ADMIT\n')
            return receipt,epoch
        except BaseException:
            # Never retry a failed/uncertain send on this connection. Its C writer is retired by caller.
            raise
        finally:
            if self.a: self.a.close(); self.a=None
    def recover(self):
        with self.display.recovery_lock() as guard:
            outer=self.hook
            class BoundHook:
                def call(self,label,fn,*args,**kw):
                    def checked():
                        guard(); result=fn(*args,**kw); guard(); return result
                    return outer.call(label,checked)
            hook=BoundHook()
            self.a=self.supervisor(hook)
            try:
                last=self.a.j.last
                s.require(last and last['kind'] in ('PREPARED','INTENT'),'no pending trusted receipt')
                receipt=m.receipt_from(last['receipt'])
                plan=s.Store(self.snapshots).recover(receipt,self.context)
                hook.call('bridge:verified-plan',lambda:None)
                self.display.restore_virtual(plan,hook,guard)
                # Actual retained bytes and ownership are checked before every
                # supervisor action/record, through the RECOVERED durable return.
                guard(); self.a.recover(); guard()
                s.require(self.display.baseline_matches(dict(plan.payloads)),'post-commit virtual readback mismatch')
                return plan
            finally: self.a.close(); self.a=None
