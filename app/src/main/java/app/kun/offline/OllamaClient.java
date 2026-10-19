package app.kun.offline;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Explicit user-selected Ollama connection. No public plaintext endpoints or redirects. */
final class OllamaClient {
 static String validateBase(String value)throws Exception {
  URI u=new URI(value);String host=u.getHost(),path=u.getPath();
  if(host==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!(path.isEmpty()||path.equals("/"))||u.getPort()>65535||u.getPort()==0)
   throw new IOException("Enter only the server origin, for example http://192.168.1.10:11434");
  boolean local=host.equals("localhost")||host.equals("127.0.0.1");
  String[] parts=host.split("\\.");
  if(parts.length==4){try{int[] ip=new int[4];for(int i=0;i<4;i++){if(!parts[i].matches("[0-9]{1,3}"))throw new NumberFormatException();ip[i]=Integer.parseInt(parts[i]);if(ip[i]>255)throw new NumberFormatException();}local=ip[0]==10||(ip[0]==192&&ip[1]==168)||(ip[0]==172&&ip[1]>=16&&ip[1]<=31)||ip[0]==127;}catch(NumberFormatException e){throw new IOException("Invalid IPv4 address");}}
  if(!"https".equalsIgnoreCase(u.getScheme())&&!("http".equalsIgnoreCase(u.getScheme())&&local))throw new IOException("HTTP is allowed only for a private PC IP. Use HTTPS elsewhere.");
  return value.endsWith("/")?value.substring(0,value.length()-1):value;
 }
 static JSONArray context(JSONArray history,String channel){
  JSONArray result=new JSONArray();int chars=0;int start=history.length();
  // Only complete recent pairs from this endpoint/model; exclude experimental output.
  for(int i=history.length()-2;i>=0&&history.length()-i<=8;i-=2){JSONObject a=history.optJSONObject(i),b=history.optJSONObject(i+1);if(a==null||b==null||!channel.equals(a.optString("channel"))||!channel.equals(b.optString("channel"))||!"user".equals(a.optString("role")))break;chars+=a.optString("text").length()+b.optString("text").length();if(chars>12000)break;start=i;}
  try{for(int i=start;i<history.length();i++){JSONObject o=history.getJSONObject(i);result.put(new JSONObject().put("role","user".equals(o.optString("role"))?"user":"assistant").put("content",o.optString("text")));}}catch(JSONException e){return new JSONArray();}return result;
 }
 static String[] models(String base)throws Exception{
  JSONArray a=request(base,"/api/tags",null).getJSONArray("models");if(a.length()==0)throw new IOException("No installed models. Run ollama pull qwen2.5:1.5b on your PC first.");
  if(a.length()>500)throw new IOException("Too many models returned");String[] names=new String[a.length()];for(int i=0;i<a.length();i++)names[i]=a.getJSONObject(i).getString("name");return names;
 }
 static String generate(String base,String model,JSONArray context,String question)throws Exception{
  return generate(base,model,context,question,SmartPrompt.build("general",new JSONArray()));
 }
 static String generate(String base,String model,JSONArray context,String question,String systemPrompt)throws Exception{
  JSONArray messages=new JSONArray();messages.put(new JSONObject().put("role","system").put("content",systemPrompt));
  for(int i=0;i<context.length();i++)messages.put(context.getJSONObject(i));
  messages.put(new JSONObject().put("role","user").put("content",question));
  JSONObject payload=new JSONObject().put("model",model).put("messages",messages).put("stream",false).put("options",new JSONObject().put("num_predict",768).put("num_ctx",8192));
  String text=request(base,"/api/chat",payload).getJSONObject("message").getString("content");if(text.trim().isEmpty())throw new IOException("Model returned no text. Try another model.");return text;
 }
 static JSONObject request(String base,String path,JSONObject payload)throws Exception{
  HttpURLConnection c=(HttpURLConnection)new URL(validateBase(base)+path).openConnection();
  c.setConnectTimeout(10000);c.setReadTimeout(payload==null?15000:180000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");
  try{
   if(payload!=null){byte[] bytes=payload.toString().getBytes(StandardCharsets.UTF_8);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
   int status=c.getResponseCode();if(status!=200){if(status==404)throw new IOException("Model or Ollama endpoint not found (404). Reconnect and select an installed model.");if(status==401||status==403)throw new IOException("Server requires authentication. This client supports your own direct Ollama server.");throw new IOException("Ollama HTTP "+status);}
   ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("Cancelled");if(out.size()+n>1048576)throw new IOException("Server response exceeded 1 MB");out.write(buf,0,n);}}
   return new JSONObject(out.toString("UTF-8"));
  }catch(SocketTimeoutException e){throw new IOException("PC/model timed out. Keep Ollama running, or choose a smaller model.",e);}catch(ConnectException e){throw new IOException("Cannot reach PC. Check its IP, Wi-Fi and Ollama listener.",e);}finally{c.disconnect();}
 }
}
