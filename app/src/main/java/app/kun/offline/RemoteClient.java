package app.kun.offline;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/** Talks only to the owner's authenticated KUN gateway. Never embeds provider keys. */
final class RemoteClient {
 static String validateBase(String value) throws Exception {
  URI u=new URI(value);
  if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!(u.getPath().isEmpty()||u.getPath().equals("/")))
   throw new IllegalArgumentException("صرف HTTPS server کا اصل address درج کریں؛ path یا query نہیں۔");
  return value.endsWith("/")?value.substring(0,value.length()-1):value;
 }
 static String generate(String base,String token,JSONArray history,String question)throws Exception {
  validateBase(base);
  JSONArray messages=new JSONArray();
  // Keep a bounded, complete recent-turn window. Do not silently truncate the current question.
  int start=Math.max(0,history.length()-8);if(start%2!=0)start++;
  int chars=question.length();
  for(int i=history.length()-1;i>=start;i--){JSONObject o=history.optJSONObject(i);if(o!=null)chars+=o.optString("text").length();}
  while(chars>12000&&start<history.length()){for(int i=start;i<Math.min(start+2,history.length());i++){JSONObject o=history.optJSONObject(i);if(o!=null)chars-=o.optString("text").length();}start+=2;}
  for(int i=start;i<history.length();i++){JSONObject o=history.optJSONObject(i);if(o!=null)messages.put(new JSONObject().put("role",o.optString("role").equals("user")?"user":"assistant").put("content",o.optString("text")));}
  messages.put(new JSONObject().put("role","user").put("content",question));
  byte[] payload=new JSONObject().put("messages",messages).toString().getBytes(StandardCharsets.UTF_8);
  HttpsURLConnection c=(HttpsURLConnection)new URL(base+"/v1/chat/completions").openConnection();
  c.setConnectTimeout(15000);c.setReadTimeout(180000);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(payload.length);
  try{
   try(OutputStream out=c.getOutputStream()){out.write(payload);}
   int code=c.getResponseCode();
   if(code!=200){if(code==401)throw new IOException("Server token غلط یا منسوخ ہے۔");if(code==429||code==503)throw new IOException("Server مصروف ہے؛ کچھ دیر بعد دوبارہ کوشش کریں۔");throw new IOException("Server request ناکام (HTTP "+code+")۔");}
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(bytes.size()+n>262144)throw new IOException("Server جواب بہت بڑا ہے۔");bytes.write(buf,0,n);}}
   return new JSONObject(bytes.toString("UTF-8")).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
  }finally{c.disconnect();}
 }
}
