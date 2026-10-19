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


public class MainActivity extends Activity {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private volatile KunModel engine;
 private TaskRouter router;private MemoryBank memories;private boolean memoryReadable=true;
 private String selectedMode="general";
 private boolean remote(){return getPreferences(0).getBoolean("remote",false);}
 private String endpoint(){return getPreferences(0).getString("endpoint","");}
 private String remoteModel(){return getPreferences(0).getString("remoteModel","");}
 private String channel(){return remote()?endpoint()+"/"+remoteModel():"experimental";}
 private boolean busy=false; private volatile boolean dead=false;
 private LinearLayout chat, root; private ScrollView scroll; private EditText input;
 private TextView status; private Button send, model, clear, settings;
 
 private JSONArray history=new JSONArray();
 private final int BG=Color.rgb(16,20,19), PANEL=Color.rgb(25,32,29), INK=Color.rgb(241,245,242), LIME=Color.rgb(172,241,201);
 private File modelFile(){return new File(getFilesDir(),"kun-model.json");}
 private File historyFile(){return new File(getFilesDir(),"chat.json");}
 private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n);}
 private GradientDrawable box(int c,int r){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));return d;}
 private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(6),dp(6),dp(6),dp(6));t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);return t;}
 private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(INK);b.setBackground(box(PANEL,14));return b;}
 private void ui(Runnable r){runOnUiThread(()->{if(!dead&&!isFinishing())r.run();});}
 @Override public void onCreate(Bundle state){super.onCreate(state);buildUi();loadSmartData();loadHistory();render();setBusy(false);if(remote())status.setText("PC MODEL • "+remoteModel());showLastExit();
  File recovery=new File(getFilesDir(),"previous-kun-model.json");if(!modelFile().exists()&&recovery.exists())recovery.renameTo(modelFile());
  if(modelFile().exists()){setBusy(true);status.setText("Model کھل رہا ہے…");worker.execute(()->{try{engine=openModel(modelFile());ui(()->status.setText(remote()?"PC MODEL • "+remoteModel():"OFFLINE • تجرباتی model تیار ہے"));}catch(Throwable e){ui(()->status.setText("Model نہیں کھلا۔ مدد دیکھیں یا دوسرا model لوڈ کریں۔"));}finally{ui(()->setBusy(false));}});}
 }
 private KunModel openModel(File f)throws Exception{
  if(f.length()<100 || f.length()>2_000_000)throw new IOException("KUN model JSON must be under 2 MB.");
  JSONObject saved;try(InputStream in=new FileInputStream(f)){saved=new JSONObject(new String(readBytes(in),StandardCharsets.UTF_8));}
  JSONObject m=saved.getJSONObject("model");
  if(m.getInt("c")!=8||m.getInt("e")!=8||m.getInt("h")!=32)throw new IOException("Unsupported KUN model dimensions.");
  JSONArray v=m.getJSONArray("vocab");if(v.length()<1||v.length()>512)throw new IOException("Invalid vocabulary size.");
  String[] vocab=new String[v.length()];for(int i=0;i<vocab.length;i++)vocab[i]=v.getString(i);
  return new KunModel(vocab,matrix(m.getJSONArray("E"),v.length(),8),matrix(m.getJSONArray("W"),32,64),vector(m.getJSONArray("b"),32),matrix(m.getJSONArray("O"),v.length(),32),vector(m.getJSONArray("d"),v.length()));
 }
 private double[] vector(JSONArray a,int n)throws Exception{if(a.length()!=n)throw new IOException("Wrong model dimensions.");double[] r=new double[n];for(int i=0;i<n;i++)r[i]=a.getDouble(i);return r;}
 private double[][] matrix(JSONArray a,int rows,int cols)throws Exception{if(a.length()!=rows)throw new IOException("Wrong model dimensions.");double[][] r=new double[rows][];for(int i=0;i<rows;i++)r[i]=vector(a.getJSONArray(i),cols);return r;}
 private void append(String role,String content){try{JSONObject o=new JSONObject();o.put("role",role);o.put("text",content);o.put("channel",channel());history.put(o);while(history.length()>100)history.remove(0);}catch(JSONException ignored){} }
 private void generate(){
  if(busy)return;
  String q=input.getText().toString().trim();if(q.isEmpty())return;if(q.length()>5000){toast("سوال مختصر کریں (زیادہ سے زیادہ 5000 حروف)۔");return;}
  if(!remote()&&engine==null){try{String saved=memoryReadable&&memories!=null&&getPreferences(0).getBoolean("useMemory",false)?memories.exact(q):null;if(saved!=null){append("user",q);append("model","محفوظ جواب — آپ کی سکھائی ہوئی مثال:\n"+saved);input.setText("");saveHistory();render();status.setText("SAVED EXAMPLE • offline · نیا AI جواب نہیں");return;}}catch(Exception e){toast(safeError(e));}configureServer();return;}
  final boolean useRemote=remote();final String base=endpoint(),name=remoteModel();
  String preferred=getPreferences(0).getString("taskMode","auto");selectedMode="auto".equals(preferred)?(router==null?"general":router.route(q)):preferred;
  JSONArray notes=new JSONArray();try{if(memoryReadable&&memories!=null&&getPreferences(0).getBoolean("useMemory",false))notes=memories.relevant(q);}catch(Exception e){toast("Memory نہیں پڑھی جا سکی؛ سوال memory کے بغیر بھیجا جائے گا۔");}
  final String systemPrompt=SmartPrompt.build(selectedMode,notes);
  final JSONArray context=OllamaClient.context(history,channel());
  pendingQuestion=q;getPreferences(0).edit().putString("draft",q).commit();notice.setVisibility(View.GONE);render();setBusy(true);status.setText(useRemote?"PC MODEL • جواب بن رہا ہے…":"LOCAL • Experimental text بن رہا ہے…");
  worker.execute(()->{try{
   getPreferences(0).edit().putString("crashStage","KUN Java generation").commit();
   String answer=useRemote?OllamaClient.generate(base,name,context,q,systemPrompt):engine.generateResponse(q);
   if(answer==null||answer.trim().isEmpty())throw new IOException("خالی جواب؛ دوبارہ کوشش کریں۔");
   final String result=answer;ui(()->{pendingQuestion=null;append("user",q);append("model",result);input.setText("");saveHistory();render();status.setText(useRemote?"PC MODEL • "+name+" · "+selectedMode:"LOCAL • Experimental text · جواب کی ضمانت نہیں");});
  }catch(Throwable e){ui(()->{pendingQuestion=null;render();status.setText("جواب مکمل نہیں ہوا۔ سوال محفوظ ہے۔");notice.setText("جواب نہیں آیا: "+safeError(e)+" · سوال محفوظ ہے");notice.setVisibility(View.VISIBLE);getPreferences(0).edit().putString("lastDiagnostic",safeError(e)).apply();});}finally{ui(()->setBusy(false));}});
 }

 private String safeError(Throwable e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m.substring(0,Math.min(m.length(),350));}
 @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(req==20){if(res==RESULT_OK&&data!=null&&data.getData()!=null)exportExamples(data.getData());return;}if(req!=10||res!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();setBusy(true);status.setText("Model فائل نقل ہو رہی ہے…");worker.execute(()->{File tmp=new File(getFilesDir(),"import-kun-model.json"),backup=new File(getFilesDir(),"previous-kun-model.json");boolean moved=false, installedNew=false;try{
  try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(tmp)){if(in==null)throw new IOException("فائل نہیں کھلی");byte[] buffer=new byte[1024*1024];long total=0;int count;while((count=in.read(buffer))!=-1){total+=count;if(total>2_000_000)throw new IOException("KUN trainer کی 2 MB سے چھوٹی model.json منتخب کریں۔");out.write(buffer,0,count);}out.getFD().sync();if(total<100)throw new IOException("Model JSON خالی یا نامکمل ہے۔");}
  KunModel candidate=openModel(tmp);ui(()->status.setText("Model محفوظ ہو رہا ہے…"));
  if(modelFile().exists()){if(backup.exists())backup.delete();if(!modelFile().renameTo(backup))throw new IOException("پچھلا model محفوظ نہیں ہوا");moved=true;}
  if(!tmp.renameTo(modelFile()))throw new IOException("Model محفوظ نہیں ہوا");installedNew=true;engine=candidate;getPreferences(0).edit().putBoolean("remote",false).apply();backup.delete();ui(()->status.setText(remote()?"PC MODEL • "+remoteModel():"OFFLINE • تجرباتی model تیار ہے"));
 }catch(Throwable e){tmp.delete();if(moved&&backup.exists()){modelFile().delete();backup.renameTo(modelFile());}else if(installedNew&&engine==null){modelFile().delete();}if(engine==null&&modelFile().exists())try{engine=openModel(modelFile());}catch(Throwable ignored){}ui(()->{status.setText(engine==null?"Model لوڈ نہیں ہوا":"پچھلا model بحال ہے");new AlertDialog.Builder(this).setTitle("موزوں model منتخب کریں").setMessage("KUN-Auto-Train کے output folder سے model.json منتخب کریں۔ پرانی .task، GGUF اور .litertlm فائلیں اس ورژن میں نہیں چلتیں۔\n\n"+safeError(e)).setPositiveButton("ٹھیک ہے",null).show();});}finally{ui(()->setBusy(false));}});}
 private void saveHistory(){try{android.util.AtomicFile af=new android.util.AtomicFile(historyFile());FileOutputStream out=null;try{out=af.startWrite();out.write(history.toString().getBytes(StandardCharsets.UTF_8));af.finishWrite(out);}catch(Exception e){if(out!=null)af.failWrite(out);throw e;}}catch(Exception e){toast("گفتگو محفوظ نہیں ہوئی۔");}}
 private void loadHistory(){try{if(historyFile().exists()&&historyFile().length()<2_000_000){try(FileInputStream in=new FileInputStream(historyFile())){history=new JSONArray(new String(readBytes(in),StandardCharsets.UTF_8));}}}catch(Exception e){history=new JSONArray();toast("پچھلی گفتگو نہیں پڑھی جا سکی۔");}}
 private byte[] readBytes(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}
 private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 private void help(){new AlertDialog.Builder(this).setTitle("KUN 0.6 · Experimental")
 .setMessage("PC AI mode اپنے Ollama server سے حقیقی model کا جواب لیتا ہے۔ Offline mode چھوٹا تجرباتی Java text model ہے۔\n\nPC پر KUN-Auto-Train چلائیں، output/model.json فون پر منتقل کریں، پھر Model لوڈ کریں۔\n\nیہ صرف تجرباتی text generator ہے، مکمل سوال جواب والا AI نہیں۔ جواب بے معنی بھی ہو سکتا ہے۔ پرانا .task model استعمال نہیں ہوتا اور حذف نہیں کیا گیا۔\n\nگفتگو app کی نجی storage میں ہے؛ uninstall سے حذف ہوگی۔")
 .setPositiveButton("ٹھیک ہے",null).show();}
 @Override protected void onDestroy(){dead=true;pulseHandler.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy();}
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
  modeLabel=label("●  EXPERIMENTAL · OFFLINE",10,LIME);identity.addView(modeLabel);header.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
  clear=button("＋");clear.setTextSize(25);clear.setContentDescription("New chat / نئی گفتگو");header.addView(clear,lp(48,48));clear.setOnClickListener(v->startFresh());
  settings=button("⋯");settings.setTextSize(25);settings.setContentDescription("Model and settings");LinearLayout.LayoutParams more=lp(48,48);more.leftMargin=dp(6);header.addView(settings,more);settings.setOnClickListener(v->showSettings());root.addView(header);
  status=label("⋯ سے PC AI جوڑیں یا تجرباتی model لوڈ کریں",12,Color.rgb(156,168,161));status.setPadding(dp(4),dp(14),0,dp(8));status.setMaxLines(2);root.addView(status);
  notice=label("",13,Color.rgb(250,204,145));notice.setBackground(border(Color.rgb(42,33,24),12));notice.setPadding(dp(12),dp(10),dp(12),dp(10));notice.setVisibility(View.GONE);notice.setOnClickListener(v->showDiagnosticDetails());root.addView(notice);
  model=button("Model لوڈ کریں");model.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(i,10);}catch(ActivityNotFoundException e){toast("فائل منتخب کرنے والی app دستیاب نہیں۔");}});
  scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
  chat=column();chat.setPadding(0,dp(10),0,dp(20));scroll.addView(chat);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout composer=column();composer.setPadding(dp(12),dp(8),dp(10),dp(8));composer.setBackground(border(PANEL,25));
  input=new EditText(this);input.setTextColor(INK);input.setHintTextColor(Color.rgb(153,165,159));input.setHint("کچھ بھی پوچھیں…");input.setTextSize(17);input.setMinLines(1);input.setMaxLines(5);input.setGravity(Gravity.TOP|Gravity.START);input.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);input.setBackgroundColor(Color.TRANSPARENT);input.setPadding(dp(8),dp(10),dp(8),dp(10));composer.addView(input,lp(-1,-2));
  LinearLayout tools=new LinearLayout(this);tools.setGravity(Gravity.CENTER_VERTICAL);
  TextView mode=label("✦  KUN CHAT",11,Color.rgb(161,178,167));tools.addView(mode,new LinearLayout.LayoutParams(0,-2,1));
  send=button("↑");send.setTextSize(24);send.setTextColor(BG);send.setBackground(box(LIME,24));send.setContentDescription("Send message / بھیجیں");tools.addView(send,lp(48,48));send.setOnClickListener(v->generate());composer.addView(tools);root.addView(composer,lp(-1,-2));
  TextView foot=label("PC AI: کنکشن ضروری · Offline: تجرباتی متن",10,Color.rgb(128,143,134));foot.setGravity(Gravity.CENTER);root.addView(foot,lp(-1,-2));
  input.setText(getPreferences(0).getString("draft", ""));
  input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){updateSend();}public void afterTextChanged(android.text.Editable e){getPreferences(0).edit().putString("draft",e.toString()).apply();}});
 }
 private void updateSend(){if(send==null||input==null)return;boolean ready=true;boolean enabled=!busy&&ready&&input.getText().toString().trim().length()>0;send.setEnabled(enabled);send.setAlpha(enabled?1f:0.42f);}
 private void setBusy(boolean b){busy=b;model.setEnabled(!b);clear.setEnabled(!b);settings.setEnabled(!b);historyButton.setEnabled(!b);input.setEnabled(!b);send.setText(b?"···":"↑");modeLabel.setText(remote()?"●  PC MODEL · NETWORK":"●  EXPERIMENTAL · OFFLINE");updateSend();}
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
   TextView eyebrow=label("YOUR MODEL. ON YOUR PHONE.",9,Color.rgb(155,175,162));LinearLayout.LayoutParams ep=lp(-2,-2);ep.topMargin=dp(18);welcome.addView(eyebrow,ep);
   TextView hero=label(remote()?"کچھ پوچھیں":"KUN کو AI سے جوڑیں",29,INK);hero.setTypeface(Typeface.create("sans-serif-medium",0));hero.setGravity(Gravity.CENTER);welcome.addView(hero);
   TextView sub=label(remote()?"PC model: "+remoteModel():"⋯ → Connect PC AI · یا offline تجرباتی model",14,Color.rgb(163,179,169));sub.setGravity(Gravity.CENTER);welcome.addView(sub);chat.addView(welcome,lp(-1,-2));
   LinearLayout r1=new LinearLayout(this);suggestion(r1,"✎","کچھ لکھیں","Write something","میرے لیے ایک مختصر اور مؤثر تعارفی پیغام لکھیں۔");suggestion(r1,"◇","نیا خیال","Explore an idea","میرے کاروبار کے لیے تین تخلیقی آئیڈیاز تجویز کریں۔");chat.addView(r1);
   LinearLayout r2=new LinearLayout(this);suggestion(r2,"⌘","Code سمجھیں","Learn step by step","Programming سیکھنے کا آسان طریقہ بتائیں۔");suggestion(r2,"↗","منصوبہ بنائیں","Make a clear plan","میرے دن کی بہتر منصوبہ بندی میں مدد کریں۔");chat.addView(r2);
  }
  for(int n=0;n<history.length();n++){JSONObject o=history.optJSONObject(n);if(o!=null){addBubble(o.optString("role"),o.optString("text"));if("model".equals(o.optString("role"))&&n>0){JSONObject previous=history.optJSONObject(n-1);if(previous!=null&&"user".equals(previous.optString("role"))){final String question=previous.optString("text"),answer=o.optString("text");Button teach=button("درست جواب سکھائیں / Correct & save");teach.setTextSize(12);teach.setOnClickListener(v->{if(!busy)teachExample(question,answer);});chat.addView(teach,lp(-1,48));}}}}
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
  panel.addView(label("آپ کی جگہ۔ آپ کا AI۔",23,INK));panel.addView(label("KUN 0.6 · PC AI + Offline experiment",12,LIME));
  Button smart=button("Smart mode / سکھائیں / یادداشت");panel.addView(smart,lp(-1,54));smart.setOnClickListener(v->{sheet.dismiss();showSmartSettings();});
  Button connect=button("Connect PC AI / Ollama");panel.addView(connect,lp(-1,54));connect.setOnClickListener(v->{sheet.dismiss();configureServer();});
  Button offline=button("Use offline experimental model");panel.addView(offline,lp(-1,54));offline.setOnClickListener(v->{getPreferences(0).edit().putBoolean("remote",false).apply();setBusy(false);status.setText("OFFLINE • تجرباتی model");sheet.dismiss();if(engine==null)model.performClick();});
  Button imp=button("Model لوڈ کریں  ↗");panel.addView(imp,lp(-1,54));imp.setOnClickListener(v->{sheet.dismiss();model.performClick();});
  panel.addView(label(modelFile().exists()?"Installed model · "+(modelFile().length()/1024/1024)+" MB":"ابھی کوئی model شامل نہیں",12,Color.LTGRAY));
  panel.addView(label("اپنی training والی model.json منتخب کریں۔ یہ تجرباتی text generator ہے۔",13,Color.LTGRAY));
  Button info=button("مدد اور diagnostics");panel.addView(info,lp(-1,54));info.setOnClickListener(v->{sheet.dismiss();showDiagnosticDetails();});
  sheet.setContentView(panel);Window w=sheet.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setGravity(Gravity.BOTTOM);w.setLayout(-1,-2);}sheet.show();if(w!=null)w.setLayout(-1,-2);
 }
 private File memoryFile(){return new File(getFilesDir(),"kun-examples.json");}
 private void loadSmartData(){
  try(InputStream in=getAssets().open("task-router.json")){router=new TaskRouter(new JSONObject(new String(readBytes(in),StandardCharsets.UTF_8)));}catch(Exception e){router=null;toast("Auto mode دستیاب نہیں؛ General mode استعمال ہوگا۔");}
  try{android.util.AtomicFile af=new android.util.AtomicFile(memoryFile());if(memoryFile().exists()||new File(memoryFile().getPath()+".bak").exists()){if(memoryFile().length()>1000000)throw new IOException("Memory file too large");try(InputStream in=af.openRead()){memories=new MemoryBank(new String(readBytes(in),StandardCharsets.UTF_8));}}else memories=new MemoryBank("[]");}catch(Exception e){memoryReadable=false;toast("Saved examples نہیں کھلیں؛ اصل فائل برقرار ہے۔");}
 }
 private void persistMemory(MemoryBank candidate)throws Exception{
  android.util.AtomicFile af=new android.util.AtomicFile(memoryFile());FileOutputStream out=null;
  try{out=af.startWrite();out.write(candidate.toString().getBytes(StandardCharsets.UTF_8));af.finishWrite(out);memories=candidate;}catch(Exception e){if(out!=null)af.failWrite(out);throw e;}
 }
 private void showSmartSettings(){
  if(busy)return;
  String[] choices={"Task mode: "+getPreferences(0).getString("taskMode","auto"),"Teach KUN / نئی مثال سکھائیں","Saved examples / دیکھیں یا حذف کریں","Use saved examples: "+(getPreferences(0).getBoolean("useMemory",false)?"ON":"OFF"),"Export reviewed examples (.jsonl)","Training کی حقیقت / معلومات"};
  new AlertDialog.Builder(this).setTitle("KUN Smart").setItems(choices,(d,index)->{
   if(index==0){String[] modes={"auto","architecture","business","coding","writing","general"};new AlertDialog.Builder(this).setTitle("Task mode").setItems(modes,(x,n)->{getPreferences(0).edit().putString("taskMode",modes[n]).apply();toast("Mode: "+modes[n]);}).show();}
   else if(index==1)teachExample("","");
   else if(index==2)showExamples();
   else if(index==3){boolean current=getPreferences(0).getBoolean("useMemory",false);if(current){getPreferences(0).edit().putBoolean("useMemory",false).apply();toast("Memory OFF");}else new AlertDialog.Builder(this).setTitle("Saved examples استعمال کریں؟").setMessage("متعلقہ محفوظ سوال اور جواب آپ کے منتخب PC model کو بھیجے جائیں گے۔ مثالیں اپنے فون پر محفوظ ہیں؛ cloud training میں خودکار upload نہیں ہوتیں۔").setNegativeButton("واپس",null).setPositiveButton("Enable",(x,y)->{getPreferences(0).edit().putBoolean("useMemory",true).apply();toast("Memory ON");}).show();}
   else if(index==4){if(!memoryReadable||memories==null||memories.size()==0){toast("کوئی محفوظ مثال نہیں۔");return;}Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.setType("application/json");intent.addCategory(Intent.CATEGORY_OPENABLE);intent.putExtra(Intent.EXTRA_TITLE,"KUN-reviewed-examples.jsonl");try{startActivityForResult(intent,20);}catch(ActivityNotFoundException e){toast("File picker دستیاب نہیں۔");}}
   else new AlertDialog.Builder(this).setTitle("کیا train ہوا؟").setMessage("200 synthetic سوالوں پر چھوٹا task classifier train ہوا ہے۔ یہ صرف سوال کی قسم پہچانتا ہے۔ 40 الگ synthetic سوالوں پر raw classification: 39/40؛ احتیاطی fallback کے ساتھ: 37/40۔ یہ حقیقی صارفین کی benchmark نہیں۔\n\nTeach KUN آپ کی درست مثالیں محفوظ اور retrieve کرتا ہے۔ بڑے chat model کے weights fine-tune نہیں ہوئے۔ AI جواب منتخب Ollama model دیتا ہے۔").setPositiveButton("ٹھیک ہے",null).show();
  }).show();
 }
 private void teachExample(String question,String answer){
  if(busy)return;if(!memoryReadable||memories==null){toast("Memory فائل میں خرابی ہے؛ اسے overwrite نہیں کیا گیا۔");return;}
  LinearLayout form=column();form.setPadding(dp(18),dp(8),dp(18),dp(8));
  form.addView(label("درست جواب خود review کریں۔ Save & use سے متعلقہ مثال منتخب PC model کو بھیجی جا سکتی ہے۔ یہ memory ہے، LLM training نہیں۔",13,INK));
  EditText q=new EditText(this),a=new EditText(this);q.setTextColor(INK);a.setTextColor(INK);q.setHintTextColor(Color.GRAY);a.setHintTextColor(Color.GRAY);q.setHint("سوال / موضوع (500 حروف)");a.setHint("درست جواب / معلومات (2000 حروف)");q.setText(question);a.setText(answer);q.setMaxLines(3);a.setMinLines(3);a.setMaxLines(7);q.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);a.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);form.addView(q);form.addView(a);
  ScrollView sc=new ScrollView(this);sc.addView(form);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Teach KUN").setView(sc).setNegativeButton("واپس",null).setPositiveButton("Save & use",null).create();
  dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{MemoryBank candidate=new MemoryBank(memories.toString());if(!question.isEmpty()&&!MemoryBank.normalize(question).equals(MemoryBank.normalize(q.getText().toString()))){for(int n=0;n<candidate.size();n++)if(MemoryBank.normalize(candidate.question(n)).equals(MemoryBank.normalize(question))){candidate.remove(n);break;}}candidate.put(q.getText().toString(),a.getText().toString());persistMemory(candidate);getPreferences(0).edit().putBoolean("useMemory",true).apply();toast("مثال محفوظ ہو گئی۔");dialog.dismiss();}catch(Exception e){a.setError(safeError(e));}}));dialog.show();
 }
 private void showExamples(){
  if(!memoryReadable||memories==null||memories.size()==0){toast("کوئی محفوظ مثال نہیں۔");return;}
  try{String[] titles=new String[memories.size()];for(int i=0;i<titles.length;i++)titles[i]=memories.question(i);new AlertDialog.Builder(this).setTitle("Saved examples").setItems(titles,(d,index)->{try{String q=memories.question(index),a=memories.answer(index);new AlertDialog.Builder(this).setTitle(q).setMessage(a).setPositiveButton("Edit",(x,y)->teachExample(q,a)).setNegativeButton("واپس",null).setNeutralButton("Delete",(x,y)->new AlertDialog.Builder(this).setTitle("یہ مثال حذف کریں؟").setNegativeButton("واپس",null).setPositiveButton("Delete",(c,v)->{try{MemoryBank candidate=new MemoryBank(memories.toString());candidate.remove(index);persistMemory(candidate);toast("مثال حذف ہو گئی۔");}catch(Exception e){toast(safeError(e));}}).show()).show();}catch(Exception e){toast(safeError(e));}}).show();}catch(Exception e){toast(safeError(e));}
 }
 private void exportExamples(Uri uri){
  if(memories==null||!memoryReadable){toast("Memory دستیاب نہیں۔");return;}
  try{byte[] data=memories.exportJsonl().getBytes(StandardCharsets.UTF_8);try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("فائل نہیں کھلی");out.write(data);}toast("Reviewed examples export ہو گئیں۔");}catch(Exception e){toast("Export ناکام: "+safeError(e));}
 }
 private void configureServer(){
  if(busy)return;
  LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),dp(8));
  form.addView(label("اپنے PC پر Ollama چلائیں۔ فون اور PC ایک trusted Wi-Fi پر ہوں۔ آپ کے سوال، اسی model کی حالیہ گفتگو، اور فعال ہونے پر متعلقہ محفوظ مثالیں منتخب PC کو بھیجی جائیں گی۔",14,INK));
  EditText address=new EditText(this);address.setSingleLine(true);address.setTextColor(INK);address.setHintTextColor(Color.GRAY);address.setHint("http://192.168.1.10:11434");address.setText(endpoint());form.addView(address);
  AlertDialog d=new AlertDialog.Builder(this).setTitle("Connect PC AI").setView(form).setNegativeButton("واپس",null).setPositiveButton("Connect",null).create();
  d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
   final String base;try{base=OllamaClient.validateBase(address.getText().toString().trim());}catch(Exception e){address.setError(safeError(e));return;}
   d.dismiss();setBusy(true);status.setText("PC کے models دیکھ رہے ہیں…");
   worker.execute(()->{try{String[] names=OllamaClient.models(base);ui(()->{
    new AlertDialog.Builder(this).setTitle("AI model منتخب کریں").setItems(names,(dialog,index)->{
     getPreferences(0).edit().putString("endpoint",base).putString("remoteModel",names[index]).putBoolean("remote",true).apply();setBusy(false);status.setText("PC MODEL • "+names[index]);
    }).setNegativeButton("واپس",null).show();
   });}catch(Exception e){ui(()->{status.setText("PC سے رابطہ نہیں ہوا");new AlertDialog.Builder(this).setTitle("Connection error").setMessage(safeError(e)+"\n\nPC IP، Ollama، Wi-Fi اور firewall چیک کریں۔").setPositiveButton("ٹھیک ہے",null).show();});}finally{ui(()->setBusy(false));}});
  }));d.show();
 }
 private File archivesFile(){return new File(getFilesDir(),"threads.json");}
 private JSONArray readArchives()throws Exception{File f=archivesFile();if(!f.exists())return new JSONArray();try(FileInputStream in=new FileInputStream(f)){return new JSONArray(new String(readBytes(in),StandardCharsets.UTF_8));}}
 private void writeArchives(JSONArray a)throws Exception{android.util.AtomicFile f=new android.util.AtomicFile(archivesFile());FileOutputStream out=null;try{out=f.startWrite();out.write(a.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}}
 private JSONObject currentThread()throws Exception{JSONObject o=new JSONObject();String title=history.length()>0?history.optJSONObject(0).optString("text","Chat"):"Chat";o.put("title",title.substring(0,Math.min(title.length(),60)));o.put("messages",new JSONArray(history.toString()));return o;}
 private void startFresh(){if(busy)return;try{if(history.length()>0){JSONArray a=readArchives();if(a.length()>=30){toast("30 محفوظ chats موجود ہیں۔ پہلے history سے ایک حذف کریں۔");return;}a.put(currentThread());writeArchives(a);}history=new JSONArray();pendingQuestion=null;input.setText("");saveHistory();render();}catch(Exception e){toast("گفتگو محفوظ نہیں ہوئی؛ نئی chat شروع نہیں کی گئی۔");}}
 private void showChats(){if(busy)return;try{JSONArray a=readArchives();if(a.length()==0){new AlertDialog.Builder(this).setTitle("آپ کی گفتگو").setMessage("نئی گفتگو شروع کرنے پر موجودہ chat یہاں محفوظ ہوگی۔").setPositiveButton("ٹھیک ہے",null).show();return;}String[] titles=new String[a.length()];for(int i=0;i<a.length();i++)titles[i]=a.getJSONObject(i).optString("title","Chat");new AlertDialog.Builder(this).setTitle("محفوظ گفتگو").setItems(titles,(d,index)->new AlertDialog.Builder(this).setTitle(titles[index]).setItems(new String[]{"گفتگو کھولیں","حذف کریں"},(dialog,action)->{try{if(action==0){JSONArray chosen=a.getJSONObject(index).getJSONArray("messages");a.remove(index);if(history.length()>0)a.put(currentThread());writeArchives(a);history=new JSONArray(chosen.toString());input.setText("");saveHistory();render();}else{new AlertDialog.Builder(this).setTitle("یہ گفتگو حذف کریں؟").setNegativeButton("واپس",null).setPositiveButton("حذف کریں",(x,y)->{try{a.remove(index);writeArchives(a);}catch(Exception e){toast("حذف نہیں ہوئی");}}).show();}}catch(Exception e){toast("گفتگو کھل نہیں سکی");}}).show()).setNegativeButton("واپس",null).show();}catch(Exception e){toast("History پڑھی نہیں جا سکی؛ فائل برقرار ہے۔");}}
 private void showLastExit(){try{ActivityManager am=(ActivityManager)getSystemService(ACTIVITY_SERVICE);java.util.List<ApplicationExitInfo> exits=am.getHistoricalProcessExitReasons(null,0,1);if(exits.isEmpty())return;ApplicationExitInfo e=exits.get(0);int r=e.getReason();if(r!=ApplicationExitInfo.REASON_CRASH_NATIVE&&r!=ApplicationExitInfo.REASON_CRASH&&r!=ApplicationExitInfo.REASON_ANR&&r!=ApplicationExitInfo.REASON_LOW_MEMORY)return;if(e.getTimestamp()<=getPreferences(0).getLong("lastExitSeen",0))return;getPreferences(0).edit().putLong("lastExitSeen",e.getTimestamp()).putString("lastDiagnostic","Reason: "+r+"\nStage: "+getPreferences(0).getString("crashStage","Unknown")+"\n"+e.getDescription()).apply();notice.setText("پچھلی کوشش مکمل نہیں ہوئی · تفصیل دیکھیں");notice.setVisibility(View.VISIBLE);}catch(Exception ignored){}}
 private void showDiagnosticDetails(){new AlertDialog.Builder(this).setTitle("KUN · مدد")
 .setMessage("KUN 0.6: اپنا Java model runtime۔ پرانا native runtime ہٹا دیا گیا ہے۔ Redmi پر اس build کی جانچ ابھی درکار ہے۔\n\nپرانا diagnostic record:\n"+getPreferences(0).getString("lastDiagnostic","کوئی crash record نہیں۔"))
 .setPositiveButton("ٹھیک ہے",null).setNeutralButton("مزید مدد",(d,w)->help()).show();}


}

