"""Host-only sealed snapshot protocol. Contains no device access or restore executor.
Trust model: cooperative processes, owner-private directories, honest Linux fsync/flock.
Caller must keep the returned receipt outside the snapshot's mutable trust domain.
"""
import contextlib, ctypes, errno, fcntl, hashlib, json, os, re, stat
from dataclasses import dataclass

NAMES = ('fg.original.var','fg.original.fix','fg.original.bgvar','fg.original.bgfix',
         'fg.original.dp','fg.original.modes','fg.original.pixels')
SIZES = dict(zip(NAMES[:5], (160,68,160,68,12)))
MAX_PIXELS = 16*1024*1024
MAX_META = 4096
# Worst-case payload and both metadata records per permanently charged slot.
SLOT_BYTES = sum(SIZES.values()) + 64 + MAX_PIXELS + 2*MAX_META
FORMAT = 'p7-host-sealed-arm32-v1'
O_SAFE = os.O_NOFOLLOW | os.O_CLOEXEC

class Refused(Exception): pass

def require(ok, why):
    if not ok: raise Refused(why)

def canonical(obj): return json.dumps(obj, sort_keys=True, separators=(',',':'), ensure_ascii=True).encode()
def digest(data): return hashlib.sha256(data).hexdigest()
def strict_json(raw):
    def pairs(items):
        result = {}
        for k,v in items:
            require(k not in result, 'duplicate JSON key')
            result[k] = v
        return result
    try: obj = json.loads(raw, object_pairs_hook=pairs)
    except (ValueError, UnicodeError) as e: raise Refused('invalid JSON') from e
    require(canonical(obj) == raw, 'noncanonical JSON')
    return obj

def metadata(fd, kind, mode):
    s = os.fstat(fd)
    require((stat.S_ISDIR(s.st_mode) if kind == 'dir' else stat.S_ISREG(s.st_mode)), 'wrong type')
    require(s.st_uid == os.geteuid() and stat.S_IMODE(s.st_mode) == mode, 'owner/mode mismatch')
    if kind != 'dir': require(s.st_nlink == 1, 'hard link forbidden')
    return s

def open_dir(path):
    # Do not follow symlinks in any component, including ancestors.
    require(os.path.isabs(path) and os.path.normpath(path) == path, 'absolute normalized path required')
    fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY | O_SAFE)
    try:
        for part in path.split('/')[1:]:
            require(part not in ('','.','..'), 'invalid path')
            new = os.open(part, os.O_RDONLY | os.O_DIRECTORY | O_SAFE, dir_fd=fd)
            os.close(fd); fd = new
        return fd
    except BaseException:
        os.close(fd); raise

def read_file(dfd, name, mode, limit, sync=False):
    fd = os.open(name, os.O_RDONLY | os.O_NONBLOCK | O_SAFE, dir_fd=dfd)
    try:
        before = metadata(fd, 'file', mode)
        require(0 < before.st_size <= limit, 'file size out of bounds')
        data = bytearray()
        while len(data) <= limit:
            chunk = os.read(fd, min(65536,limit+1-len(data)))
            if not chunk: break
            data.extend(chunk)
        after = os.fstat(fd)
        require(len(data) == before.st_size and len(data) <= limit, 'truncated/oversized read')
        require((before.st_ino,before.st_dev,before.st_size,before.st_mtime_ns,before.st_ctime_ns) ==
                (after.st_ino,after.st_dev,after.st_size,after.st_mtime_ns,after.st_ctime_ns), 'changed during read')
        bound = os.stat(name, dir_fd=dfd, follow_symlinks=False)
        require((bound.st_dev,bound.st_ino) == (after.st_dev,after.st_ino), 'replaced during read')
        if sync: os.fsync(fd)
        return bytes(data)
    finally: os.close(fd)

