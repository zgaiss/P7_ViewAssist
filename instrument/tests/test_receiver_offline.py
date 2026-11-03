#!/usr/bin/env python3
"""HOST STUB integration only; no real decoder/display/device access.
Exercises production network parser, fork/setsid, pipes and lease restoration.
No claim that mocked restoration proves physical display recovery.
"""
from pathlib import Path
import json, os, signal, socket, struct, subprocess, tempfile, time
ROOT=Path(__file__).resolve().parents[2]
NATIVE=ROOT/'instrument/native'
def main():
    base=ROOT/'instrument/build';base.mkdir(parents=True,exist_ok=True)
    out=Path(tempfile.mkdtemp(prefix='guardian-host-',dir=base)); binary=out/'receiver-stub'
    # Short independent temp state path; no existing production /tmp directory touched.
    state_parent=Path(tempfile.mkdtemp(prefix='p7-guardian-host-'));state=state_parent/'state'
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-fsanitize=undefined','-DIC_VPU_ABI_CONFIRMED=1',f'-DIC_STATE_ROOT="{state}"','-I',str(NATIVE),str(NATIVE/'ic_receiver.c'),str(NATIVE/'ic_protocol.c'),str(Path(__file__).with_name('receiver_stubs.c')),'-o',str(binary)],check=True)
    key=bytes.fromhex('0123456789abcdef'*4);token=out/'token';token.write_text(key.hex());token.chmod(0o600)
    gate_binary=out/'receiver-gate-disabled'
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror',f'-DIC_STATE_ROOT="{state}"','-I',str(NATIVE),str(NATIVE/'ic_receiver.c'),str(NATIVE/'ic_protocol.c'),str(Path(__file__).with_name('receiver_stubs.c')),'-o',str(gate_binary)],check=True)
    gate=subprocess.run([str(gate_binary),'--parked-experimental-ack','127.0.0.1','19000',str(token),'1','--accept-fg-disable-origin-risk'],capture_output=True)
    assert gate.returncode==78 and not state.exists(),'default ABI gate must exit before state/hardware initialization'
    results=[{'case':'default-abi-gate','pass':True,'parent_exit':gate.returncode}]
    def run_case(name):
        log=out/(name+'.events');env=dict(os.environ,IC_TEST_LOG=str(log))
        with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
        p=subprocess.Popen([str(binary),'--parked-experimental-ack','127.0.0.1',str(port),str(token),'6','--accept-fg-disable-origin-risk'],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        s=None
        try:
            deadline=time.monotonic()+2
            while time.monotonic()<deadline:
                try:s=socket.create_connection(('127.0.0.1',port),timeout=.2);break
                except (ConnectionRefusedError,TimeoutError):time.sleep(.02)
            assert s is not None,'listener startup'
            s.settimeout(2)
            hello=b'P7IC0001'+key+struct.pack('>IIIII',1,0,0,1280,720)
            def events():return log.read_text().splitlines() if log.exists() else []
            def wait_for(event,seconds=2):
                until=time.monotonic()+seconds
                while time.monotonic()<until:
                    if event in events():return events()
                    time.sleep(.01)
                raise AssertionError(f'{name}: missing {event}: {events()}')
            if name=='reject-auth':
                bad=bytearray(hello);bad[8]^=1;s.sendall(bad);assert s.recv(1)==b'';assert not events();p.terminate()
            else:
                # Deliberately fragmented hello proves read_exact handles short reads.
                s.sendall(hello[:17]);s.sendall(hello[17:]);assert s.recv(1)==b'\x01'
                s.sendall(struct.pack('>IIQ',1,1,1)+b'a');wait_for('frame')
                if name=='eof':s.close();s=None;wait_for('restore');p.terminate()
                elif name=='hung-decoder':
                    s.sendall(struct.pack('>IIQ',1,1,2)+b'h');wait_for('busy');e=wait_for('restore');assert 'idle' not in e,'restore must not wait for blocked worker';p.terminate()
                elif name=='parent-death':
                    s.sendall(struct.pack('>IIQ',1,1,2)+b'h');wait_for('busy');p.kill();wait_for('restore')
            stdout,stderr=p.communicate(timeout=7)
            (out/(name+'.log')).write_bytes(stdout+stderr)
            results.append({'case':name,'pass':True,'events':events(),'parent_exit':p.returncode})
        finally:
            if s is not None:s.close()
            if p.poll() is None:p.kill();p.communicate(timeout=7)
    for case in ('reject-auth','eof','hung-decoder','parent-death'):run_case(case)
    report={'scope':'HOST STUBS ONLY; real display/VPU NOT executed','cases':results,'state_directory':str(state_parent)}
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2));print('guardian host tests PASS:',out)
if __name__=='__main__':main()
