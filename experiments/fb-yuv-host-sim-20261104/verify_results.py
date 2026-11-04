#!/usr/bin/env python3
"""Independently replay stored fake-device traces and verify delivery integrity."""
from pathlib import Path
import ast
import hashlib
import json
import sys


ROOT=Path(__file__).resolve().parent


def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()


def key(token):
    return tuple(token[n] for n in ['epoch','slot','version','allocation_generation'])


def main():
    name=sys.argv[1] if len(sys.argv)==2 else 'results-verified'
    if not name.replace('-','').replace('_','').isalnum():raise SystemExit('simple directory name required')
    out=ROOT/name
    summary=json.loads((out/'summary.json').read_text())
    hashes=json.loads((out/'source-hashes.json').read_text())
    for filename,digest in hashes.items():
        assert sha(out/'tested-sources'/filename)==digest
        assert sha(ROOT/filename)==digest,'Current sources differ from the tested snapshot'
        tree=ast.parse((ROOT/filename).read_text())
        imports=[]
        for node in ast.walk(tree):
            if isinstance(node,ast.Import):imports.extend(n.name.split('.')[0] for n in node.names)
            if isinstance(node,ast.ImportFrom):imports.append(node.module.split('.')[0])
        assert set(imports)<={'collections','dataclasses','pathlib','hashlib','json','sys','traceback','ast','os','layouts','model'}
    allowed_hazard={'unsafe-pan-return','unsafe-irq','unsafe-current-index'}
    allowed_bad={'hidden-offset-error','missing-cache-handoff'}
    count=events=copy_events=retirement_events=0
    cases=[]
    for path in sorted(out.glob('*/result.json')):
        result=json.loads(path.read_text());assert result['passed']
        case=path.parent.name;count+=1;tracefile=path.parent/'events.json'
        if not tracefile.exists():continue
        trace=json.loads(tracefile.read_text());readers=set();current=None;finished=set()
        for i,event in enumerate(trace):
            assert event['step']==i
            kind=event['event'];events+=1
            if kind=='hardware_latched':
                current=key(event['token']);readers.add(current)
            elif kind=='model_last_read_completed':
                token=key(event['token']);assert token!=current and token in readers
                readers.remove(token);finished.add(token)
            elif kind=='model_retirement_accepted_NO_REAL_API':
                token=key(event['token']);assert token in finished and token not in readers
                retirement_events+=1
            elif kind=='model_global_dma_stop_ack_NO_REAL_API':
                readers.clear();current=None
            elif kind=='copied_to_owned_slot':
                token=key(event['token'])
                assert not any(t[1]==token[1] and t[3]==token[3] for t in readers),case
                copy_events+=1
            elif kind=='cpu_write_while_hardware_reads':
                assert case in allowed_hazard
                assert any(t[1]==event['slot'] for t in readers)
            elif kind=='bad_frame_can_be_visible':assert case in allowed_bad
        assert len(trace)==result['events']
        cases.append({'case':case,'events':len(trace),'remaining_fake_readers':len(readers)})
    assert count==summary['cases']==34 and summary['passed']==34 and summary['failed']==0
    assert not summary['target_route_enabled'] and not summary['target_sync_or_restore_contract_proven']
    copies=json.loads((out/'copy-layout-matrix/result.json').read_text())
    assert copies['trials']==39 and copies['visible_samples_compared']==5751162
    finite=json.loads((out/'finite-interleavings/result.json').read_text())['finite_state_results']
    assert finite[0]['policy']=='trusted-ack' and finite[0]['states_seen']==24 and finite[0]['counterexample'] is None
    assert all(x['counterexample'] for x in finite[1:])
    result={'cases_verified':count,'trace_events_replayed':events,'safe_copy_events_replayed':copy_events,
            'model_retirement_acceptances_replayed':retirement_events,
            'expected_overlap_cases':sorted(allowed_hazard),'expected_bad_frame_cases':sorted(allowed_bad),
            'tested_source_hashes_match':True,'source_import_allowlist_checked':True,
            'real_target_safety_proven':False,'traces':cases}
    target=out/'trace-verification.json'
    with target.open('x') as f:json.dump(result,f,indent=2);f.write('\n')
    print(json.dumps({k:v for k,v in result.items() if k!='traces'},indent=2))


if __name__=='__main__':main()
