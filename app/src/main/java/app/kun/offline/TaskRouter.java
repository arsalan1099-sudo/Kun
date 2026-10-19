package app.kun.offline;
import org.json.*;
import java.util.*;
import java.util.regex.*;

/** Exported TF-IDF/logistic-regression weights trained by training/train_router.py. */
final class TaskRouter {
 private final Map<String,Integer> indexes=new HashMap<>();
 private final String[] labels;private final double[] idf,bias;private final double[][] weights;
 private final double threshold,margin;
 TaskRouter(JSONObject model)throws Exception{
  if(model.getInt("version")!=1)throw new IllegalArgumentException("Unknown router version");
  JSONArray f=model.getJSONArray("features"),c=model.getJSONArray("classes"),di=model.getJSONArray("idf"),b=model.getJSONArray("bias"),w=model.getJSONArray("weights");
  if(f.length()>10000||f.length()!=di.length()||c.length()!=5||b.length()!=5||w.length()!=5)throw new IllegalArgumentException("Invalid router dimensions");
  labels=new String[5];idf=new double[f.length()];bias=new double[5];weights=new double[5][f.length()];
  for(int i=0;i<f.length();i++){indexes.put(f.getString(i),i);idf[i]=finite(di.getDouble(i));}
  for(int k=0;k<5;k++){labels[k]=c.getString(k);bias[k]=finite(b.getDouble(k));JSONArray row=w.getJSONArray(k);if(row.length()!=f.length())throw new IllegalArgumentException("Invalid weight row");for(int i=0;i<f.length();i++)weights[k][i]=finite(row.getDouble(i));}
  threshold=finite(model.getDouble("threshold"));margin=finite(model.getDouble("margin"));
 }
 private static double finite(double n){if(!Double.isFinite(n))throw new IllegalArgumentException("Nonfinite weight");return n;}
 static Map<String,Integer> features(String text){
  Map<String,Integer> counts=new HashMap<>();Matcher m=Pattern.compile("[a-z0-9\\u0600-\\u06ff]+").matcher(text.toLowerCase(Locale.ROOT));
  while(m.find()){String word=m.group();add(counts,"w:"+word);for(int n=2;n<=3;n++)for(int i=0;i<=word.length()-n;i++)add(counts,"c:"+word.substring(i,i+n));}return counts;
 }
 private static void add(Map<String,Integer> counts,String key){counts.put(key,Math.min(3,counts.getOrDefault(key,0)+1));}
 double[] probabilities(String text){
  Map<Integer,Double> vector=new HashMap<>();double norm=0;
  for(Map.Entry<String,Integer> e:features(text).entrySet()){Integer i=indexes.get(e.getKey());if(i!=null){double v=e.getValue()*idf[i];vector.put(i,v);norm+=v*v;}}
  norm=Math.sqrt(norm);double[] scores=bias.clone();if(norm>0)for(int k=0;k<labels.length;k++)for(Map.Entry<Integer,Double> e:vector.entrySet())scores[k]+=weights[k][e.getKey()]*e.getValue()/norm;
  double max=Arrays.stream(scores).max().getAsDouble(),sum=0;for(int k=0;k<scores.length;k++){scores[k]=Math.exp(scores[k]-max);sum+=scores[k];}for(int k=0;k<scores.length;k++)scores[k]/=sum;return scores;
 }
 String route(String text){double[] p=probabilities(text);int best=0;for(int i=1;i<p.length;i++)if(p[i]>p[best])best=i;double second=0;for(int i=0;i<p.length;i++)if(i!=best)second=Math.max(second,p[i]);return p[best]>=threshold&&p[best]-second>=margin?labels[best]:"general";}
}
