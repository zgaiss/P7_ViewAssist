#!/usr/bin/env python3
"""Host-only byte-copy and fault model suite. Output directory must be new."""
from collections import deque
from dataclasses import asdict, replace
from pathlib import Path
import ast
import hashlib
import json
import sys
import traceback

from layouts import (FBLayout, LayoutError, align, copy_to_fb_slot, make_vpu_surface,
                     pattern, sample_fb)
from model import (Controller, DriverModel, Hazard, ModelDevice, Rejected, Token,
                   Trace, Witness, differing_fields, model_capabilities)


ROOT=Path(__file__).resolve().parent
SMALL=FBLayout(16,8,16)


def sha(data):return hashlib.sha256(data).hexdigest()


def reject(call,exc=Rejected):
    try:call()
    except exc:return
    raise AssertionError('operation unexpectedly accepted')


def environment(layout=SMALL,journal=None,target=False,growth_negative_control=False):
    trace=Trace();device=ModelDevice(trace);driver=DriverModel(device)
    controller=Controller(device,driver,None if target else model_capabilities(),journal)
    accepted=controller.activate(layout,allow_growth_negative_control=growth_negative_control)
    assert accepted != target
    return trace,device,driver,controller


def frame(controller,slot=0,seed=1):
    layout=controller.device.layout
    source=make_vpu_surface(layout.width,layout.height,seed)
    token=controller.stage(source,slot)
    controller.publish_cache(token)
    return token


def displayed(controller,slot=0,seed=1,notify=True):
    token=frame(controller,slot,seed)
    assert controller.submit(token)==0
    controller.observe_latch(controller.device.latch())
    if notify:controller.driver.irq('unassociated display event')
    return token


