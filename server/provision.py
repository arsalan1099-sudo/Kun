"""Owner-only CLI: python provision.py add alice | revoke alice.
Prints a new token ONCE. Pass it privately to that user, never embed in the APK.
Stop simultaneous admin writes; one administrator owns this pilot file.
"""
import argparse, hashlib, json, os, secrets
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('action',choices=['add','revoke'])
p.add_argument('user')
p.add_argument('--file',default='users.json')
a=p.parse_args()
path=Path(a.file)
users=json.loads(path.read_text()) if path.exists() else {}
if a.action=='add':
    token=secrets.token_urlsafe(32)
    users[a.user]=hashlib.sha256(token.encode()).hexdigest()
else:
    users.pop(a.user,None)
path.parent.mkdir(parents=True,exist_ok=True)
tmp=path.with_suffix('.tmp')
fd=os.open(tmp,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
with os.fdopen(fd,'w') as f:
    json.dump(users,f,indent=2)
os.replace(tmp,path)
if a.action=='add':
    print(token)
else:
    print('Revoked')
