"""Synthetic trusted-supervisor model. NO device opens, ioctl, mmap or subprocess.
The journal, mock process table and mock hardware are explicit trusted assumptions.
This is not deployed in ViewAssist and is not a same-UID security boundary.
"""
import fcntl, os, re, stat
from dataclasses import dataclass
import sealed as s

VERSION='p7-trusted-supervisor-simulation-v1'
MAX_EVENTS=192
MAX_RECORD=4096

def receipt_obj(r):
    return {'generation':r.generation,'manifest_sha256':r.manifest_sha256,'context':dict(r.context)}

def receipt_from(o):
    s.require(type(o) is dict and set(o)=={'generation','manifest_sha256','context'},'receipt fields')
    r=s.Receipt(o['generation'],o['manifest_sha256'],dict(o['context']))
    s.validate_context(r.context)
    s.require(type(r.generation) is str and re.fullmatch(r'committed-[0-9]{6}',r.generation),'receipt generation')
    s.require(type(r.manifest_sha256) is str and re.fullmatch('[0-9a-f]{64}',r.manifest_sha256),'receipt digest')
    return r

class Hook:
    def __init__(self): self.trace=[]
    def call(self,label,fn,*args,**kw):
        self.trace.append(label)
        return fn(*args,**kw)

class Journal:
    """No repair/delete/overwrite. Any torn record requires operator inspection.
    Limits are logical bytes, not filesystem allocation or hostile-owner quotas.
    """
    @staticmethod
    def provision(path,context,quota=MAX_EVENTS):
        s.validate_context(context)
        s.require(type(quota) is int and 3<=quota<=MAX_EVENTS,'journal quota')
        parent,leaf=os.path.split(path); p=s.open_dir(parent)
        try:
            os.mkdir(leaf,0o700,dir_fd=p); d=s.open_dir(path)
            try:
                s.write_new(d,'CONFIG',s.canonical({'version':VERSION,'context':dict(context),'quota':quota}),s.IO())
                s.write_new(d,'LOCK',b'supervisor-lock-v1',s.IO(),0o600); os.fsync(d)
            finally: os.close(d)
            os.fsync(p)
        finally: os.close(p)

    def __init__(self,path,context,hook=None):
        self.path=path; self.hook=hook or Hook(); self.d=None; self.lock=None; self.poisoned=False
        try:
            self.d=s.open_dir(path); s.metadata(self.d,'dir',0o700)
            self.lock=os.open('LOCK',os.O_RDWR|os.O_NONBLOCK|s.O_SAFE,dir_fd=self.d)
            s.metadata(self.lock,'file',0o600); fcntl.flock(self.lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            s.require(s.read_file(self.d,'LOCK',0o600,64)==b'supervisor-lock-v1','lock content')
            config=s.strict_json(s.read_file(self.d,'CONFIG',0o400,MAX_RECORD,sync=True))
            s.require(type(config) is dict and set(config)=={'version','context','quota'},'config keys')
            s.validate_context(context)
            s.require(config['version']==VERSION and config['context']==context,'journal context')
            s.require(type(config['quota']) is int and 3<=config['quota']<=MAX_EVENTS,'journal quota')
            self.context=dict(context); self.quota=config['quota']; self.events=[]
            names=set(os.listdir(self.d))-{'LOCK','CONFIG'}
            s.require(len(names)<=self.quota,'journal quota exceeded')
            s.require(names=={f'event-{i:06d}' for i in range(len(names))},'journal gap/extra entry')
            for i in range(len(names)):
                event=s.strict_json(s.read_file(self.d,f'event-{i:06d}',0o400,MAX_RECORD,sync=True))
                self.validate(event); self.events.append(event)
            os.fsync(self.d); self.bind()
        except BaseException:
            self.close(); raise

    def bind(self): s.binding(self.path,self.d,self.lock)
    @property
    def last(self): return self.events[-1] if self.events else None
    def validate(self,e):
        s.require(type(e) is dict and set(e)=={'seq','prev','epoch','kind','receipt'},'event fields')
        s.require(type(e['seq']) is int and e['seq']==len(self.events),'event sequence')
        prev=self.last
        s.require(e['prev']==(s.digest(s.canonical(prev)) if prev else '0'*64),'event chain')
        s.require(type(e['epoch']) is int and 1<=e['epoch']<=MAX_EVENTS,'epoch bounds')
        r=receipt_from(e['receipt']); s.require(r.context==self.context,'receipt context')
        if e['kind']=='PREPARED':
            s.require(prev is None or prev['kind']=='RECOVERED','unresolved prior epoch')
            s.require(e['epoch']==(prev['epoch']+1 if prev else 1),'epoch monotonicity')
        elif e['kind'] in ('INTENT','RECOVERED'):
            allowed=('PREPARED',) if e['kind']=='INTENT' else ('PREPARED','INTENT')
            s.require(prev is not None and prev['kind'] in allowed,'illegal transition')
            s.require(e['epoch']==prev['epoch'] and e['receipt']==prev['receipt'],'epoch/receipt changed')
        else: raise s.Refused('unknown event')

    def append(self,kind,epoch,r):
        s.require(not self.poisoned,'journal uncertain; restart required')
        s.require(len(self.events)<self.quota,'permanent journal quota exhausted')
        e={'seq':len(self.events),'prev':s.digest(s.canonical(self.last)) if self.last else '0'*64,
           'epoch':epoch,'kind':kind,'receipt':receipt_obj(r)}
        self.validate(e); raw=s.canonical(e); s.require(len(raw)<=MAX_RECORD,'record bound')
        name=f"event-{len(self.events):06d}"
        try:
            self.bind()
            s.write_new(self.d,name,raw,self.hook)
            self.hook.call('journal:directory-fsync',os.fsync,self.d)
            self.hook.call('journal:readback',lambda:s.require(s.read_file(self.d,name,0o400,MAX_RECORD,sync=True)==raw,'journal readback'))
            self.bind(); self.hook.call('journal:durable-return',lambda:None)
            self.events.append(e)
        except BaseException:
            self.poisoned=True; raise
        return e
    def close(self):
        if self.lock is not None: os.close(self.lock); self.lock=None
        if self.d is not None: os.close(self.d); self.d=None

@dataclass(frozen=True)
class MockFD:
    number:int
    owner:int
    process:tuple
    context:bytes = b''

class MockHardware:
    """Memory-only device with explicitly trusted monotonic fencing.
    An actual Linux framebuffer FD does NOT supply these fencing semantics.
    """
    def __init__(self,context,payloads,max_actions=512):
        s.require(type(max_actions) is int and 1<=max_actions<=4096,'mock action quota')
        self.max_actions=max_actions
        self.context=dict(context); self.payloads=dict(payloads); self.owner=0; self.epoch=0
        self.fds={}; self.nextfd=10; self.alive=set(); self.ledger=[]
    def rotate_owner(self):
        self.owner+=1; self.fds.clear(); self.alive.clear(); return self.owner
    def open(self,owner,process):
        s.require(owner==self.owner,'stale supervisor'); self.alive.add(process)
        fd=MockFD(self.nextfd,owner,process,s.canonical(self.context)); self.nextfd+=1; self.fds[fd.number]=fd; return fd
    def kill(self,process): self.alive.discard(process)
    def check(self,fd,epoch):
        s.require(self.fds.get(fd.number) is fd and fd.owner==self.owner and fd.process in self.alive,'stale/dead/reused mock FD')
        s.require(fd.context==s.canonical(self.context),'mock hardware context changed')
        s.require(type(epoch) is int and epoch>=self.epoch,'stale epoch')
    def foreground(self,fd,epoch):
        self.check(fd,epoch); s.require(epoch>self.epoch,'foreground epoch must advance')
        s.require(len(self.ledger)+2<=self.max_actions,'mock foreground requires recovery action reserve'); self.epoch=epoch
        self.payloads['fg.original.pixels']=b'VIRTUAL_FOREGROUND'
        self.ledger.append({'action':'virtual_foreground','epoch':epoch,'fd':fd.number})
    def restore(self,fd,epoch,plan):
        self.check(fd,epoch); s.require(len(self.ledger)<self.max_actions,'mock action quota exhausted')
        self.epoch=epoch; self.payloads=dict(plan.payloads)
        self.ledger.append({'action':'virtual_restore','epoch':epoch,'fd':fd.number})

class Supervisor:
    def __init__(self,journal_path,snapshot_path,context,hardware,hook=None):
        self.hook=hook or Hook(); self.j=Journal(journal_path,context,self.hook)
        self.store=s.Store(snapshot_path); self.context=dict(context); self.hw=hardware; self.closed=False
        try:
            s.require(type(hardware) is MockHardware and hardware.context==context,'mock target context')
            s.require(hardware.epoch<=(self.j.last['epoch'] if self.j.last else 0),'hardware epoch ahead of journal')
            self.owner=hardware.rotate_owner(); self.fd=hardware.open(self.owner,('supervisor',self.owner))
        except BaseException: self.j.close(); raise
    def check(self):
        s.require(not self.closed and not self.j.poisoned,'closed/uncertain supervisor')
        self.j.bind(); self.hw.check(self.fd,self.j.last['epoch'] if self.j.last else 0)
        s.require(self.hw.context==self.context,'live mock context changed')
    def start(self,receipt):
        self.check(); last=self.j.last
        s.require(last is None or last['kind']=='RECOVERED','recovery required')
        # Reserve the entire lifecycle so recovery cannot run out of journal slots.
        s.require(len(self.j.events)+3<=self.j.quota,'lifecycle quota exhausted')
        r=receipt_from(receipt_obj(receipt)); self.store.recover(r,self.context)
        epoch=last['epoch']+1 if last else 1
        self.j.append('PREPARED',epoch,r)
        self.j.append('INTENT',epoch,r)
        self.check()
        self.hook.call('hardware:before-foreground',lambda:None)
        self.hw.foreground(self.fd,epoch)
        self.hook.call('hardware:after-foreground',lambda:None)
        return epoch
    def recover(self):
        self.check(); last=self.j.last
        s.require(last is not None and last['kind'] in ('PREPARED','INTENT'),'no recovery pending')
        r=receipt_from(last['receipt']); plan=self.store.recover(r,self.context)
        self.check(); self.hook.call('hardware:before-restore',lambda:None)
        self.hw.restore(self.fd,last['epoch'],plan)
        self.hook.call('hardware:after-restore',lambda:None)
        self.check()
        self.hook.call('hardware:verify-restore',lambda:s.require(self.hw.payloads==dict(plan.payloads),'mock restore readback mismatch'))
        self.j.append('RECOVERED',last['epoch'],r)
        return plan
    def close(self):
        if not self.closed:
            self.hw.kill(self.fd.process); self.j.close(); self.closed=True
