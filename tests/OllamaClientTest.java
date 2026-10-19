package app.kun.offline;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
public class OllamaClientTest {
 static int checks=0;static void check(boolean ok){if(!ok)throw new AssertionError();checks++;}
 static void rejected(String s)throws Exception{try{OllamaClient.validateBase(s);throw new AssertionError(s);}catch(java.io.IOException|URISyntaxException expected){checks++;}}
 public static void main(String[] args)throws Exception{
  check(OllamaClient.validateBase("http://192.168.1.2:11434/").equals("http://192.168.1.2:11434"));
  for(String s:new String[]{"http://example.com","http://8.8.8.8","http://192.168.999.1","https://user:pass@example.com","https://example.com/api/chat","https://example.com?q=1","file:///tmp/model","http://10.0.0.2:99999"})rejected(s);
  JSONArray h=new JSONArray();h.put(new JSONObject().put("role","user").put("text","old").put("channel","experimental"));h.put(new JSONObject().put("role","model").put("text","gibberish").put("channel","experimental"));check(OllamaClient.context(h,"pc/model").length()==0);
  h.put(new JSONObject().put("role","user").put("text","Hello").put("channel","pc/model"));h.put(new JSONObject().put("role","model").put("text","Hi").put("channel","pc/model"));check(OllamaClient.context(h,"pc/model").length()==2);
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/api/tags",e->{byte[] b="{\"models\":[{\"name\":\"test-model\"}]}".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});
  server.createContext("/api/chat",e->{try{JSONObject req=new JSONObject(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));check(!req.getBoolean("stream"));check(req.getJSONArray("messages").getJSONObject(1).getString("content").equals("السلام علیکم"));String name=req.getString("model");byte[] b=(name.equals("empty")?"{\"message\":{\"content\":\"\"}}":"{\"message\":{\"content\":\"وعلیکم السلام\"}}").getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(name.equals("missing")?404:200,b.length);e.getResponseBody().write(b);}catch(Exception ex){throw new RuntimeException(ex);}finally{e.close();}});
  server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();try{
   check(OllamaClient.models(base)[0].equals("test-model"));check(OllamaClient.generate(base,"test-model",new JSONArray(),"السلام علیکم").equals("وعلیکم السلام"));
   for(String name:new String[]{"empty","missing"}){try{OllamaClient.generate(base,name,new JSONArray(),"السلام علیکم");throw new AssertionError();}catch(java.io.IOException expected){checks++;}}
  }finally{server.stop(0);}
  System.out.println("PASS: "+checks+" checks: origin validation, context isolation, model list, UTF-8 request/response, empty output and HTTP error handling. Mock server; no real AI inference tested.");
 }
}
