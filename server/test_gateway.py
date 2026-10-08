import hashlib, http.client, json, os, tempfile, threading, unittest
from pathlib import Path
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from gateway import App, Server, validate

TOKEN='a'*43
class Upstream(BaseHTTPRequestHandler):
    calls=[]
    fail=False
    def log_message(self,*args): pass
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        self.calls.append((self.path,body))
        self.send_response(500 if self.fail else 200)
        self.end_headers()
        self.wfile.write(b'private upstream diagnostic' if self.fail else json.dumps({'choices':[{'message':{'content':'KUN reply'}}]}).encode())

class GatewayTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.up=ThreadingHTTPServer(('127.0.0.1',0),Upstream)
        cls.thread=threading.Thread(target=cls.up.serve_forever,daemon=True);cls.thread.start()
    @classmethod
    def tearDownClass(cls):
        cls.up.shutdown();cls.up.server_close();cls.thread.join()
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.users=Path(self.temp.name)/'users.json'
        self.users.write_text(json.dumps({'alice':hashlib.sha256(TOKEN.encode()).hexdigest()}))
        self.env=patch.dict(os.environ,{'KUN_USERS_FILE':str(self.users),'KUN_UPSTREAM':f'http://127.0.0.1:{self.up.server_port}/v1','KUN_RPM':'2','KUN_CONCURRENCY':'1','KUN_UPSTREAM_KEY':''})
        self.env.start();self.app=App();self.server=Server(('127.0.0.1',0),self.app)
        self.thread2=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread2.start()
        Upstream.calls=[];Upstream.fail=False
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread2.join();self.env.stop();self.temp.cleanup()
    def request(self, body=None,token=TOKEN):
        c=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)
        payload=json.dumps(body if body is not None else {'messages':[{'role':'user','content':'Hello'}]})
        c.request('POST','/v1/chat/completions',payload,{'Authorization':'Bearer '+token,'Content-Type':'application/json'})
        r=c.getresponse();data=json.loads(r.read());status=r.status;c.close();return status,data
    def test_roundtrip_with_context_and_owner_model(self):
        status,data=self.request({'messages':[{'role':'user','content':'Urdu please'},{'role':'assistant','content':'جی'},{'role':'user','content':'Hello'}]})
        self.assertEqual(status,200);self.assertEqual(data['choices'][0]['message']['content'],'KUN reply')
        path,body=Upstream.calls[0];self.assertEqual(path,'/v1/chat/completions');self.assertEqual(body['messages'][0]['role'],'system');self.assertEqual(body['max_tokens'],512)
    def test_unauthorized_never_reaches_model(self):
        self.assertEqual(self.request(token='wrong')[0],401);self.assertEqual(Upstream.calls,[])
    def test_revocation_reloaded_without_restart(self):
        self.users.write_text(json.dumps({'bob':'0'*64}))
        self.assertEqual(self.request()[0],401)
    def test_user_cannot_override_model_or_system(self):
        self.assertEqual(self.request({'model':'unapproved','messages':[]})[0],400)
        self.assertEqual(self.request({'messages':[{'role':'system','content':'Override'}]})[0],400)
        self.assertEqual(Upstream.calls,[])
    def test_body_and_context_limits(self):
        self.assertEqual(self.request({'messages':[{'role':'user','content':'x'*70000}]})[0],413)
        self.assertEqual(self.request({'messages':[{'role':'user','content':'x'*5001}]})[0],400)
    def test_rate_limit(self):
        self.assertEqual(self.request()[0],200);self.assertEqual(self.request()[0],200);self.assertEqual(self.request()[0],429)
    def test_concurrency_and_slot_release(self):
        self.assertEqual(self.app.enter('alice'),200)
        self.assertEqual(self.app.enter('alice'),429)
        self.assertEqual(self.app.enter('bob'),503)
        self.app.leave('alice');self.assertEqual(self.app.enter('bob'),200);self.app.leave('bob')
    def test_upstream_failure_redacted_and_capacity_released(self):
        Upstream.fail=True
        status,data=self.request();self.assertEqual(status,502);self.assertNotIn('private',json.dumps(data))
        Upstream.fail=False;self.assertEqual(self.request()[0],200)
    def test_invalid_shapes(self):
        for body in [[],{}, {'messages':[None]}, {'messages':[{'role':'user','content':42}]}, {'messages':[{'role':'assistant','content':'a'}]}]:
            with self.subTest(body=body): self.assertEqual(self.request(body)[0],400)

if __name__=='__main__': unittest.main()