def visible_pixels_match(source,data,layout,slot):
    count=0
    for plane,scale in [('Y',1),('U',2),('V',2)]:
        for row in range(source.height//scale):
            actual=bytes(sample_fb(data,layout,slot,plane,col,row) for col in range(source.width//scale))
            expected=bytes(pattern(plane,source.crop_x//scale+col,source.crop_y//scale+row,source.seed)
                           for col in range(source.width//scale))
            assert actual==expected,(layout,plane,row)
            count+=len(actual)
    return count


def copy_matrix(_):
    shapes=[(320,180,None,None,None),(1280,720,None,None,None),
            (640,360,656,368,(16,16,320,180)),(64,48,80,64,None),(30,18,None,None,None)]
    trials=[];compared=0
    for w,h,stride,ah,crop in shapes:
        source=make_vpu_surface(w,h,seed=37,stride=stride,allocation_height=ah,crop=crop)
        original=sha(source.data)
        for fmt in ['I420','YV12','NV12']:
            pitch=align(source.width,16)+16
            layout=FBLayout(source.width,source.height,pitch,fmt)
            for slot in ([1] if w==1280 else [0,1,2]):
                data=bytearray([0xcd])*(layout.total_bytes+64)
                result=copy_to_fb_slot(source,data,layout,slot)
                compared+=visible_pixels_match(source,data,layout,slot)
                lo=slot*layout.slot_bytes;hi=lo+layout.slot_bytes
                assert data[:lo]==bytes([0xcd])*lo
                assert data[hi:]==bytes([0xcd])*(len(data)-hi)
                # Padding differs from VPU sentinel, proving it was not copied as content.
                for row in range(layout.height):
                    a=lo+row*layout.virtual_width+layout.width
                    assert data[a:lo+(row+1)*layout.virtual_width]==bytes(layout.virtual_width-layout.width)
                if fmt=='NV12':
                    for row in range(layout.height//2):
                        a=lo+layout.y_bytes+row*layout.virtual_width+layout.width
                        assert data[a:lo+layout.y_bytes+(row+1)*layout.virtual_width]==bytes([128])*(layout.virtual_width-layout.width)
                else:
                    for plane in ['U','V']:
                        for row in range(layout.height//2):
                            a=lo+layout.offsets()[plane]+row*(layout.virtual_width//2)+layout.width//2
                            end=lo+layout.offsets()[plane]+(row+1)*(layout.virtual_width//2)
                            assert data[a:end]==bytes([128])*(end-a)
                assert sha(source.data)==original
                trials.append({**result,'source_coded':[w,h],'crop':crop,'source_sha256':original,
                               'slot_sha256':sha(data[lo:hi])})
    return {'trials':len(trials),'visible_samples_compared':compared,'details':trials}


def naive_contiguous_copy(_):
    source=make_vpu_surface(320,180,seed=29)
    layout=FBLayout(320,180,320)
    naive=bytearray(layout.total_bytes)
    naive[:layout.slot_bytes]=source.data[:layout.slot_bytes]
    wrong_u=sample_fb(naive,layout,0,'U',0,0)
    expected=pattern('U',0,0,source.seed)
    assert wrong_u!=expected
    assert (source.u_offset,source.v_offset)==(61440,76800)
    assert layout.offsets()=={'Y':0,'U':57600,'V':72000}
    return {'expected_counterexample':True,'source_offsets':[61440,76800],
            'fb_offsets':[57600,72000],'naive_first_u':wrong_u,'expected_first_u':expected}


def invalid_copy_is_atomic(_):
    source=make_vpu_surface(64,48)
    layout=FBLayout(64,48,64)
    bad=[('released',replace(source,leased=False),layout,0,0x20000000),
         ('cache_unknown',replace(source,cpu_visible=False),layout,0,0x20000000),
         ('odd_crop',replace(source,crop_x=1,width=62),layout,0,0x20000000),
         ('out_of_bounds_crop',replace(source,crop_x=2),layout,0,0x20000000),
         ('overlap',replace(source,u_offset=0),layout,0,0x20000000),
         ('truncated',replace(source,data=source.data[:source.v_offset+8]),layout,0,0x20000000),
         ('mismatch_size',source,FBLayout(64,46,64),0,0x20000000),
         ('bad_slot',source,layout,3,0x20000000),
         ('bad_alignment',source,layout,0,0x20000002)]
    checked=[]
    for name,s,l,slot,base in bad:
        target=bytearray([0xce])*(layout.total_bytes+64);before=sha(target)
        reject(lambda:copy_to_fb_slot(s,target,l,slot,base),LayoutError)
        assert sha(target)==before;checked.append(name)
    target=bytearray(100);before=sha(target)
    reject(lambda:copy_to_fb_slot(source,target,layout,0),LayoutError)
    assert sha(target)==before;checked.append('destination_short')
    for w,h in [(560,315),(319,180)]:reject(lambda:FBLayout(w,h,align(w,16)),LayoutError)
    return {'invalid_copies_rejected_without_destination_mutation':checked,
            'odd_geometry_cases_rejected':2,'scope':'Conservative host policy; not a driver rejection test.'}


def producer_reuse_after_copy(_):
    source=make_vpu_surface(320,180,seed=42);layout=FBLayout(320,180,320)
    target=bytearray(layout.total_bytes);copy_to_fb_slot(source,target,layout,2)
    before=sha(target);source.leased=False;source.data[:]=bytes(len(source.data))
    assert sha(target)==before
    reject(lambda:copy_to_fb_slot(source,target,layout,0),LayoutError)
    return {'producer_storage_reused':True,'owned_fb_copy_unchanged':True}


def target_gate(_):
    trace,d,driver,c=environment(target=True)
    assert c.phase=='DISABLED' and not c.slots and d.layout is None
    assert d.snapshot()==d.baseline
    reject(lambda:c.stage(make_vpu_surface(16,8),0))
    assert not any(e['event']=='pan_returned' for e in trace.events)
    return {'trace':trace,'target_route_disabled':True}


def normal_retirement(_):
    trace,d,driver,c=environment()
    old=displayed(c,0,1)
    new=frame(c,1,2);assert c.submit(new)==0
    reject(lambda:c.stage(make_vpu_surface(16,8),0))
    c.observe_latch(d.latch());driver.irq('new frame notification is not retirement')
    assert old in d.readers and d.current==new
    reject(lambda:c.stage(make_vpu_surface(16,8),0))
    witness=d.last_read(old);c.accept_retirement(witness)
    replacement=frame(c,0,3)
    assert replacement.version==old.version+1
    reject(lambda:c.accept_retirement(witness))
    reject(lambda:d.last_read(new))
    assert d.hazards==0
    return {'trace':trace,'retirement_requires_model_witness':True,'stale_witness_rejected':True}


def initial_completion(_):
    trace,d,driver,c=environment();token=frame(c)
    assert driver.completion_credit==1
    assert c.submit(token)==0
    assert d.current is None and d.pending is not None
    reject(lambda:c.stage(make_vpu_surface(16,8),0))
    reject(lambda:c.accept_retirement(Witness('retired',token,12345)))
    return {'trace':trace,'initial_credit_did_not_prove_display_or_retirement':True}


def unsafe_reuse(kind):
    def test(_):
        trace,d,driver,c=environment();old=displayed(c,0)
        token=frame(c,1,2);assert c.submit(token)==0
        if kind=='irq':driver.irq('stale/unassociated IRQ')
        if kind=='current-index':c.observe_latch(d.latch())
        assert old in d.readers
        # Deliberately naive policy decision. The independent fake-device guard
        # catches the imminent overlapping write; no actual hardware is present.
        reject(lambda:d.cpu_write_guard(old.slot),Hazard)
        assert d.hazards==1
        return {'trace':trace,'expected_counterexample':True,'unsafe_retirement_rule':kind,
                'logical_overlap_detected':True,'real_dma_or_host_uaf':False}
    return test


def hidden_offset_error(_):
    trace,d,driver,c=environment();token=frame(c)
    assert c.submit(token,offset_error=True)==0
    result=d.latch();assert not result[2]
    c.observe_latch(result)
    assert c.phase=='QUARANTINED' and d.bad_frames==1
    reject(lambda:c.stage(make_vpu_surface(16,8),1))
    return {'trace':trace,'expected_counterexample':True,'bad_frame_may_precede_detection':True,
            'rc_zero_did_not_prove_valid_offsets':True}


def hidden_select_error(_):
    trace,d,driver,c=environment();old=displayed(c)
    new=frame(c,1,2);assert c.submit(new,select_error=True)==0
    assert d.current==old and d.pending is None
    c.quarantine('new frame did not obtain a trusted model latch observation')
    reject(lambda:c.stage(make_vpu_surface(16,8),1))
    return {'trace':trace,'zero_rc_without_new_frame':True,'old_reader_preserved':old in d.readers}


def submission_failure(kind):
    def test(_):
        trace,d,driver,c=environment();old=displayed(c,notify=kind!='timeout')
        token=frame(c,1,2)
        rc=c.submit(token,**({'address_error':True} if kind=='address' else {}))
        assert rc==(-16 if kind=='address' else -110)
        assert c.phase=='QUARANTINED' and old in d.readers
        reject(lambda:c.stage(make_vpu_surface(16,8),2))
        return {'trace':trace,'rc':rc,'quarantine_did_not_pretend_dma_stopped':True}
    return test


def cache_missing(_):
    trace,d,driver,c=environment();token=c.stage(make_vpu_surface(16,8),0)
    reject(lambda:c.submit(token));assert d.pending is None
    # Negative control bypasses policy to expose why CPU bytes alone are insufficient.
    assert driver.pan(token)==0
    result=d.latch();assert not result[2] and d.bad_frames==1
    c.observe_latch(result);assert c.phase=='QUARANTINED'
    return {'trace':trace,'expected_counterexample':True,'policy_rejected_missing_cache_ack':True,
            'bypass_exposed_stale_device_view':True}


def late_retirement(_):
    trace,d,driver,c=environment();old=displayed(c)
    new=displayed(c,1,2)
    c.quarantine('watchdog deadline, no cancellation assumed')
    witness=d.last_read(old)
    reject(lambda:c.accept_retirement(witness))
    driver.irq('late IRQ')
    reject(lambda:c.stage(make_vpu_surface(16,8),0))
    assert c.phase=='QUARANTINED' and new in d.readers
    return {'trace':trace,'late_events_did_not_clear_quarantine':True}


def crash_restart(who):
    def test(directory):
        trace,d,driver,c=environment(journal=directory/'journal.json')
        token=displayed(c);c.crash(who)
        restarted=Controller(d,driver,model_capabilities(),directory/'journal.json')
        assert restarted.phase=='IRRECOVERABLE_UNCONFIRMED' and token in d.readers
        reject(lambda:restarted.activate(SMALL))
        reject(lambda:restarted.attempt_restore())
        return {'trace':trace,'actor_crash_is_simulated_event':who,'restart_poisoned':True,
                'model_hardware_outlived_owner':True,'real_keeper_crash_safety_proven':False}
    return test


def corrupt_journals(directory):
    trace=Trace();checked=[]
    snapshot=ModelDevice(Trace()).snapshot()
    values=['{bad', '[]', '{}', '{"version":1,"status":"CLEAN","epoch":true}',
        '{"version":1,"status":"CLEAN","epoch":1,"snapshot":{}}']
    values += [json.dumps({'version':version,'status':'CLEAN','epoch':epoch,'snapshot':snapshot})
               for version,epoch in [(True,1),(1,0),(1,-3)]]
    for i,value in enumerate(values):
        path=directory/('bad-'+str(i)+'.json');path.write_text(value)
        d=ModelDevice(trace);driver=DriverModel(d);c=Controller(d,driver,model_capabilities(),path)
        assert c.phase=='IRRECOVERABLE_UNCONFIRMED'
        reject(lambda:c.activate(SMALL));checked.append(i)
    return {'trace':trace,'invalid_or_unmatched_journals_poisoned':len(checked)}


def exact_restore(directory):
    trace,d,driver,c=environment(journal=directory/'journal.json')
    displayed(c);c.quarantine('test end')
    result=c.attempt_restore(d.global_stop_oracle(),restore_matrix=True)
    assert result['exact'] and c.phase=='RESTORED_EXACT' and d.snapshot()==d.baseline
    saved=json.loads((directory/'journal.json').read_text());assert saved['status']=='CLEAN'
    restarted=Controller(d,driver,None,directory/'journal.json')
    assert restarted.epoch==c.epoch+1 and not restarted.activate(SMALL)
    return {'trace':trace,'positive_control_uses_trusted_fake_stop_and_full_matrix_restore':True,
            'exact_restore_in_model':True,'target_route_still_disabled':True}


def allocation_restore_mismatch(directory):
    layout=FBLayout(560,316,560)
    trace,d,driver,c=environment(layout,journal=directory/'journal.json',growth_negative_control=True)
    old=dict(d.baseline['allocation']);displayed(c);c.quarantine('test end')
    result=c.attempt_restore(d.global_stop_oracle(),restore_matrix=True)
    assert not result['exact'] and c.phase=='IRRECOVERABLE_UNCONFIRMED'
    assert 'allocation.base' in result['different_fields'] and 'allocation.length' in result['different_fields']
    assert d.state==d.baseline['display']
    assert d.memory[:len(d.baseline_memory)]==d.baseline_memory
    assert json.loads((directory/'journal.json').read_text())['status']=='RESTORE_MISMATCH'
    return {'trace':trace,'original_allocation':old,'restored_allocation':d.snapshot()['allocation'],
            'mode_and_original_pixel_prefix_equal':True,'exact_restore_rejected':result,
            'no_guess_write_to_original_physical_address':True}


def coefficient_restore_mismatch(_):
    trace,d,driver,c=environment();displayed(c)
    result=c.attempt_restore(d.global_stop_oracle(),restore_matrix=False)
    assert result['different_fields']==['display.coefficient_signature']
    assert c.phase=='IRRECOVERABLE_UNCONFIRMED'
    return {'trace':trace,'coefficient_mismatch_rejected':result}


def blank_success_without_stop(_):
    trace,d,driver,c=environment();token=displayed(c);before=sha(d.memory)
    assert driver.blank(disable_stuck=True)==0
    assert d.state['cached_blank']==4 and d.state['enabled'] and token in d.readers
    reject(lambda:c.attempt_restore())
    assert before==sha(d.memory) and token in d.readers
    return {'trace':trace,'cached_blank_and_rc_zero_did_not_authorize_restore':True}


def allocation_changed(_):
    trace,d,driver,c=environment();token=frame(c)
    d.base+=0x100000;d.generation+=1
    reject(lambda:c.submit(token))
    assert c.phase=='IRRECOVERABLE_UNCONFIRMED' and d.pending is None
    return {'trace':trace,'stale_mapping_and_token_blocked_before_submission':True}


def copy_failure_quarantine(_):
    trace,d,driver,c=environment();source=make_vpu_surface(16,8);source.leased=False
    before=sha(d.memory)
    reject(lambda:c.stage(source,0),LayoutError)
    assert c.phase=='QUARANTINED' and sha(d.memory)==before
    return {'trace':trace,'copy_rejected_atomically_and_session_stopped':True}


def partial_copy_crash(directory):
    trace,d,driver,c=environment(journal=directory/'journal.json')
    source=make_vpu_surface(16,8,seed=51)
    staged=bytearray(len(d.memory));copy_to_fb_slot(source,staged,SMALL,0)
    c.persist('DIRTY');d.cpu_write_guard(0)
    d.memory[:64]=staged[:64]
    trace.emit('simulated_crash_after_partial_copy',bytes_written=64,slot_never_ready=True)
    c.crash('worker during copy')
    restarted=Controller(d,driver,model_capabilities(),directory/'journal.json')
    assert restarted.phase=='IRRECOVERABLE_UNCONFIRMED'
    assert d.pending is None and d.current is None and not d.cache_tokens
    reject(lambda:restarted.activate(SMALL))
    return {'trace':trace,'partial_bytes_really_written_to_host_bytearray':64,
            'no_partial_frame_published':True,'process_kill_not_executed':True}


def stale_epoch_witness(directory):
    trace,d,driver,c=environment(journal=directory/'journal.json')
    old=displayed(c);displayed(c,1,2)
    witness=d.last_read(old);c.accept_retirement(witness)
    assert c.attempt_restore(d.global_stop_oracle(),restore_matrix=True)['exact']
    restarted=Controller(d,driver,model_capabilities(),directory/'journal.json')
    assert restarted.epoch==2 and restarted.activate(SMALL)
    current=displayed(restarted,0,3)
    assert current.epoch!=old.epoch and d.verify_retirement(witness)
    reject(lambda:restarted.accept_retirement(witness))
    assert current in d.readers
    return {'trace':trace,'old_epoch_witness_did_not_retire_reused_slot_number':True}


def activate_restore_mismatch(_):
    trace=Trace();d=ModelDevice(trace)
    d.state['activate']=0;d.baseline=d.snapshot()
    driver=DriverModel(d);c=Controller(d,driver,model_capabilities());assert c.activate(SMALL)
    displayed(c)
    result=c.attempt_restore(d.global_stop_oracle(),restore_matrix=True,force_activate=True)
    assert result['different_fields']==['display.activate']
    assert c.phase=='IRRECOVERABLE_UNCONFIRMED'
    return {'trace':trace,'model_of_restore_path_with_unblank_activate_change':result}


def clean_journal_identity_changed(directory):
    trace,d,driver,c=environment(journal=directory/'journal.json')
    assert c.attempt_restore(d.global_stop_oracle(),restore_matrix=True)['exact']
    d.base+=0x100000;d.generation+=1
    restarted=Controller(d,driver,model_capabilities(),directory/'journal.json')
    assert restarted.phase=='IRRECOVERABLE_UNCONFIRMED'
    reject(lambda:restarted.activate(SMALL))
    return {'trace':trace,'clean_label_did_not_override_allocation_mismatch':True}


def restore_with_pending(_):
    trace,d,driver,c=environment();token=frame(c);assert c.submit(token)==0
    before=sha(d.memory);assert driver.blank()==0
    reject(lambda:c.attempt_restore())
    assert d.pending[0]==token and before==sha(d.memory)
    return {'trace':trace,'pending_submission_blocks_restore_without_stop_witness':True}


def pending_backpressure(_):
    trace,d,driver,c=environment();old=frame(c);assert c.submit(old)==0
    driver.irq('unassociated extra credit');new=frame(c,1,2)
    reject(lambda:c.submit(new))
    assert c.phase=='QUARANTINED' and d.pending[0]==old
    reject(lambda:c.stage(make_vpu_surface(16,8),2))
    return {'trace':trace,'uncertain_partial_submission_stays_quarantined':True}


def reject_reallocation_before_activation(directory):
    trace=Trace();d=ModelDevice(trace);driver=DriverModel(d)
    journal=directory/'journal.json'
    c=Controller(d,driver,model_capabilities(),journal)
    assert not c.activate(FBLayout(560,316,560))
    assert c.phase=='DISABLED' and d.snapshot()==d.baseline and not journal.exists()
    assert not any(e['event']=='allocation_replaced' for e in trace.events)
    return {'trace':trace,'candidate_needing_larger_allocation_refused_before_mutation':True}


def finite_model(policy):
    # (submitted, latched, old_reading, irq_seen, ack_seen, poisoned, reused)
    start=(False,False,True,False,False,False,False)
    todo=deque([start]);seen={start};parent={};edges=0
    while todo:
        state=todo.popleft();submitted,latched,reading,irq,ack,poison,reused=state
        candidates=[]
        if not submitted:candidates.append(('pan-return-zero',(True,latched,reading,irq,ack,poison,reused)))
        if submitted and not latched:candidates.append(('current-index-changes',(submitted,True,reading,irq,ack,poison,reused)))
        if not irq:candidates.append(('possibly-stale-irq',(submitted,latched,reading,True,ack,poison,reused)))
        if latched and reading:candidates.append(('independent-last-read',(submitted,latched,False,irq,ack,poison,reused)))
        if latched and not reading and not ack:candidates.append(('trusted-model-retire-ack',(submitted,latched,reading,irq,True,poison,reused)))
        if not poison:candidates.append(('timeout-or-owner-crash',(submitted,latched,reading,irq,ack,True,reused)))
        allowed={'trusted-ack':ack and not poison,'pan-return':submitted,
                 'irq':submitted and irq,'current-index':latched}[policy]
        if allowed and not reused:candidates.append(('reuse-old-slot',(submitted,latched,reading,irq,ack,poison,True)))
        for event,nxt in candidates:
            edges+=1
            if event=='reuse-old-slot' and reading:
                path=[event];cur=state
                while cur!=start:
                    cur,previous_event=parent[cur];path.append(previous_event)
                path.reverse()
                return {'policy':policy,'states_seen':len(seen),'edges_examined':edges,'counterexample':path}
            if nxt not in seen:seen.add(nxt);parent[nxt]=(state,event);todo.append(nxt)
    return {'policy':policy,'states_seen':len(seen),'edges_examined':edges,'counterexample':None}


def interleavings(_):
    results=[finite_model(p) for p in ['trusted-ack','pan-return','irq','current-index']]
    assert results[0]['counterexample'] is None
    assert all(r['counterexample'] for r in results[1:])
    return {'finite_state_results':results,'scope':'One old and one new frame; abstract Boolean events, not an exhaustive kernel/hardware proof.'}


CASES=[('copy-layout-matrix',copy_matrix),('naive-contiguous-copy-counterexample',naive_contiguous_copy),
       ('invalid-copy-atomic',invalid_copy_is_atomic),('producer-reuse-after-copy',producer_reuse_after_copy),
       ('target-evidence-gate',target_gate),('normal-retirement',normal_retirement),
       ('initial-completion-credit',initial_completion),
       ('unsafe-pan-return',unsafe_reuse('pan-return')),('unsafe-irq',unsafe_reuse('irq')),
       ('unsafe-current-index',unsafe_reuse('current-index')),
       ('hidden-offset-error',hidden_offset_error),('hidden-select-error',hidden_select_error),
       ('address-update-error',submission_failure('address')),('pan-wait-timeout',submission_failure('timeout')),
       ('missing-cache-handoff',cache_missing),('late-retirement-stays-quarantined',late_retirement),
       ('worker-crash-restart',crash_restart('worker')),('controller-crash-restart',crash_restart('controller')),
       ('keeper-crash-restart',crash_restart('last keeper')),('corrupt-journals',corrupt_journals),
       ('exact-restore-positive-control',exact_restore),('allocation-restore-mismatch',allocation_restore_mismatch),
       ('matrix-restore-mismatch',coefficient_restore_mismatch),('blank-success-no-stop',blank_success_without_stop),
       ('allocation-identity-changed',allocation_changed),('copy-failure-quarantine',copy_failure_quarantine),
       ('partial-copy-crash-restart',partial_copy_crash),('stale-epoch-retirement',stale_epoch_witness),
       ('activate-restore-mismatch',activate_restore_mismatch),('clean-journal-allocation-changed',clean_journal_identity_changed),
       ('restore-with-pending-submission',restore_with_pending),('pending-backpressure',pending_backpressure),
       ('reallocation-preflight-refusal',reject_reallocation_before_activation),
       ('finite-interleavings',interleavings)]


def main():
    name=sys.argv[1] if len(sys.argv)==2 else 'results'
    if not name.replace('-','').replace('_','').isalnum():raise SystemExit('simple new directory name required')
    out=ROOT/name;out.mkdir(exist_ok=False)
    source_hashes={p.name:sha(p.read_bytes()) for p in [ROOT/'layouts.py',ROOT/'model.py',ROOT/'run_suite.py']}
    tested=out/'tested-sources';tested.mkdir()
    for name,digest in source_hashes.items():
        data=(ROOT/name).read_bytes();assert sha(data)==digest
        (tested/name).write_bytes(data)
    for p in [ROOT/'layouts.py',ROOT/'model.py',ROOT/'run_suite.py']:ast.parse(p.read_text())
    records=[]
    for name,fn in CASES:
        directory=out/name;directory.mkdir()
        try:
            result=fn(directory);trace=result.pop('trace',None)
            if trace:
                (directory/'events.json').write_text(json.dumps(trace.events,indent=2)+'\n')
                result['events']=len(trace.events)
                result['logical_overlap_events']=sum(e['event']=='cpu_write_while_hardware_reads' for e in trace.events)
                result['bad_frame_events']=sum(e['event']=='bad_frame_can_be_visible' for e in trace.events)
            record={'case':name,'passed':True,**result}
        except Exception:
            record={'case':name,'passed':False,'error':traceback.format_exc()}
        (directory/'result.json').write_text(json.dumps(record,indent=2)+'\n')
        records.append(record)
    assert source_hashes=={n:sha((ROOT/n).read_bytes()) for n in source_hashes}
    (out/'source-hashes.json').write_text(json.dumps(source_hashes,indent=2)+'\n')
    summary={'classification':'HOST BYTE COPIES + DETERMINISTIC FAKE-DEVICE MODEL',
             'cases':len(records),'passed':sum(r['passed'] for r in records),
             'failed':sum(not r['passed'] for r in records),
             'expected_counterexample_cases':sum(bool(r.get('expected_counterexample')) for r in records),
             'logical_overlap_events_in_negative_controls':sum(r.get('logical_overlap_events',0) for r in records),
             'bad_frame_events_in_negative_controls':sum(r.get('bad_frame_events',0) for r in records),
             'source_hashes_unchanged_during_suite':True,'real_device_access':False,
             'real_process_SIGKILL_or_dma_executed':False,'target_route_enabled':False,
             'target_sync_or_restore_contract_proven':False,'keeper_crash_safety_resolved':False}
    (out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary,indent=2))
    for r in records:
        if not r['passed']:print(r['error'])
    return 0 if not summary['failed'] else 1


if __name__=='__main__':raise SystemExit(main())
