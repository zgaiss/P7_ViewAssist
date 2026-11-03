#!/usr/bin/env python3
"""Independent fresh-process read-only observer. Does not import bridge/supervisor."""
import hashlib,json,os,pathlib,sys
state=pathlib.Path(sys.argv[1]); layout=json.loads(pathlib.Path(sys.argv[2]).read_text())
fd=os.open(state,os.O_RDONLY|os.O_NOFOLLOW)
try: raw=os.pread(fd,layout['size'],0)
finally: os.close(fd)
assert len(raw)==layout['size']
fields={n:raw[e['offset']:e['offset']+e['size']] for n,e in layout['fields'].items()}
result={'sha256':{n:hashlib.sha256(b).hexdigest() for n,b in fields.items()},'integers':{n:int.from_bytes(fields[n],'little',signed=n!='admission_epoch') for n in ['fbblank','modewide','owner_pid','admission_epoch','mutation_count']}}
if len(sys.argv)>3:
 p=pathlib.Path(sys.argv[3]); matches={}
 for n,f in layout['payload_fields'].items():
  if not n.endswith('modes'):
   want=(p/n).read_bytes(); matches[n]=fields[f][:len(want)]==want
 matches['blank']=result['integers']['fbblank']==4; matches['mode']=result['integers']['modewide']==0
 result['baseline_matches']=matches; result['baseline']=all(matches.values())
print(json.dumps(result,sort_keys=True))
