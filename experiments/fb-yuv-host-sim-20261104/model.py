"""Deterministic fake-device lifecycle model, never a framebuffer adapter.

Driver return codes, software notifications, physical reads and trusted model
witnesses are separate events. Witnesses below have NO real target equivalent.
"""
from dataclasses import asdict, dataclass
from pathlib import Path
import hashlib
import json
import os

from layouts import LayoutError, copy_to_fb_slot


class Rejected(RuntimeError): pass
class Hazard(RuntimeError): pass


class Trace:
    def __init__(self): self.events=[]
    def emit(self,event,**fields):
        self.events.append({'step':len(self.events),'event':event,**fields})


@dataclass(frozen=True)
class Token:
    epoch: int
    slot: int
    version: int
    allocation_generation: int


@dataclass(frozen=True)
class Witness:
    kind: str
    token: object
    nonce: int


@dataclass
class Slot:
    state: str='WRITABLE'
    token: object=None
    version: int=0
    cache_ready: bool=False


TARGET_CAPABILITIES = {
    'format_and_layout_acceptance':False,
    'cache_visibility_contract':False,
    'frame_specific_retirement':False,
    'complete_error_propagation':False,
    'exact_restore_contract':False,
    'oem_display_ownership':False,
}


class ModelDevice:
    def __init__(self, trace, baseline_length=460800, base=0x18580000):
        self.trace=trace
        self.base=base;self.generation=1
        self.memory=bytearray([0x5a])*baseline_length
        self.visible_memory=bytearray(self.memory)
        self.state={'width':240,'height':320,'virtual_width':240,'virtual_height':960,
                    'bpp':16,'format':'RGB565','stride':480,'activate':0x80,
                    'com':4,'gw':0x80000000,'position':0,'fg_mode':4,'bg_mode':2,
                    'coefficient_signature':'original-matrix','cached_blank':4,'enabled':False}
        self.baseline_memory=bytes(self.memory)
        self.baseline=self.snapshot()
        self.layout=None;self.pending=None;self.current=None;self.readers=set()
        self.cache_tokens=set();self.nonce=0;self.witnesses={}
        self.bad_frames=0;self.hazards=0

    def snapshot(self):
        return {'allocation':{'base':self.base,'length':len(self.memory),'generation':self.generation},
                'mapping_generation':self.generation,'display':dict(self.state),
                'pixel_sha256':hashlib.sha256(self.memory).hexdigest()}

    def configure(self,layout):
        if self.current or self.pending or self.readers:
            raise Rejected('cannot reconfigure while fake scanout may read')
        if layout.total_bytes > len(self.memory):
            old=(self.base,len(self.memory))
            self.base=0x18a00000+(self.generation-1)*0x01000000
            self.generation+=1
            self.memory=bytearray(layout.total_bytes)
            self.visible_memory=bytearray(self.memory)
            self.trace.emit('allocation_replaced',old_base=old[0],old_length=old[1],
                            new_base=self.base,new_length=len(self.memory),generation=self.generation)
        self.layout=layout
        self.state.update(width=layout.width,height=layout.height,virtual_width=layout.virtual_width,
                          virtual_height=layout.height*layout.slots,bpp=12,format=layout.format,
                          stride=layout.fix_line_length,fg_mode=1,coefficient_signature='YUV-matrix',
                          com=0x304,cached_blank=0)
        self.trace.emit('model_mode_configured',format=layout.format,bytes=layout.total_bytes)

    def cpu_write_guard(self,slot):
        if any(t.slot==slot and t.allocation_generation==self.generation for t in self.readers):
            self.hazards+=1;self.trace.emit('cpu_write_while_hardware_reads',slot=slot)
            raise Hazard('physical slot still has a fake hardware reader')

    def publish_cache(self,token):
        self.cpu_write_guard(token.slot)
        begin=token.slot*self.layout.slot_bytes;end=begin+self.layout.slot_bytes
        self.visible_memory[begin:end]=self.memory[begin:end]
        self.cache_tokens.add(token)
        self.trace.emit('model_cache_visibility_ack',token=asdict(token))

    def queue(self,token,offset_valid=True):
        if self.pending is not None: raise Rejected('one fake pending frame at a time')
        self.pending=(token,offset_valid)
        self.trace.emit('hardware_pending',token=asdict(token),offset_valid=offset_valid)

    def latch(self):
        if not self.pending: raise Rejected('no pending frame')
        token,offset_valid=self.pending;self.pending=None
        old=self.current;self.current=token;self.readers.add(token)
        # Old readers intentionally remain until the independent last-read event.
        self.state['enabled']=True;self.state['com']|=1
        valid=offset_valid and token in self.cache_tokens
        if not valid:
            self.bad_frames+=1
            self.trace.emit('bad_frame_can_be_visible',token=asdict(token),offset_valid=offset_valid,
                            cache_visible=token in self.cache_tokens)
        self.trace.emit('hardware_latched',token=asdict(token),old=asdict(old) if old else None,
                        old_reads_can_continue=old in self.readers,valid=valid)
        return token,old,valid

    def _witness(self,kind,token):
        self.nonce+=1;w=Witness(kind,token,self.nonce);self.witnesses[self.nonce]=w
        return w

    def last_read(self,token):
        if token==self.current or token not in self.readers:
            raise Rejected('a current/nonreading frame cannot be certified retired')
        self.readers.remove(token)
        self.trace.emit('model_last_read_completed',token=asdict(token))
        return self._witness('retired',token)

    def verify_retirement(self,w):
        return (isinstance(w,Witness) and self.witnesses.get(w.nonce)==w and w.kind=='retired'
                and w.token not in self.readers and w.token!=self.current)

    def global_stop_oracle(self):
        self.pending=None;self.current=None;self.readers.clear();self.state['enabled']=False
        self.trace.emit('model_global_dma_stop_ack_NO_REAL_API')
        return self._witness('global-stop',self.generation)

    def verify_stop(self,w):
        return (isinstance(w,Witness) and self.witnesses.get(w.nonce)==w and w.kind=='global-stop'
                and w.token==self.generation and not self.current and not self.pending and not self.readers)

    def restore_geometry_and_pixels(self,restore_matrix=False,force_activate=False):
        if self.readers or self.current or self.pending:
            raise Rejected('restoration cannot write while fake DMA may read')
        # Models a driver keeping a grown allocation: no guessed old-address write.
        matrix=self.state['coefficient_signature']
        self.state=dict(self.baseline['display'])
        if not restore_matrix:self.state['coefficient_signature']=matrix
        if force_activate:self.state['activate']=(self.state['activate'] & ~0x8f)|0x80
        self.memory[:len(self.baseline_memory)]=self.baseline_memory
        self.visible_memory=bytearray(self.memory)
        self.trace.emit('mode_pixels_restored_allocation_not_forced',base=self.base,length=len(self.memory),
                        matrix_restored=restore_matrix)


