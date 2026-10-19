package app.kun.offline;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Explicit user-authored examples. Retrieval memory, not LLM weight training. */
final class MemoryBank {
 private JSONArray entries;
 MemoryBank(String json)throws Exception{entries=new JSONArray(json);if(entries.length()>100)throw new IllegalArgumentException("Too many notes");Set<String> keys=new HashSet<>();for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);validate(e.getString("question"),e.getString("answer"));if(!keys.add(normalize(e.getString("question"))))throw new IllegalArgumentException("Duplicate memory question");}}
 static String normalize(String s){return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");}
 static void validate(String q,String a){if(q.trim().isEmpty()||a.trim().isEmpty()||q.length()>500||a.length()>2000)throw new IllegalArgumentException("Question: 1–500 characters; answer: 1–2000 characters.");}
 void put(String q,String a)throws Exception{validate(q,a);for(int i=0;i<entries.length();i++)if(normalize(q).equals(normalize(entries.getJSONObject(i).getString("question")))){entries.put(i,new JSONObject().put("question",q.trim()).put("answer",a.trim()));return;}if(entries.length()>=100)throw new IllegalArgumentException("100 saved examples. Delete one first.");entries.put(new JSONObject().put("question",q.trim()).put("answer",a.trim()));}
 int size(){return entries.length();}
 String question(int index)throws Exception{return entries.getJSONObject(index).getString("question");}
 String answer(int index)throws Exception{return entries.getJSONObject(index).getString("answer");}
 void remove(int index){entries.remove(index);}
 public String toString(){return entries.toString();}
 String exact(String q)throws Exception{for(int i=0;i<entries.length();i++)if(normalize(q).equals(normalize(question(i))))return answer(i);return null;}
 private static final Set<String> STOP=new HashSet<>(Arrays.asList("a","an","the","is","are","my","me","i","you","for","to","of","in","and","do","how","what","please","can","mujhe","mera","meri","ke","ki","ka","ko","hai","karo","batao","mein","aur","میں","کی","کا","کے","کو","ہے","ہیں","اور","کیا","مجھے","میرا","میری","بتائیں","کریں"));
 private static Set<String> tokens(String s){Set<String> out=new HashSet<>();Matcher m=Pattern.compile("[a-z0-9\\u0600-\\u06ff]+").matcher(normalize(s));while(m.find())if(!STOP.contains(m.group()))out.add(m.group());return out;}
 JSONArray relevant(String q)throws Exception{
  String exact=exact(q);JSONArray out=new JSONArray();if(exact!=null){out.put(new JSONObject().put("question",q).put("answer",exact));return out;}
  Set<String> query=tokens(q);List<double[]> matches=new ArrayList<>();
  for(int i=0;i<size();i++){Set<String> t=tokens(question(i));int hits=0;for(String word:query)if(t.contains(word))hits++;int union=query.size()+t.size()-hits;double score=union==0?0:(double)hits/union;if(hits>=2&&score>=.25)matches.add(new double[]{i,score});}
  matches.sort((a,b)->Double.compare(b[1],a[1]));for(int i=0;i<Math.min(2,matches.size());i++){int n=(int)matches.get(i)[0];out.put(new JSONObject().put("question",question(n)).put("answer",answer(n)));}return out;
 }
 String exportJsonl()throws Exception{StringBuilder b=new StringBuilder();for(int i=0;i<size();i++){JSONArray m=new JSONArray().put(new JSONObject().put("role","user").put("content",question(i))).put(new JSONObject().put("role","assistant").put("content",answer(i)));b.append(new JSONObject().put("messages",m).put("source","user-reviewed-example")).append('\n');}return b.toString();}
}
