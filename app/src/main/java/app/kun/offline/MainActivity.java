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
 private final int BG=Color.rgb(16,20,19), PANEL=Color.rgb(25,32,29), INK=Color.rgb(241,245,242), LIME=Color.rgb(172,241,201);
 private File modelFile(){return new File(getFilesDir(),"model.task");}
 private File historyFile(){return new File(getFilesDir(),"chat.json");}
 private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n);}
 private GradientDrawable box(int c,int r){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));return d;}
 private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(6),dp(6),dp(6),dp(6));t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);return t;}
 private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(INK);b.setBackground(box(PANEL,14));return b;}
 private void ui(Runnable r){runOnUiThread(()->{if(!dead&&!isFinishing())r.run();});}
 @Override public void onCreate(Bundle state){super.onCreate(state);buildUi();loadHistory();render();setBusy(false);showLastExit();
  File recovery=new File(getFilesDir(),"previous.task");if(!modelFile().exists()&&recovery.exists())recovery.renameTo(modelFile());
  if(modelFile().exists()){setBusy(true);status.setText("Model کھل رہا ہے…");worker.execute(()->{try{engine=openModel(modelFile());ui(()->status.setText("OFFLINE • Model تیار ہے"));}catch(Throwable e){ui(()->status.setText("Model نہیں کھلا۔ مدد دیکھیں یا دوسرا model لوڈ کریں۔"));}finally{ui(()->setBusy(false));}});}
 }
 private LlmInference openModel(File f){return LlmInference.createFromOptions(getApplicationContext(),LlmInference.LlmInferenceOptions.builder().setModelPath(f.getAbsolutePath()).setMaxTokens(2048).setPreferredBackend(LlmInference.Backend.GPU).build());}
 private void append(String role,String content){try{JSONObject o=new JSONObject();o.put("role",role);o.put("text",content);history.put(o);while(history.length()>100)history.remove(0);}catch(JSONException ignored){} }
 private String clean(String s){return s.replace("<start_of_turn>","").replace("<end_of_turn>","");}
 private String prompt(String q,int from){StringBuilder p=new StringBuilder("You are KUN, a helpful offline assistant. Reply in the user's language; use Urdu by default. Be honest about uncertainty. Do not claim internet access or actions you have not taken. The following is prior conversation for context. Answer only the final user question.\n\n");for(int i=from;i<history.length();i++){JSONObject o=history.optJSONObject(i);if(o!=null)p.append(o.optString("role").equals("user")?"User: ":"KUN: ").append(clean(o.optString("text"))).append("\n\n");}return p.append("Final user question: ").append(clean(q)).toString();}
 private void serverSettings(){
  LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(20),dp(8),dp(20),0);
  TextView note=text("اپنے KUN server کا HTTPS address درج کریں۔ Token صرف اس session میں رہے گا؛ app بند ہونے پر دوبارہ درج کریں۔",16,INK);form.addView(note);
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
  final String base=serverUrl,key=sessionToken;pendingQuestion=q;getPreferences(0).edit().putString("draft",q).commit();notice.setVisibility(View.GONE);render();setBusy(true);status.setText(remote?"SERVER • جواب تیار ہو رہا ہے…":"LOCAL • فون پر جواب تیار ہو رہا ہے…");
  worker.execute(()->{try{
   String answer;
   if(remote){answer=RemoteClient.generate(base,key,history,q);}
   else {getPreferences(0).edit().putString("crashStage","Token counting").commit();int start=Math.max(0,history.length()-8);if(start%2!=0)start++;String p=prompt(q,start);while(engine.sizeInTokens(p)>1400&&start<history.length()){start=Math.min(history.length(),start+2);p=prompt(q,start);}if(engine.sizeInTokens(p)>1400)throw new IllegalArgumentException("سوال بہت طویل ہے؛ مختصر کریں۔");getPreferences(0).edit().putString("crashStage","Generating response GPU").commit();answer=engine.generateResponse(p);}
   if(answer==null||answer.trim().isEmpty())throw new IOException("خالی جواب؛ دوبارہ کوشش کریں۔");
   final String result=answer;ui(()->{pendingQuestion=null;append("user",q);append("model",result);input.setText("");saveHistory();render();status.setText(remote?"SERVER • جواب تیار ہے":"LOCAL • جواب تیار ہے");});
  }catch(Throwable e){ui(()->{pendingQuestion=null;render();status.setText("جواب مکمل نہیں ہوا۔ سوال محفوظ ہے۔");notice.setText("جواب نہیں آیا · دوبارہ کوشش کے لیے Send دبائیں");notice.setVisibility(View.VISIBLE);getPreferences(0).edit().putString("lastDiagnostic",safeError(e)).apply();});}finally{ui(()->setBusy(false));}});
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
 private void help(){new AlertDialog.Builder(this).setTitle("KUN • Chat 0.3").setMessage("Local mode میں Replit، login یا API key کی ضرورت نہیں۔ Server mode کے لیے آپ کا HTTPS backend اور الگ user token درکار ہے۔ خودکار cloud fallback نہیں ہوتا۔\n\n1. Model صفحہ کھولیں، شرائط قبول کرکے Gemma 3 1B کا MediaPipe .task model ڈاؤن لوڈ کریں۔\n2. KUN میں Model لوڈ کریں دبائیں اور فائل منتخب کریں۔\n3. Model تیار ہونے پر سوال بھیجیں۔\n\nApp میں model شامل نہیں۔ GGUF اور .litertlm یہاں نہیں چلتے۔ چند GB خالی جگہ درکار ہو سکتی ہے؛ درآمد کے وقت فائل کی نقل بنتی ہے۔\n\nگفتگو اور model app کی نجی storage میں ہیں؛ uninstall سے حذف ہوں گے۔ نئی گفتگو سے موجودہ chat محفوظ history میں چلی جائے گی۔ صرف حالیہ گفتگو model کو دی جاتی ہے۔\n\nیہ نیا trained model نہیں؛ منتخب model پر چلنے والا اپنا app ہے۔ فون پر رفتار، اردو معیار اور compatibility ابھی غیر مصدقہ ہیں۔").setPositiveButton("ٹھیک ہے",null).setNeutralButton("Model صفحہ",(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://huggingface.co/litert-community/Gemma3-1B-IT")));}catch(Exception e){toast("Browser دستیاب نہیں۔");}}).show();}
 @Override protected void onDestroy(){dead=true;pulseHandler.removeCallbacksAndMessages(null);worker.execute(()->{if(engine!=null){engine.close();engine=null;}});worker.shutdown();super.onDestroy();}
 private TextView modeLabel, notice, typingLabel;
 private Button historyButton;
 private String pendingQuestion=null;
 private final Handler pulseHandler=new Handler(Looper.getMainLooper());
 private int pulse=0;
 private final Runnable pulseTick=new Runnable(){public void run(){
  if(dead||typingLabel==null)return;
  String[] dots={"●  ·  ·","·  ●  ·","·  ·  ●"};
  typingLabel.setText("KUN  "+dots[pulse++%3]);pulseHandler.postDelayed(this,450);
 }};
 private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
 private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
 private TextView label(String s,int size,int color){TextView t=text(s,size,color);t.setFontFeatureSettings("kern");return t;}
 private GradientDrawable border(int color,int radius){GradientDrawable d=box(color,radius);d.setStroke(dp(1),Color.rgb(48,54,53));return d;}
 private void buildUi(){
  getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
  root=column();root.setBackgroundColor(BG);root.setPadding(dp(20),dp(8),dp(20),dp(8));setContentView(root);
  LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
  historyButton=button("☰");historyButton.setContentDescription("Chat history / پرانی گفتگو");header.addView(historyButton,lp(48,48));historyButton.setOnClickListener(v->showChats());
  LinearLayout identity=column();identity.setPadding(dp(10),0,0,0);
  TextView title=label("KUN",21,INK);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));identity.addView(title);
  modeLabel=label("●  ON DEVICE",10,LIME);identity.addView(modeLabel);header.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
  clear=button("＋");clear.setTextSize(25);clear.setContentDescription("New chat / نئی گفتگو");header.addView(clear,lp(48,48));clear.setOnClickListener(v->startFresh());
  settings=button("⋯");settings.setTextSize(25);settings.setContentDescription("Model and settings");LinearLayout.LayoutParams more=lp(48,48);more.leftMargin=dp(6);header.addView(settings,more);settings.setOnClickListener(v->showSettings());root.addView(header);
  status=label("Model منتخب کریں · Ready when you are",12,Color.rgb(156,168,161));status.setPadding(dp(4),dp(14),0,dp(8));status.setMaxLines(2);root.addView(status);
  notice=label("",13,Color.rgb(250,204,145));notice.setBackground(border(Color.rgb(42,33,24),12));notice.setPadding(dp(12),dp(10),dp(12),dp(10));notice.setVisibility(View.GONE);notice.setOnClickListener(v->showDiagnosticDetails());root.addView(notice);
  model=button("Model لوڈ کریں");model.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);});
  serverMode=new Switch(this);serverUrl=getPreferences(0).getString("serverUrl", "");
  serverMode.setOnCheckedChangeListener((buttonView,on)->{setBusy(busy);status.setText(on?"SERVER · سوال اور حالیہ گفتگو server کو بھیجی جائے گی":"LOCAL · گفتگو فون پر رہے گی");if(on&&sessionToken.isEmpty())serverSettings();});
  scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
  chat=column();chat.setPadding(0,dp(10),0,dp(20));scroll.addView(chat);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout composer=column();composer.setPadding(dp(12),dp(8),dp(10),dp(8));composer.setBackground(border(PANEL,25));
  input=new EditText(this);input.setTextColor(INK);input.setHintTextColor(Color.rgb(153,165,159));input.setHint("کچھ بھی پوچھیں…");input.setTextSize(17);input.setMinLines(1);input.setMaxLines(5);input.setGravity(Gravity.TOP|Gravity.START);input.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);input.setBackgroundColor(Color.TRANSPARENT);input.setPadding(dp(8),dp(10),dp(8),dp(10));composer.addView(input,lp(-1,-2));
  LinearLayout tools=new LinearLayout(this);tools.setGravity(Gravity.CENTER_VERTICAL);
  TextView mode=label("✦  KUN CHAT",11,Color.rgb(161,178,167));tools.addView(mode,new LinearLayout.LayoutParams(0,-2,1));
  send=button("↑");send.setTextSize(24);send.setTextColor(BG);send.setBackground(box(LIME,24));send.setContentDescription("Send message / بھیجیں");tools.addView(send,lp(48,48));send.setOnClickListener(v->generate());composer.addView(tools);root.addView(composer,lp(-1,-2));
  TextView foot=label("KUN غلطی کر سکتا ہے۔ اہم معلومات کی تصدیق کریں۔",10,Color.rgb(128,143,134));foot.setGravity(Gravity.CENTER);root.addView(foot,lp(-1,-2));
  input.setText(getPreferences(0).getString("draft", ""));
  input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){updateSend();}public void afterTextChanged(android.text.Editable e){getPreferences(0).edit().putString("draft",e.toString()).apply();}});
 }
 private void updateSend(){if(send==null||input==null)return;boolean ready=serverMode.isChecked()?(!serverUrl.isEmpty()&&!sessionToken.isEmpty()):engine!=null;boolean enabled=!busy&&ready&&input.getText().toString().trim().length()>0;send.setEnabled(enabled);send.setAlpha(enabled?1f:0.42f);}
 private void setBusy(boolean b){busy=b;model.setEnabled(!b);clear.setEnabled(!b);settings.setEnabled(!b);historyButton.setEnabled(!b);serverMode.setEnabled(!b);input.setEnabled(!b);send.setText(b?"···":"↑");modeLabel.setText(serverMode.isChecked()?"●  YOUR SERVER":"●  ON DEVICE");updateSend();}
 private void suggestion(LinearLayout row,String symbol,String title,String subtitle,String prompt){
  LinearLayout c=column();c.setPadding(dp(12),dp(14),dp(12),dp(14));c.setBackground(border(PANEL,18));
  TextView icon=label(symbol,23,LIME);c.addView(icon);TextView heading=label(title,15,INK);heading.setTypeface(Typeface.create("sans-serif-medium",0));c.addView(heading);c.addView(label(subtitle,11,Color.rgb(154,169,159)));
  LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(4),dp(4),dp(4),dp(4));row.addView(c,p);c.setContentDescription(title+". "+subtitle);c.setFocusable(true);c.setOnClickListener(v->{if(!busy){input.setText(prompt);input.setSelection(input.length());input.requestFocus();}});
 }
 private void render(){
  pulseHandler.removeCallbacks(pulseTick);typingLabel=null;chat.removeAllViews();
  if(history.length()==0&&pendingQuestion==null){
   LinearLayout welcome=column();welcome.setPadding(0,dp(24),0,dp(22));welcome.setGravity(Gravity.CENTER_HORIZONTAL);
   TextView mark=label("✦",44,LIME);mark.setGravity(Gravity.CENTER);GradientDrawable glow=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(40,68,55),Color.rgb(23,31,30)});glow.setCornerRadius(dp(26));glow.setStroke(dp(1),Color.rgb(57,91,72));mark.setBackground(glow);welcome.addView(mark,lp(80,80));
   TextView eyebrow=label("A LITTLE CURIOSITY. ENDLESS IDEAS.",9,Color.rgb(155,175,162));LinearLayout.LayoutParams ep=lp(-2,-2);ep.topMargin=dp(18);welcome.addView(eyebrow,ep);
   TextView hero=label("آج کیا تخلیق کریں؟",29,INK);hero.setTypeface(Typeface.create("sans-serif-medium",0));hero.setGravity(Gravity.CENTER);welcome.addView(hero);
   TextView sub=label("پوچھیں، سیکھیں، یا کسی خیال کو شکل دیں۔",14,Color.rgb(163,179,169));sub.setGravity(Gravity.CENTER);welcome.addView(sub);chat.addView(welcome,lp(-1,-2));
   LinearLayout r1=new LinearLayout(this);suggestion(r1,"✎","کچھ لکھیں","Write something","میرے لیے ایک مختصر اور مؤثر تعارفی پیغام لکھیں۔");suggestion(r1,"◇","نیا خیال","Explore an idea","میرے کاروبار کے لیے تین تخلیقی آئیڈیاز تجویز کریں۔");chat.addView(r1);
   LinearLayout r2=new LinearLayout(this);suggestion(r2,"⌘","Code سمجھیں","Learn step by step","Programming سیکھنے کا آسان طریقہ بتائیں۔");suggestion(r2,"↗","منصوبہ بنائیں","Make a clear plan","میرے دن کی بہتر منصوبہ بندی میں مدد کریں۔");chat.addView(r2);
  }
  for(int n=0;n<history.length();n++){JSONObject o=history.optJSONObject(n);if(o!=null)addBubble(o.optString("role"),o.optString("text"));}
  if(pendingQuestion!=null){addBubble("user",pendingQuestion);typingLabel=label("KUN  ●  ·  ·",14,LIME);typingLabel.setPadding(dp(8),dp(16),0,dp(20));chat.addView(typingLabel);pulseHandler.post(pulseTick);}
  if(history.length()>0||pendingQuestion!=null)scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));else scroll.post(()->scroll.scrollTo(0,0));
 }
 private void addBubble(String role,String content){
  boolean user=role.equals("user");LinearLayout outer=column();outer.setGravity(user?Gravity.END:Gravity.START);LinearLayout card=column();card.setPadding(dp(12),dp(9),dp(12),dp(9));
  card.setBackground(user?border(Color.rgb(36,48,43),20):box(BG,0));
  if(!user){TextView who=label("✦  KUN",12,LIME);who.setTypeface(Typeface.create("sans-serif-medium",0));card.addView(who);}
  TextView body=label(content,17,INK);body.setLineSpacing(dp(4),1.05f);body.setTextIsSelectable(true);card.addView(body);
  if(!user){Button copy=button("Copy  ⧉");copy.setTextSize(11);copy.setContentDescription("Copy answer");copy.setBackgroundColor(Color.TRANSPARENT);copy.setTextColor(Color.rgb(155,171,161));copy.setOnClickListener(v->{android.content.ClipboardManager cb=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cb.setPrimaryClip(ClipData.newPlainText("KUN answer",content));toast("جواب copy ہو گیا");});card.addView(copy,lp(88,48));}
  LinearLayout.LayoutParams cp=lp(user?-2:-1,-2);if(user)cp.leftMargin=dp(32);outer.addView(card,cp);LinearLayout.LayoutParams op=lp(-1,-2);op.bottomMargin=dp(18);chat.addView(outer,op);
 }
 private void showSettings(){
  Dialog sheet=new Dialog(this);LinearLayout panel=column();panel.setPadding(dp(24),dp(20),dp(24),dp(24));panel.setBackground(border(PANEL,26));
  panel.addView(label("آپ کی جگہ۔ آپ کا AI۔",23,INK));panel.addView(label("KUN 0.3 · Model & connection",12,LIME));
  Button imp=button("Model لوڈ کریں  ↗");panel.addView(imp,lp(-1,54));imp.setOnClickListener(v->{sheet.dismiss();model.performClick();});
  panel.addView(label(modelFile().exists()?"Installed model · "+(modelFile().length()/1024/1024)+" MB":"ابھی کوئی model شامل نہیں",12,Color.LTGRAY));
  Button local=button("Local mode · فون پر گفتگو");panel.addView(local,lp(-1,54));local.setOnClickListener(v->{sheet.dismiss();serverMode.setChecked(false);});
  Button remote=button("Server mode · اپنا server");panel.addView(remote,lp(-1,54));remote.setOnClickListener(v->{sheet.dismiss();serverMode.setChecked(true);});
  Button connection=button("Server address اور token");panel.addView(connection,lp(-1,54));connection.setOnClickListener(v->{sheet.dismiss();serverSettings();});
  panel.addView(label("Server mode میں سوال اور حالیہ گفتگو آپ کے server کو بھیجی جاتی ہے۔",12,Color.LTGRAY));
  Button info=button("مدد اور diagnostics");panel.addView(info,lp(-1,54));info.setOnClickListener(v->{sheet.dismiss();showDiagnosticDetails();});
  sheet.setContentView(panel);Window w=sheet.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setGravity(Gravity.BOTTOM);w.setLayout(-1,-2);}sheet.show();if(w!=null)w.setLayout(-1,-2);
 }
 private File archivesFile(){return new File(getFilesDir(),"threads.json");}
 private JSONArray readArchives()throws Exception{File f=archivesFile();if(!f.exists())return new JSONArray();try(FileInputStream in=new FileInputStream(f)){return new JSONArray(new String(readBytes(in),StandardCharsets.UTF_8));}}
 private void writeArchives(JSONArray a)throws Exception{android.util.AtomicFile f=new android.util.AtomicFile(archivesFile());FileOutputStream out=null;try{out=f.startWrite();out.write(a.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}}
 private JSONObject currentThread()throws Exception{JSONObject o=new JSONObject();String title=history.length()>0?history.optJSONObject(0).optString("text","Chat"):"Chat";o.put("title",title.substring(0,Math.min(title.length(),60)));o.put("messages",new JSONArray(history.toString()));return o;}
 private void startFresh(){if(busy)return;try{if(history.length()>0){JSONArray a=readArchives();if(a.length()>=30){toast("30 محفوظ chats موجود ہیں۔ پہلے history سے ایک حذف کریں۔");return;}a.put(currentThread());writeArchives(a);}history=new JSONArray();pendingQuestion=null;input.setText("");saveHistory();render();}catch(Exception e){toast("گفتگو محفوظ نہیں ہوئی؛ نئی chat شروع نہیں کی گئی۔");}}
 private void showChats(){if(busy)return;try{JSONArray a=readArchives();if(a.length()==0){new AlertDialog.Builder(this).setTitle("آپ کی گفتگو").setMessage("نئی گفتگو شروع کرنے پر موجودہ chat یہاں محفوظ ہوگی۔").setPositiveButton("ٹھیک ہے",null).show();return;}String[] titles=new String[a.length()];for(int i=0;i<a.length();i++)titles[i]=a.getJSONObject(i).optString("title","Chat");new AlertDialog.Builder(this).setTitle("محفوظ گفتگو").setItems(titles,(d,index)->new AlertDialog.Builder(this).setTitle(titles[index]).setItems(new String[]{"گفتگو کھولیں","حذف کریں"},(dialog,action)->{try{if(action==0){JSONArray chosen=a.getJSONObject(index).getJSONArray("messages");a.remove(index);if(history.length()>0)a.put(currentThread());writeArchives(a);history=new JSONArray(chosen.toString());input.setText("");saveHistory();render();}else{new AlertDialog.Builder(this).setTitle("یہ گفتگو حذف کریں؟").setNegativeButton("واپس",null).setPositiveButton("حذف کریں",(x,y)->{try{a.remove(index);writeArchives(a);}catch(Exception e){toast("حذف نہیں ہوئی");}}).show();}}catch(Exception e){toast("گفتگو کھل نہیں سکی");}}).show()).setNegativeButton("واپس",null).show();}catch(Exception e){toast("History پڑھی نہیں جا سکی؛ فائل برقرار ہے۔");}}
 private void showLastExit(){try{ActivityManager am=(ActivityManager)getSystemService(ACTIVITY_SERVICE);java.util.List<ApplicationExitInfo> exits=am.getHistoricalProcessExitReasons(null,0,1);if(exits.isEmpty())return;ApplicationExitInfo e=exits.get(0);int r=e.getReason();if(r!=ApplicationExitInfo.REASON_CRASH_NATIVE&&r!=ApplicationExitInfo.REASON_CRASH&&r!=ApplicationExitInfo.REASON_ANR&&r!=ApplicationExitInfo.REASON_LOW_MEMORY)return;if(e.getTimestamp()<=getPreferences(0).getLong("lastExitSeen",0))return;getPreferences(0).edit().putLong("lastExitSeen",e.getTimestamp()).putString("lastDiagnostic","Reason: "+r+"\nStage: "+getPreferences(0).getString("crashStage","Unknown")+"\n"+e.getDescription()).apply();notice.setText("پچھلی کوشش مکمل نہیں ہوئی · تفصیل دیکھیں");notice.setVisibility(View.VISIBLE);}catch(Exception ignored){}}
 private void showDiagnosticDetails(){new AlertDialog.Builder(this).setTitle("KUN · مدد").setMessage("Local: موزوں Gemma 3 1B .task model لوڈ کریں۔\nServer: اپنا HTTPS address اور user token درج کریں۔\n\nNative engine crash کی اصلاح ابھی تصدیق شدہ نہیں ہے۔\n\n"+getPreferences(0).getString("lastDiagnostic","کوئی crash record نہیں۔")).setPositiveButton("ٹھیک ہے",null).setNeutralButton("مزید مدد",(d,w)->help()).show();}

}
