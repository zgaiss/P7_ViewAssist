#!/usr/bin/env python3
"""Explicit parked deployment helper; no automatic hardware action.
Reuses the earlier experiment's serialized/session/nonce-checked RPC transport,
with the current classified workspace path (no obsolete Desktop dependency).
All commands must be supplied explicitly and are logged by live_rpc.py.
"""
from pathlib import Path
import importlib.util
import sys
sys.dont_write_bytecode = True
EXPERIMENTS = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('p7_existing_live_rpc', EXPERIMENTS/'ic-vdec-probe-20261102/scripts/live_rpc.py')
rpc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rpc)
rpc.OLD = EXPERIMENTS/'instrument-projection/instr-rpc.py'
if __name__ == '__main__':
    rpc.main()