def rename_noreplace(source, target, src_dir_fd, dst_dir_fd):
    # Linux-only prototype: fail closed if atomic no-replace rename is unavailable.
    libc = ctypes.CDLL(None, use_errno=True)
    fn = getattr(libc, 'renameat2', None)
    require(fn is not None, 'renameat2 unavailable')
    fn.argtypes = [ctypes.c_int,ctypes.c_char_p,ctypes.c_int,ctypes.c_char_p,ctypes.c_uint]
    fn.restype = ctypes.c_int
    if fn(src_dir_fd,source.encode(),dst_dir_fd,target.encode(),1) != 0:
        e=ctypes.get_errno(); raise OSError(e,os.strerror(e))

def same_inode(a,b): return (a.st_dev,a.st_ino)==(b.st_dev,b.st_ino)

def binding(path,root,lock=None,generation=None,dfd=None):
    check=open_dir(path)
    try: require(same_inode(os.fstat(check),os.fstat(root)), 'root replaced')
    finally: os.close(check)
    if lock is not None:
        require(same_inode(os.stat('LOCK',dir_fd=root,follow_symlinks=False),os.fstat(lock)), 'lock replaced')
    if generation is not None:
        require(same_inode(os.stat(generation,dir_fd=root,follow_symlinks=False),os.fstat(dfd)), 'generation replaced')


class IO:
    """Instrumentation seam; normal implementation just performs OS operations."""
    def call(self, label, fn, *args, **kw): return fn(*args, **kw)


def write_new(dfd, name, data, io, mode=0o400):
    require(type(data) is bytes and data, 'nonempty bytes required')
    fd = io.call('create:'+name, os.open, name, os.O_WRONLY|os.O_CREAT|os.O_EXCL|O_SAFE, 0o600, dir_fd=dfd)
    try:
        offset = 0
        while offset < len(data):
            try: n = io.call('write:'+name, os.write, fd, data[offset:])
            except InterruptedError: continue
            require(n > 0, 'zero write')
            offset += n
        io.call('chmod:'+name, os.fchmod, fd, mode)
        io.call('fsync:'+name, os.fsync, fd)
    finally:
        io.call('close:'+name, os.close, fd)


