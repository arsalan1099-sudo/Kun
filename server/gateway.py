"""KUN small-pilot gateway. Run behind the included HTTPS reverse proxy.
No prompt persistence. User tokens are stored only as SHA-256 hashes.
Single process: rate/concurrency limits are process-local and reset on restart.
"""
import hashlib
import hmac
import json
import os
import socket
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener, HTTPRedirectHandler

SYSTEM = 'You are KUN, a helpful assistant. Reply in the user language, Urdu by default. Be honest about uncertainty. Do not claim browsing or actions you have not taken.'

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

class App:
    def __init__(self):
        self.users_path = Path(os.environ.get('KUN_USERS_FILE', 'users.json'))
        self.upstream = os.environ.get('KUN_UPSTREAM', 'http://127.0.0.1:11434/v1').rstrip('/')
        self.model = os.environ.get('KUN_MODEL', 'gemma3:1b')
        self.key = os.environ.get('KUN_UPSTREAM_KEY', '')
        self.limit = max(1, int(os.environ.get('KUN_RPM', '10')))
        self.slots = threading.BoundedSemaphore(max(1, int(os.environ.get('KUN_CONCURRENCY', '2'))))
        self.lock = threading.Lock()
        self.events = {}
        self.active = set()
        self.users()  # Fail closed at startup if provisioning is missing/broken.
    def users(self):
        data = json.loads(self.users_path.read_text())
        if not isinstance(data, dict) or not data or len(data)>10000:
            raise ValueError('Provision users.json with provision.py first')
        for user, digest in data.items():
            if not isinstance(user, str) or not isinstance(digest, str) or len(digest)!=64:
                raise ValueError('Invalid user digest')
        return data
    def authenticate(self, header):
        if not header.startswith('Bearer ') or len(header)>512:
            return None
        digest = hashlib.sha256(header[7:].encode()).hexdigest()
        for user, known in self.users().items():
            if hmac.compare_digest(digest, known):
                return user
        return None
    def enter(self, user):
        with self.lock:
            now = time.monotonic()
            # Drop inactive stale buckets, including revoked identities.
            for name in list(self.events):
                while self.events[name] and self.events[name][0]<=now-60:
                    self.events[name].popleft()
                if not self.events[name]:
                    del self.events[name]
            q=self.events.setdefault(user,deque())
            if user in self.active or len(q)>=self.limit:
                return 429
            if not self.slots.acquire(blocking=False):
                return 503
            q.append(now)
            self.active.add(user)
            return 200
    def leave(self,user):
        with self.lock:
            self.active.discard(user)
            self.slots.release()
    def infer(self, messages):
        payload=json.dumps({'model':self.model,'messages':[{'role':'system','content':SYSTEM}]+messages,'stream':False,'max_tokens':512}).encode()
        headers={'Content-Type':'application/json'}
        if self.key:
            headers['Authorization']='Bearer '+self.key
        req=Request(self.upstream+'/chat/completions',data=payload,headers=headers,method='POST')
        with build_opener(NoRedirect).open(req,timeout=150) as response:
            raw=response.read(262145)
        if len(raw)>262144:
            raise ValueError('Oversized upstream response')
        obj=json.loads(raw)
        answer=obj['choices'][0]['message']['content']
        if not isinstance(answer,str) or not answer.strip() or len(answer)>60000:
            raise ValueError('Invalid upstream answer')
        return {'choices':[{'index':0,'message':{'role':'assistant','content':answer},'finish_reason':obj['choices'][0].get('finish_reason','stop')}], 'model':self.model}

def validate(data):
    if not isinstance(data,dict) or set(data)!={'messages'}:
        raise ValueError('Only messages are accepted')
    messages=data['messages']
    if not isinstance(messages,list) or not 1<=len(messages)<=9:
        raise ValueError('Use 1 to 9 messages')
    total=0
    for index,item in enumerate(messages):
        if not isinstance(item,dict) or set(item)!={'role','content'}:
            raise ValueError('Invalid message')
        if item['role']!=('user' if index%2==0 else 'assistant'):
            raise ValueError('Alternate user and assistant, starting with user')
        if not isinstance(item['content'],str) or not item['content'].strip():
            raise ValueError('Empty message')
        total+=len(item['content'])
    if messages[-1]['role']!='user' or len(messages[-1]['content'])>5000 or total>12000:
        raise ValueError('Question/context too long or missing final user question')
    return messages

class Handler(BaseHTTPRequestHandler):
    # Never put requests, tokens, or prompts into logs.
    def log_message(self,*args):
        pass
    def setup(self):
        super().setup()
        self.connection.settimeout(15)
    def reply(self,code,obj):
        raw=json.dumps(obj,ensure_ascii=False).encode()
        try:
            self.send_response(code)
            self.send_header('Content-Type','application/json; charset=utf-8')
            self.send_header('Content-Length',str(len(raw)))
            self.send_header('Cache-Control','no-store')
            self.send_header('Connection','close')
            if code in (429,503):
                self.send_header('Retry-After','15')
            self.end_headers()
            self.wfile.write(raw)
        except (BrokenPipeError,ConnectionResetError,socket.timeout):
            pass
        self.close_connection=True
    def do_GET(self):
        self.reply(200,{'status':'gateway-alive','inference_checked':False}) if self.path=='/healthz' else self.reply(404,{'error':'Not found'})
    def do_POST(self):
        if self.path!='/v1/chat/completions':
            return self.reply(404,{'error':'Not found'})
        try:
            user=self.server.app.authenticate(self.headers.get('Authorization',''))
        except (OSError,ValueError):
            return self.reply(503,{'error':'Authentication temporarily unavailable'})
        if not user:
            return self.reply(401,{'error':'Invalid access token'})
        try:
            lengths=self.headers.get_all('Content-Length') or []
            if self.headers.get('Transfer-Encoding') or len(lengths)!=1:
                raise ValueError('Content-Length required')
            length=int(lengths[0])
            if length<1 or length>65536:
                return self.reply(413,{'error':'Body size limit exceeded'})
            if self.headers.get_content_type()!='application/json':
                return self.reply(415,{'error':'JSON required'})
            raw=self.rfile.read(length)
            if len(raw)!=length:
                raise ValueError('Incomplete body')
            messages=validate(json.loads(raw))
        except (ValueError,TypeError,RecursionError,socket.timeout):
            return self.reply(400,{'error':'Invalid messages or request body'})
        state=self.server.app.enter(user)
        if state!=200:
            return self.reply(state,{'error':'Busy; retry later'})
        try:
            answer=self.server.app.infer(messages)
            self.reply(200,answer)
        except (HTTPError,URLError,TimeoutError,ValueError,KeyError,IndexError,TypeError,OSError):
            self.reply(502,{'error':'Inference unavailable; check model and server'})
        finally:
            self.server.app.leave(user)

class Server(ThreadingHTTPServer):
    daemon_threads=True
    def __init__(self,address,app):
        self.app=app
        super().__init__(address,Handler)

if __name__=='__main__':
    app=App()
    Server((os.environ.get('KUN_BIND','127.0.0.1'),int(os.environ.get('PORT','8080'))),app).serve_forever()