class DriverModel:
    def __init__(self,device):
        self.device=device;self.trace=device.trace;self.completion_credit=1;self.software_index=2

    def irq(self,label='unmatched-notification'):
        self.completion_credit+=1
        self.trace.emit('irq_notification_only',label=label,credit=self.completion_credit)

    def pan(self,token,offset_error=False,select_error=False,address_error=False,wait_timeout=False):
        if wait_timeout or not self.completion_credit:
            self.trace.emit('pan_wait_timeout',rc=-110);return -110
        self.completion_credit-=1
        self.software_index=(self.software_index+1)%3
        if address_error:
            self.software_index=(self.software_index+2)%3
            self.trace.emit('pan_address_error',rc=-16,software_index=self.software_index);return -16
        if offset_error:self.trace.emit('offset_error_swallowed',lower_rc=-13)
        if select_error:self.trace.emit('select_error_swallowed',lower_rc=-22)
        else:self.device.queue(token,offset_valid=not offset_error)
        self.trace.emit('pan_returned',rc=0,token=asdict(token),current_is_new=self.device.current==token)
        return 0

    def blank(self,disable_stuck=False):
        self.device.state['cached_blank']=4
        if not disable_stuck:
            # A software request only. It deliberately does not mint a stop witness.
            self.trace.emit('blank_requested_no_dma_ack',rc=0)
        else:self.trace.emit('blank_hidden_disable_failure',rc=0,lower_rc=-110)
        return 0