def validate_context(context):
    require(type(context) is dict and set(context) == {'device_id','boot_id','session_id','abi'}, 'context keys')
    require(all(type(v) is str and re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}',v) for v in context.values()), 'context value')
    require(context['abi'] == 'linux-fb-arm32-le', 'unsupported ABI')

def validate_payload(blobs):
    require(type(blobs) is dict and set(blobs) == set(NAMES), 'exactly seven payloads required')
    for name in NAMES:
        b = blobs[name]
        require(type(b) is bytes, 'immutable byte input required')
        if name in SIZES: require(len(b) == SIZES[name], 'ABI size mismatch')
        elif name.endswith('modes'): require(1 <= len(b) <= 64, 'mode size mismatch')
        else: require(1 <= len(b) <= MAX_PIXELS, 'pixel size mismatch')


def provision(path, slots, byte_budget):
    require(type(slots) is int and 1 <= slots <= 64, 'slot budget out of bounds')
    require(type(byte_budget) is int and slots*SLOT_BYTES <= byte_budget <= 64*SLOT_BYTES, 'byte budget insufficient')
    # Caller supplies a new leaf in a trusted pre-existing sandbox directory.
    parent,leaf = os.path.split(path)
    pfd = open_dir(parent)
    try:
        os.mkdir(leaf, 0o700, dir_fd=pfd)
        dfd = os.open(leaf, os.O_RDONLY|os.O_DIRECTORY|O_SAFE, dir_fd=pfd)
        try:
            write_new(dfd,'CONFIG',canonical({'format':FORMAT,'slots':slots,'bytes':byte_budget}),IO())
            write_new(dfd,'LOCK',b'owner-lock-v1\n',IO(),0o600)
            os.fsync(dfd)
        finally: os.close(dfd)
        os.fsync(pfd)
    finally: os.close(pfd)

@dataclass(frozen=True)
class Receipt:
    generation: str
    manifest_sha256: str
    context: dict

@dataclass(frozen=True)
class RecoveryPlan:
    generation: str
    payloads: tuple
    status: str = 'VERIFIED_HOST_BYTES_ONLY_PHYSICAL_REPLAY_BLOCKED'
    requirements: tuple = ('Independent physical ownership and OEM writer exclusion',
        'Live target identity/ABI/geometry and boot/session validation',
        'Vehicle-safe physical blank/readback protocol and rollback validation',
        'Hardware power-loss and watchdog recovery qualification')

class Store:
    def __init__(self,path,io=None): self.path=path; self.io=io or IO()
    @contextlib.contextmanager
    def locked(self):
        root=open_dir(self.path); lock=None
        try:
            metadata(root,'dir',0o700)
            lock=os.open('LOCK',os.O_RDWR|os.O_NONBLOCK|O_SAFE,dir_fd=root)
            metadata(lock,'file',0o600)
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            require(read_file(root,'LOCK',0o600,64)==b'owner-lock-v1\n','lock content')
            config=strict_json(read_file(root,'CONFIG',0o400,MAX_META))
            require(type(config) is dict and set(config)=={'format','slots','bytes'},'config keys')
            require(config['format']==FORMAT and type(config['slots']) is int and 1<=config['slots']<=64,'config version/slots')
            require(type(config['bytes']) is int and config['slots']*SLOT_BYTES<=config['bytes']<=64*SLOT_BYTES,'config byte budget')
            names=set(os.listdir(root)); require({'LOCK','CONFIG'}<=names,'missing root metadata')
            slots={}
            for name in names-{'LOCK','CONFIG'}:
                match=re.fullmatch(r'(pending|committed)-([0-9]{6})',name)
                require(match is not None,'unknown root entry')
                index=int(match[2]); require(index<config['slots'] and index not in slots,'invalid/duplicate slot')
                s=os.stat(name,dir_fd=root,follow_symlinks=False)
                require(stat.S_ISDIR(s.st_mode) and s.st_uid==os.geteuid() and stat.S_IMODE(s.st_mode) in (0o700,0o500),'invalid generation directory')
                fd=os.open(name,os.O_RDONLY|os.O_DIRECTORY|O_SAFE,dir_fd=root)
                try:
                    entries=set(os.listdir(fd))
                    require(entries <= set(NAMES)|{'MANIFEST','COMMIT'},'unknown prior snapshot entry')
                    for entry in entries:
                        fs=os.stat(entry,dir_fd=fd,follow_symlinks=False)
                        limit=(MAX_PIXELS if entry.endswith('pixels') else (64 if entry.endswith('modes') else SIZES.get(entry,MAX_META)))
                        require(stat.S_ISREG(fs.st_mode) and fs.st_uid==os.geteuid() and fs.st_nlink==1,'unsafe prior snapshot entry')
                        require(stat.S_IMODE(fs.st_mode) in (0o400,0o600) and 0<=fs.st_size<=limit,'prior snapshot exceeds bounds')
                finally: os.close(fd)
                slots[index]=name
            binding(self.path,root,lock)
            yield root,config,slots
            binding(self.path,root,lock)
        finally:
            if lock is not None: os.close(lock)
            os.close(root)

    def capture(self,blobs,context):
        validate_payload(blobs); validate_context(context)
        # Snapshot caller dictionaries so later caller mutation cannot alter metadata.
        blobs=dict(blobs); context=dict(context)
        with self.locked() as (root,config,slots):
            free=sorted(set(range(config['slots']))-set(slots))
            require(bool(free),'permanent snapshot budget exhausted')
            index=free[0]; pending=f'pending-{index:06d}'; final=f'committed-{index:06d}'
            io=self.io
            io.call('mkdir:reservation',os.mkdir,pending,0o700,dir_fd=root)
            io.call('fsync:reservation-root',os.fsync,root)
            dfd=os.open(pending,os.O_RDONLY|os.O_DIRECTORY|O_SAFE,dir_fd=root)
            try:
                for name in NAMES: write_new(dfd,name,blobs[name],io)
                manifest=canonical({'format':FORMAT,'generation':final,'context':context,
                    'files':{n:{'bytes':len(blobs[n]),'sha256':digest(blobs[n])} for n in NAMES}})
                require(len(manifest)<=MAX_META,'manifest budget')
                write_new(dfd,'MANIFEST',manifest,io)
                io.call('fsync:payload-directory',os.fsync,dfd)
                # Read back bytes before the commit record can exist.
                for name in NAMES: require(read_file(dfd,name,0o400,MAX_PIXELS)==blobs[name],'capture readback mismatch')
                require(read_file(dfd,'MANIFEST',0o400,MAX_META)==manifest,'manifest readback mismatch')
                write_new(dfd,'COMMIT',canonical({'format':FORMAT,'manifest_sha256':digest(manifest)}),io)
                io.call('chmod:generation',os.fchmod,dfd,0o500)
                io.call('fsync:commit-directory',os.fsync,dfd)
                # All cooperative writers hold LOCK; destination collision is forbidden.
                require(final not in os.listdir(root),'destination exists')
                binding(self.path,root,generation=pending,dfd=dfd)
                io.call('rename:publication',rename_noreplace,pending,final,src_dir_fd=root,dst_dir_fd=root)
                io.call('fsync:publication-root',os.fsync,root)
                binding(self.path,root,generation=final,dfd=dfd)
                return Receipt(final,digest(manifest),context)
            finally: os.close(dfd)

    def recover(self,receipt,live_context):
        # A caller-supplied trusted receipt is mandatory. On-disk hashes alone are not authenticity.
        require(type(receipt) is Receipt,'trusted external receipt required')
        validate_context(live_context); validate_context(receipt.context)
        require(receipt.context==live_context,'target/boot/session context mismatch')
        require(re.fullmatch(r'committed-[0-9]{6}',receipt.generation) is not None,'not committed generation')
        require(type(receipt.manifest_sha256) is str and re.fullmatch('[0-9a-f]{64}',receipt.manifest_sha256),'invalid trusted digest')
        with self.locked() as (root,config,slots):
            require(receipt.generation in slots.values(),'missing committed generation')
            dfd=os.open(receipt.generation,os.O_RDONLY|os.O_DIRECTORY|O_SAFE,dir_fd=root)
            try:
                metadata(dfd,'dir',0o500)
                require(set(os.listdir(dfd))==set(NAMES)|{'MANIFEST','COMMIT'},'incomplete/unknown generation entries')
                raw=read_file(dfd,'MANIFEST',0o400,MAX_META,sync=True)
                require(digest(raw)==receipt.manifest_sha256,'manifest differs from trusted receipt')
                manifest=strict_json(raw)
                require(type(manifest) is dict and set(manifest)=={'format','generation','context','files'},'manifest keys')
                require(manifest['format']==FORMAT and manifest['generation']==receipt.generation and manifest['context']==live_context,'manifest binding')
                commit=strict_json(read_file(dfd,'COMMIT',0o400,MAX_META,sync=True))
                require(commit=={'format':FORMAT,'manifest_sha256':receipt.manifest_sha256},'invalid commit')
                require(type(manifest['files']) is dict and set(manifest['files'])==set(NAMES),'manifest payload inventory')
                blobs={}
                for name in NAMES:
                    entry=manifest['files'][name]
                    require(type(entry) is dict and set(entry)=={'bytes','sha256'} and type(entry['bytes']) is int,'manifest entry')
                    raw=read_file(dfd,name,0o400,MAX_PIXELS if name.endswith('pixels') else 160,sync=True)
                    require(len(raw)==entry['bytes'] and digest(raw)==entry['sha256'],'payload integrity failure')
                    blobs[name]=raw
                validate_payload(blobs)
                # Re-synchronize validated data and namespace before emitting a host plan.
                # This only makes a current OS-level durability claim, never power-loss proof.
                binding(self.path,root,generation=receipt.generation,dfd=dfd)
                os.fsync(dfd); os.fsync(root)
                return RecoveryPlan(receipt.generation,tuple((n,blobs[n]) for n in NAMES))
            finally: os.close(dfd)
