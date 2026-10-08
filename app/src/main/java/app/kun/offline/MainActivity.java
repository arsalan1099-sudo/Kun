package app.kun.offline;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import com.google.mediapipe.tasks.genai.llminference.LlmInference;

public class MainActivity extends Activity {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private LlmInference engine;
 private boolean busy=false; private volatile boolean dead=false;
 private LinearLayout chat, root; private ScrollView scroll; private EditText input;
 private TextView status; private Button send, model, clear, settings;
 private Switch serverMode; private String serverUrl="", sessionToken="";
 private JSONArray history=new JSONArray();
 private final int BG=Color.rgb(16,20,15), PANEL=Color.rgb(30,37,28), INK=Color.rgb(235,242,232), LIME=Color.rgb(164,255,103);
 private File modelFile(){return new File(getFilesDir(),"model.task");}
 private File historyFile(){return new File(getFilesDir(),"chat.json");}
 private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n);}
 private GradientDrawable box(int c,int r){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));return d;}
 private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(6),dp(6),dp(6),dp(6));t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);return t;}
 private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(INK);b.setBackground(box(PANEL,14));return b;}
 private void ui(Runnable r){runOnUiThread(()->{if(!dead&&!isFinishing())r.run();});}
 @Override public void onCreate(Bundle b){super.onCreate(b);
  root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(18),dp(14),dp(18),dp(12));root.setBackgroundColor(BG);setContentView(root);
  LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
  TextView brand=text("KUN  /  کُن",30,LIME);brand.setTypeface(null,Typeface.BOLD);head.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
  Button info=button("مدد");head.addView(info);info.setOnClickListener(v->help());root.addView(head);
  status=text("OFFLINE • اپنا model منتخب کریں",14,INK);root.addView(status);
  LinearLayout controls=new LinearLayout(this);model=button("Model لوڈ کریں");clear=button("نئی گفتگو");controls.addView(model,new LinearLayout.LayoutParams(0,dp(50),1));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(50),1);cp.setMarginStart(dp(8));controls.addView(clear,cp);root.addView(controls);
  model.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);});
  clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("گفتگو حذف کریں؟").setMessage("یہ اس فون پر محفوظ موجودہ گفتگو مٹا دے گا۔").setNegativeButton("واپس",null).setPositiveButton("حذف کریں",(d,w)->{history=new JSONArray();saveHistory();render();}).show());
  LinearLayout network=new LinearLayout(this);network.setGravity(Gravity.CENTER_VERTICAL);
  serverMode=new Switch(this);serverMode.setText("Server mode");serverMode.setTextColor(INK);network.addView(serverMode,new LinearLayout.LayoutParams(0,-2,1));
  settings=button("Server settings");network.addView(settings);root.addView(network);settings.setOnClickListener(v->serverSettings());
  serverUrl=getPreferences(0).getString("serverUrl", "");
  serverMode.setOnCheckedChangeListener((b,on)->{setBusy(busy);status.setText(on?"SERVER • سوال اور حالیہ گفتگو server کو بھیجے جائیں گے":"LOCAL • گفتگو فون پر رہے گی");if(on&&sessionToken.isEmpty())serverSettings();});
  scroll=new ScrollView(this);scroll.setFillViewport(true);chat=new LinearLayout(this);chat.setOrientation(1);chat.setPadding(0,dp(16),0,dp(16));scroll.addView(chat);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  input=new EditText(this);input.setTextColor(INK);input.setHintTextColor(Color.LTGRAY);input.setHint("اپنا سوال لکھیں…");input.setTextSize(18);input.setMinLines(2);input.setMaxLines(5);input.setGravity(Gravity.TOP|Gravity.START);input.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);input.setInputType( android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);input.setBackground(box(PANEL,18));input.setPadding(dp(14),dp(12),dp(14),dp(12));root.addView(input,new LinearLayout.LayoutParams(-1,-2));
  send=button("بھیجیں");send.setBackground(box(LIME,16));send.setTextColor(BG);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(52));sp.topMargin=dp(10);root.addView(send,sp);send.setOnClickListener(v->generate());
  loadHistory();render();setBusy(false);
  File recovery=new File(getFilesDir(),"previous.task");if(!modelFile().exists()&&recovery.exists())recovery.renameTo(modelFile());
  if(modelFile().exists()){setBusy(true);status.setText("Model کھل رہا ہے…");worker.execute(()->{try{engine=openModel(modelFile());ui(()->status.setText("OFFLINE • Model تیار ہے"));}catch(Throwable e){ui(()->status.setText("Model نہیں کھلا۔ مدد دیکھیں یا دوسرا model لوڈ کریں۔"));}finally{ui(()->setBusy(false));}});}
 }
 private LlmInference openModel(File f){return LlmInference.createFromOptions(getApplicationContext(),LlmInference.LlmInferenceOptions.builder().setModelPath(f.getAbsolutePath()).setMaxTokens(2048).setPreferredBackend(LlmInference.Backend.CPU).build());}
 private void setBusy(boolean b){busy=b;model.setEnabled(!b);clear.setEnabled(!b);settings.setEnabled(!b);serverMode.setEnabled(!b);send.setEnabled(!b&&(serverMode.isChecked()?(!serverUrl.isEmpty()&&!sessionToken.isEmpty()):engine!=null));input.setEnabled(!b);send.setText(b?"کام جاری ہے…":"بھیجیں");}
 private void render(){chat.removeAllViews();if(history.length()==0){TextView t=text("آپ کا سوال۔\nآپ کے فون پر جواب۔",29,INK);t.setTypeface(null,Typeface.BOLD);chat.addView(t);chat.addView(text("Local: موزوں .task model لوڈ کریں۔ Server: settings میں اپنا HTTPS server اور access token درج کریں۔ Server mode میں سوال اور حالیہ گفتگو باہر بھیجی جاتی ہے۔",17,Color.LTGRAY));}for(int n=0;n<history.length();n++){JSONObject o=history.optJSONObject(n);if(o!=null)addBubble(o.optString("role"),o.optString("text"));}scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));}
 private void addBubble(String role,String content){LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(10),dp(8),dp(10),dp(10));card.setBackground(box(role.equals("user")?Color.rgb(39,52,32):PANEL,18));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);card.addView(text(role.equals("user")?"آپ":"KUN",14,LIME));TextView t=text(content,18,INK);t.setTextIsSelectable(true);card.addView(t);chat.addView(card,p);}
 private void append(String role,String content){try{JSONObject o=new JSONObject();o.put("role",role);o.put("text",content);history.put(o);while(history.length()>100)history.remove(0);}catch(JSONException ignored){} }
 private String clean(String s){return s.replace("<start_of_turn>","").replace("<end_of_turn>","");}
 private String prompt(String q,int from){StringBuilder p=new StringBuilder("You are KUN, a helpful offline assistant. Reply in the user's language; use Urdu by default. Be honest about uncertainty. Do not claim internet access or actions you have not taken. The following is prior conversation for context. Answer only the final user question.\n\n");for(int i=from;i<history.length();i++){JSONObject o=history.optJSONObject(i);if(o!=null)p.append(o.optString("role").equals("user")?"User: ":"KUN: ").append(clean(o.optString("text"))).append("\n\n");}return p.append("Final user question: ").append(clean(q)).toString();}
 private void serverSettings(){
  LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(20),dp(8),dp(20),0);
  TextView note=text("اپنے KUN server کا HTTPS address درج کریں۔ Token صرف اس session میں رہے گا؛ app بند ہونے پر دوبارہ درج کریں۔",16,Color.DKGRAY);form.addView(note);
  EditText url=new EditText(this);url.setSingleLine(true);url.setHint("https://kun.example.com");url.setText(serverUrl);url.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);form.addView(url);
  EditText token=new EditText(this);token.setSingleLine(true);token.setHint("Your user access token");token.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);token.setText(sessionToken);form.addView(token);
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle("KUN Server").setView(form).setNegativeButton("واپس",null).setNeutralButton("Token ہٹائیں",(d,w)->{sessionToken="";setBusy(false);}).setPositiveButton("محفوظ کریں",null).create();
  dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
   try {String base=RemoteClient.validateBase(url.getText().toString().trim());String key=token.getText().toString().trim();if(key.length()<32||!key.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("درست user token درج کریں (کم از کم 32 حروف)۔");
    serverUrl=base;sessionToken=key;getPreferences(0).edit().putString("serverUrl",base).apply();setBusy(false);dialog.dismiss();
   } catch(Exception e){url.setError(e.getMessage());}
  }));dialog.show();
 }
 private void generate(){
  final boolean remote=serverMode.isChecked();if(busy||(!remote&&engine==null))return;
  String q=input.getText().toString().trim();if(q.isEmpty())return;if(q.length()>5000){toast("سوال مختصر کریں (زیادہ سے زیادہ 5000 حروف)۔");return;}
  final String base=serverUrl,key=sessionToken;setBusy(true);status.setText(remote?"SERVER • جواب تیار ہو رہا ہے…":"LOCAL • فون پر جواب تیار ہو رہا ہے…");
  worker.execute(()->{try{
   String answer;
   if(remote){answer=RemoteClient.generate(base,key,history,q);}
   else {int start=Math.max(0,history.length()-8);if(start%2!=0)start++;String p=prompt(q,start);while(engine.sizeInTokens(p)>1400&&start<history.length()){start=Math.min(history.length(),start+2);p=prompt(q,start);}if(engine.sizeInTokens(p)>1400)throw new IllegalArgumentException("سوال بہت طویل ہے؛ مختصر کریں۔");answer=engine.generateResponse(p);}
   if(answer==null||answer.trim().isEmpty())throw new IOException("خالی جواب؛ دوبارہ کوشش کریں۔");
   final String result=answer;ui(()->{append("user",q);append("model",result);input.setText("");saveHistory();render();status.setText(remote?"SERVER • جواب تیار ہے":"LOCAL • جواب تیار ہے");});
  }catch(Throwable e){ui(()->{status.setText("جواب مکمل نہیں ہوا۔ سوال محفوظ ہے۔");new AlertDialog.Builder(this).setTitle("دوبارہ کوشش کریں").setMessage(safeError(e)).setPositiveButton("ٹھیک ہے",null).show();});}finally{ui(()->setBusy(false));}});
 }
 private String safeError(Throwable e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m.substring(0,Math.min(m.length(),350));}
 @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(req!=10||res!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();setBusy(true);status.setText("Model فائل نقل ہو رہی ہے…");worker.execute(()->{File tmp=new File(getFilesDir(),"import.task"),backup=new File(getFilesDir(),"previous.task");boolean moved=false, installedNew=false;try{
  try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(tmp)){if(in==null)throw new IOException("فائل نہیں کھلی");byte[] buffer=new byte[1024*1024];long total=0;int count;while((count=in.read(buffer))!=-1){total+=count;if(total>4L*1024*1024*1024)throw new IOException("4GB سے چھوٹا model منتخب کریں۔");out.write(buffer,0,count);}out.getFD().sync();if(total<1024*1024)throw new IOException("یہ model فائل نہیں لگتی۔");}
  ui(()->status.setText("Model کی مطابقت جانچ رہے ہیں…"));if(engine!=null){engine.close();engine=null;}
  if(modelFile().exists()){if(backup.exists())backup.delete();if(!modelFile().renameTo(backup))throw new IOException("پچھلا model محفوظ نہیں ہوا");moved=true;}
  if(!tmp.renameTo(modelFile()))throw new IOException("Model محفوظ نہیں ہوا");installedNew=true;engine=openModel(modelFile());backup.delete();ui(()->status.setText("OFFLINE • Model تیار ہے"));
 }catch(Throwable e){tmp.delete();if(moved&&backup.exists()){modelFile().delete();backup.renameTo(modelFile());}else if(installedNew&&engine==null){modelFile().delete();}if(engine==null&&modelFile().exists())try{engine=openModel(modelFile());}catch(Throwable ignored){}ui(()->{status.setText(engine==null?"Model لوڈ نہیں ہوا":"پچھلا model بحال ہے");new AlertDialog.Builder(this).setTitle("موزوں model منتخب کریں").setMessage("یہ ورژن Gemma 3 1B کے MediaPipe .task format کے لیے بنایا گیا ہے۔ GGUF یا .litertlm فائل استعمال نہ کریں۔ فون کی مطابقت بھی ضروری ہے۔\n\n"+safeError(e)).setPositiveButton("ٹھیک ہے",null).show();});}finally{ui(()->setBusy(false));}});}
 private void saveHistory(){try{android.util.AtomicFile af=new android.util.AtomicFile(historyFile());FileOutputStream out=null;try{out=af.startWrite();out.write(history.toString().getBytes(StandardCharsets.UTF_8));af.finishWrite(out);}catch(Exception e){if(out!=null)af.failWrite(out);throw e;}}catch(Exception e){toast("گفتگو محفوظ نہیں ہوئی۔");}}
 private void loadHistory(){try{if(historyFile().exists()&&historyFile().length()<2_000_000){try(FileInputStream in=new FileInputStream(historyFile())){history=new JSONArray(new String(readBytes(in),StandardCharsets.UTF_8));}}}catch(Exception e){history=new JSONArray();toast("پچھلی گفتگو نہیں پڑھی جا سکی۔");}}
 private byte[] readBytes(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}
 private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 private void help(){new AlertDialog.Builder(this).setTitle("KUN • Hybrid 0.2").setMessage("Local mode میں Replit، login یا API key کی ضرورت نہیں۔ Server mode کے لیے آپ کا HTTPS backend اور الگ user token درکار ہے۔ خودکار cloud fallback نہیں ہوتا۔\n\n1. Model صفحہ کھولیں، شرائط قبول کرکے Gemma 3 1B کا MediaPipe .task model ڈاؤن لوڈ کریں۔\n2. KUN میں Model لوڈ کریں دبائیں اور فائل منتخب کریں۔\n3. Model تیار ہونے پر سوال بھیجیں۔\n\nApp میں model شامل نہیں۔ GGUF اور .litertlm یہاں نہیں چلتے۔ چند GB خالی جگہ درکار ہو سکتی ہے؛ درآمد کے وقت فائل کی نقل بنتی ہے۔\n\nگفتگو اور model app کی نجی storage میں ہیں؛ uninstall سے حذف ہوں گے۔ نئی گفتگو سے موجودہ history حذف ہوگی۔ صرف حالیہ گفتگو model کو دی جاتی ہے۔\n\nیہ نیا trained model نہیں؛ منتخب model پر چلنے والا اپنا app ہے۔ فون پر رفتار، اردو معیار اور compatibility ابھی غیر مصدقہ ہیں۔").setPositiveButton("ٹھیک ہے",null).setNeutralButton("Model صفحہ",(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://huggingface.co/litert-community/Gemma3-1B-IT")));}catch(Exception e){toast("Browser دستیاب نہیں۔");}}).show();}
 @Override protected void onDestroy(){dead=true;worker.execute(()->{if(engine!=null){engine.close();engine=null;}});worker.shutdown();super.onDestroy();}
}