def differing_fields(a,b,prefix=''):
    result=[]
    for key in sorted(set(a)|set(b)):
        name=prefix+key
        if isinstance(a.get(key),dict) and isinstance(b.get(key),dict):
            result+=differing_fields(a[key],b[key],name+'.')
        elif a.get(key)!=b.get(key):result.append(name)
    return result


class Controller:
    def __init__(self,device,driver,capabilities=None,journal=None,epoch=1):
        self.device=device;self.driver=driver;self.trace=device.trace
        self.capabilities=dict(TARGET_CAPABILITIES if capabilities is None else capabilities)
        self.journal=Path(journal) if journal else None
        self.epoch=epoch;self.phase='DISABLED';self.slots=[];self.denied=0;self.expected_allocation=None
        if self.journal and self.journal.exists():
            try:
                saved=json.loads(self.journal.read_text())
                if (not isinstance(saved,dict) or type(saved.get('version')) is not int
                    or saved.get('version')!=1 or saved.get('status')!='CLEAN'
                    or not isinstance(saved.get('epoch'),int) or isinstance(saved.get('epoch'),bool)
                    or saved['epoch']<1
                    or saved.get('snapshot')!=self.device.snapshot()):
                    raise ValueError('unclean or unmatched journal')
                self.epoch=saved['epoch']+1
            except (ValueError,OSError,TypeError):
                self.phase='IRRECOVERABLE_UNCONFIRMED'
                self.trace.emit('restart_poisoned')

    def persist(self,status):
        if not self.journal:return
        data={'version':1,'status':status,'epoch':self.epoch,'snapshot':self.device.snapshot()}
        temp=self.journal.with_name(self.journal.name+'.pending')
        with temp.open('w') as f:
            json.dump(data,f,sort_keys=True);f.write('\n');f.flush();os.fsync(f.fileno())
        os.replace(temp,self.journal)
        self.trace.emit('journal_written',status=status)

    def reject(self,reason):
        self.denied+=1;self.trace.emit('operation_rejected',phase=self.phase,reason=reason)
        raise Rejected(reason)

    def activate(self,layout,allow_growth_negative_control=False):
        if self.phase!='DISABLED':return self.reject('activation is not a retry/recovery operation')
        absent=[key for key in TARGET_CAPABILITIES if not self.capabilities.get(key)]
        if absent:
            self.trace.emit('target_route_disabled',missing=absent);return False
        original_identity=(self.device.base,len(self.device.memory),self.device.generation)
        if layout.total_bytes>len(self.device.memory) and not allow_growth_negative_control:
            self.trace.emit('activation_refused_would_reallocate',required=layout.total_bytes,
                            available=len(self.device.memory));return False
        self.persist('DIRTY')
        self.device.configure(layout)
        if original_identity!=(self.device.base,len(self.device.memory),self.device.generation) and not allow_growth_negative_control:
            self.phase='IRRECOVERABLE_UNCONFIRMED';self.persist('DIRTY')
            return self.reject('mode change unexpectedly replaced original allocation')
        self.expected_allocation=(self.device.base,len(self.device.memory),self.device.generation)
        self.slots=[Slot() for _ in range(layout.slots)]
        self.phase='READY';self.trace.emit('host_model_only_activated')
        return True

    def _active(self):
        if self.phase not in ('READY','IN_FLIGHT'):self.reject('session is conservatively stopped')
        if self.expected_allocation!=(self.device.base,len(self.device.memory),self.device.generation):
            self.phase='IRRECOVERABLE_UNCONFIRMED';self.persist('DIRTY')
            self.reject('allocation/mapping identity changed')

    def stage(self,source,index):
        self._active()
        if not 0<=index<len(self.slots):self.reject('slot outside model')
        slot=self.slots[index]
        if slot.state not in ('WRITABLE','RETIRED'):self.reject('slot has no reuse proof')
        self.device.cpu_write_guard(index)
        # Persist uncertainty before any framebuffer bytes are written.
        self.persist('DIRTY')
        try:report=copy_to_fb_slot(source,self.device.memory,self.device.layout,index,self.device.base)
        except LayoutError:
            self.quarantine('source/destination layout failed before mutation')
            raise
        slot.version+=1
        slot.token=Token(self.epoch,index,slot.version,self.device.generation)
        slot.state='READY';slot.cache_ready=False
        self.trace.emit('copied_to_owned_slot',token=asdict(slot.token),**report)
        return slot.token

    def publish_cache(self,token):
        self._active();slot=self._slot(token)
        if slot.state!='READY':self.reject('cache handoff outside ready slot')
        self.device.publish_cache(token);slot.cache_ready=True

    def _slot(self,token):
        if not isinstance(token,Token) or not 0<=token.slot<len(self.slots):self.reject('unknown token')
        slot=self.slots[token.slot]
        if slot.token!=token or token.epoch!=self.epoch or token.allocation_generation!=self.device.generation:
            self.reject('stale epoch/version/allocation token')
        return slot

    def submit(self,token,**faults):
        self._active();slot=self._slot(token)
        if slot.state!='READY' or not slot.cache_ready:self.reject('slot/cache not ready')
        self.persist('DIRTY')
        try:rc=self.driver.pan(token,**faults)
        except Rejected:
            self.quarantine('partial submission outcome is uncertain')
            raise
        if rc:self.quarantine('nonzero pan result does not prove cancellation');return rc
        slot.state='QUEUED';self.phase='IN_FLIGHT'
        return rc

    def observe_latch(self,result):
        self._active()
        token,old,valid=result;slot=self._slot(token)
        if not valid:self.quarantine('hardware may already have displayed an invalid frame');return
        slot.state='CURRENT'
        if old:
            old_slot=self._slot(old);old_slot.state='RETIRING'
        self.trace.emit('model_latch_observed_NO_REAL_API',token=asdict(token))

    def accept_retirement(self,w):
        self._active()
        if not self.device.verify_retirement(w):self.reject('no trusted model retirement witness')
        slot=self._slot(w.token)
        if slot.state!='RETIRING':self.reject('witness does not retire the expected slot state')
        slot.state='RETIRED';self.phase='READY'
        self.trace.emit('model_retirement_accepted_NO_REAL_API',token=asdict(w.token))

    def quarantine(self,reason):
        self.phase='QUARANTINED'
        for slot in self.slots:
            if slot.state not in ('WRITABLE','RETIRED'):slot.state='QUARANTINED'
        self.persist('DIRTY');self.trace.emit('sticky_quarantine',reason=reason)

    def crash(self,who):
        self.phase='IRRECOVERABLE_UNCONFIRMED'
        self.trace.emit('simulated_owner_crash',who=who,physical_readers=len(self.device.readers))
        # No pretend cleanup, device stop, fd semantics or durability proof.

    def attempt_restore(self,stop_witness=None,restore_matrix=False,force_activate=False):
        if not self.device.verify_stop(stop_witness):
            self.phase='IRRECOVERABLE_UNCONFIRMED';self.persist('DIRTY')
            return self.reject('blank/timeout/exit is not a global DMA-stop witness')
        self.device.restore_geometry_and_pixels(restore_matrix,force_activate)
        diff=differing_fields(self.device.baseline,self.device.snapshot())
        if diff:
            self.phase='IRRECOVERABLE_UNCONFIRMED';self.persist('RESTORE_MISMATCH')
            self.trace.emit('restore_rejected',different_fields=diff)
            return {'exact':False,'different_fields':diff}
        self.phase='RESTORED_EXACT';self.persist('CLEAN')
        self.trace.emit('model_exact_restore_verified_NO_REAL_CONTRACT')
        return {'exact':True,'different_fields':[]}


def model_capabilities():
    """For positive controls only: trusted fake events, never target evidence."""
    return {name:True for name in TARGET_CAPABILITIES}
