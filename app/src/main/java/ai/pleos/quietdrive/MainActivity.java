package ai.pleos.quietdrive;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(12,18,27), PANEL = Color.rgb(22,31,43), INK = Color.rgb(241,246,250), MUTED = Color.rgb(151,167,184), MINT = Color.rgb(118,226,196), AMBER = Color.rgb(255,198,116);
    private AutomationService service;
    private AutomationService.Snapshot state;
    private boolean rendering, bound;
    private TextView mode, headline, detail, reason, meter, connection, mic, bottom, gear;
    private Switch conversation, reversing, solo;
    private Button pause, demoSpeech, demoReverse, settings, status;
    private LinearLayout demoPanel;
    private SeekBar manual;
    private ProgressBar progress;
    private CabinView cabin;
    private float uiScale = 1f;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((AutomationService.LocalBinder)binder).service();
            service.observe(MainActivity.this::render);
        }
        public void onServiceDisconnected(ComponentName name) { service = null; connection.setText("차량 연결이 끊겼습니다. 앱을 다시 열어 주세요."); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        android.content.res.Configuration screen = getResources().getConfiguration();
        uiScale = Math.max(1f, Math.min(1.5f, Math.min(screen.screenWidthDp / 1100f, screen.screenHeightDp / 740f)));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildScreen();
    }
    @Override protected void onStart() {
        super.onStart(); bound = bindService(new Intent(this, AutomationService.class), serviceConnection, BIND_AUTO_CREATE);
    }
    @Override protected void onStop() {
        if (service != null) service.detach();
        if (bound) { unbindService(serviceConnection); bound = false; }
        service = null; super.onStop();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout root = column(); root.setPadding(dp(30),dp(20),dp(30),dp(18)); scroll.addView(root);
        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = column();
        TextView wordmark = text("Quiet Drive", 27, INK); wordmark.setTypeface(null, Typeface.BOLD); brand.addView(wordmark);
        TextView eyebrow = text("PLEOS  /  AUDIO COMPANION", 10, MUTED); eyebrow.setLetterSpacing(.16f); brand.addView(eyebrow);
        header.addView(brand, new LinearLayout.LayoutParams(0,-2,1));
        mode = text("차량 연동 확인", 12, MINT); mode.setPadding(dp(15),dp(10),dp(15),dp(10)); mode.setBackground(round(PANEL,20)); header.addView(mode);
        status = button("연동 상태 · 시연", this::showStatus); header.addView(status, margins(-2,48,12,0,0,0));
        settings = button("설정", this::showSettings); header.addView(settings, margins(-2,48,8,0,0,0));
        root.addView(header);

        LinearLayout title = row(); title.setGravity(Gravity.CENTER_VERTICAL); title.setPadding(0,dp(23),0,dp(20));
        LinearLayout titleCopy = column();
        headline = text("운전에 집중하세요.", 29, INK); headline.setTypeface(null, Typeface.BOLD); titleCopy.addView(headline);
        detail = text("소리는 알아서 맞출게요.", 14, MUTED); detail.setPadding(0,dp(5),0,0); titleCopy.addView(detail);
        title.addView(titleCopy,new LinearLayout.LayoutParams(0,-2,1));
        pause = button("자동 조절 일시 중지", () -> { if(service!=null && state!=null) service.setPaused(!state.paused); });
        title.addView(pause,margins(-2,52,8,0,0,0)); root.addView(title);

        LinearLayout body = row(); body.setBaselineAligned(false);
        LinearLayout features = column();
        conversation = feature(features,"01", "대화 시 자동 음량 조절", "말하는 동안 낮추고, 조용해지면 천천히 복원", enabled -> toggleConversation(enabled));
        reversing = feature(features,"02", "후진 시 자동 음량 조절", "R단 진입부터 해제까지 · 안전 경고음은 그대로", enabled -> { if(service!=null) service.setReversing(enabled); });
        solo = feature(features,"03", "나만 듣기", "함께 탄 사람의 휴식을 위한 운전석 중심 사운드", enabled -> { if(service!=null) service.setSolo(enabled); });
        body.addView(features,new LinearLayout.LayoutParams(0,-2,1.23f));

        LinearLayout audio = column(); audio.setPadding(dp(24),dp(18),dp(24),dp(18)); audio.setBackground(round(PANEL,20));
        LinearLayout audioTitle = row(); audioTitle.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = text("지금의 소리",14,INK); caption.setTypeface(null,Typeface.BOLD); audioTitle.addView(caption,new LinearLayout.LayoutParams(0,-2,1));
        gear = text("연결 확인 중",11,MUTED); audioTitle.addView(gear); audio.addView(audioTitle);
        cabin = new CabinView(); audio.addView(cabin,new LinearLayout.LayoutParams(-1,dp(145)));
        reason = text("차량 API 확인 중",16,MINT); reason.setGravity(Gravity.CENTER); audio.addView(reason);
        meter = text("—",36,INK); meter.setTypeface(null,Typeface.BOLD); meter.setGravity(Gravity.CENTER); audio.addView(meter);
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(40);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(MINT)); audio.addView(progress,new LinearLayout.LayoutParams(-1,dp(5)));
        TextView media = text("현재 재생 중인 미디어",11,MUTED); media.setGravity(Gravity.CENTER); media.setPadding(0,dp(9),0,0); audio.addView(media);
        body.addView(audio,marginsWeighted(0,-2,18,0,0,0,1)); root.addView(body);

        LinearLayout health = column(); health.setPadding(dp(2),dp(13),dp(2),dp(7));
        connection = text("차량 연결을 확인하고 있습니다.",11,MUTED); health.addView(connection);
        mic = text("마이크 꺼짐 · 음성을 저장하지 않습니다",11,MUTED); mic.setPadding(0,dp(5),0,0); health.addView(mic); root.addView(health);

        demoPanel = column(); demoPanel.setPadding(dp(18),dp(12),dp(18),dp(12)); demoPanel.setBackground(round(Color.rgb(37,33,29),14));
        TextView demoLabel=text("화면 시연  /  아래 입력은 차량과 연결되지 않습니다",12,AMBER); demoLabel.setTypeface(null,Typeface.BOLD); demoPanel.addView(demoLabel);
        LinearLayout inputs=row(); inputs.setGravity(Gravity.CENTER_VERTICAL);
        demoSpeech=button("대화 시작",()->{ if(service!=null&&state!=null)service.demoSpeech(!state.speech); }); inputs.addView(demoSpeech,new LinearLayout.LayoutParams(0,dp(50),1));
        demoReverse=button("R단 진입",()->{ if(service!=null&&state!=null)service.demoReverse(!state.reverse); }); inputs.addView(demoReverse,marginsWeighted(0,50,8,0,0,0,1));
        Button position=button("소리 위치 직접 조절",this::showPosition); inputs.addView(position,marginsWeighted(0,50,8,0,0,0,1.3f)); demoPanel.addView(inputs);
        LinearLayout slider=row(); slider.setGravity(Gravity.CENTER_VERTICAL); slider.addView(text("음량 직접 조절",12,MUTED));
        manual=new SeekBar(this); manual.setMax(40); manual.setProgress(28);
        manual.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar v,int value,boolean fromUser){if(fromUser&&service!=null)service.demoVolume(value);}
            public void onStartTrackingTouch(SeekBar v){} public void onStopTrackingTouch(SeekBar v){}
        });slider.addView(manual,new LinearLayout.LayoutParams(0,dp(48),1));demoPanel.addView(slider);
        demoPanel.setVisibility(View.GONE);root.addView(demoPanel);
        bottom=text("권한이 확인된 기능만 실제 차량에 적용합니다.",11,MUTED);bottom.setGravity(Gravity.CENTER);bottom.setPadding(0,dp(12),0,0);root.addView(bottom);
        setContentView(scroll);
    }

    private interface Toggle { void set(boolean enabled); }
    private Switch feature(LinearLayout parent,String number,String title,String subtitle,Toggle toggle){
        LinearLayout card=row();card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(19),dp(15),dp(17),dp(15));card.setBackground(round(PANEL,16));
        TextView index=text(number,13,MINT);index.setTypeface(null,Typeface.BOLD);card.addView(index,margins(34,-2,0,0,9,0));
        LinearLayout copy=column();TextView name=text(title,18,INK);name.setTypeface(null,Typeface.BOLD);copy.addView(name);
        TextView sub=text(subtitle,11,MUTED);sub.setPadding(0,dp(7),dp(8),0);copy.addView(sub);card.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        Switch control=new Switch(this);control.setContentDescription(title);control.setMinHeight(dp(52));control.setMinWidth(dp(54));
        control.setOnCheckedChangeListener((v,checked)->{if(!rendering)toggle.set(checked);});card.addView(control);
        parent.addView(card,margins(-1,100,0,0,0,10));return control;
    }

    private void toggleConversation(boolean enabled){
        if(service==null)return;
        if(enabled&&state!=null&&!state.simulation&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            rendering=true;conversation.setChecked(false);rendering=false;
            new AlertDialog.Builder(this).setTitle("실험용 대화 감지")
                .setMessage("마이크로 소리의 활동 여부만 기기에서 판단합니다. 음성은 저장·전송·전사하지 않습니다. 음악 보컬이나 라디오를 발화로 오인할 수 있으며, 모든 좌석의 감지는 검증되지 않았습니다.\n\n차량 음량 권한이 없으면 감지 상태만 표시됩니다.")
                .setPositiveButton("마이크 권한 확인",(d,w)->{
                    if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS},10);
                    else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);
                }).setNegativeButton("취소",null).show();return;
        }
        service.setConversation(enabled);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==10&&service!=null&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)service.setConversation(true);
        else if(request==10)Toast.makeText(this,"마이크 권한을 허용해야 감지할 수 있습니다.",Toast.LENGTH_LONG).show();
    }

    private void render(AutomationService.Snapshot s){
        AutomationService.Snapshot previous=state;
        if(previous!=null&&sameDisplay(previous,s)){state=s;return;}
        state=s;rendering=true;
        conversation.setChecked(s.conversation);reversing.setChecked(s.reversing);solo.setChecked(s.solo);
        reversing.setEnabled(s.reversing||(s.audioAvailable&&s.gearAvailable));solo.setEnabled(s.simulation);
        pause.setText(s.paused?"자동 조절 다시 시작":"자동 조절 일시 중지");
        mode.setText(s.simulation?"화면 시연":s.audioAvailable?"차량 연동":"연동 권한 필요");mode.setTextColor(s.simulation||!s.audioAvailable?AMBER:MINT);
        headline.setText(s.paused?"잠시, 직접 맞추세요.":"운전에 집중하세요.");
        detail.setText(s.paused?"일시 중지 중 · 직접 조절한 설정을 존중합니다.":"소리는 알아서 맞출게요.");
        String why=s.paused?"자동 조절 일시 중지":s.manualOverride?"직접 조절한 음량 유지":s.reasons==3?"대화 · 후진 동시 적용":s.reasons==1?"대화 중":s.reasons==2?"후진 중":s.holding?"복원 대기 중":s.changing?"부드럽게 복원 중":s.solo?"운전석 중심으로 듣는 중":"편안한 기본 음량";
        if(!s.audioAvailable)why=s.speech?"발화 감지 · 음량 제어 대기":"차량 오디오 권한 필요";
        if(s.audioAvailable&&!s.paused&&!s.manualOverride&&s.holding){
            if(s.reasons==1&&!s.speech)why="대화 종료 · 복원 대기";
            else if(s.reasons==2&&!s.reverse)why="후진 해제 · 복원 대기";
        }
        reason.setText(why);reason.setTextColor(s.audioAvailable?MINT:AMBER);meter.setText(s.audioAvailable?String.format(Locale.KOREA,"%02d  /  %d",s.volume,s.maximum):"—");
        progress.setMax(s.maximum);progress.setProgress(s.audioAvailable?s.volume:0);
        gear.setText(s.gearStatus);connection.setText(s.audioStatus);mic.setText(s.micStatus+" · 음성 저장 없음");
        demoPanel.setVisibility(s.simulation?View.VISIBLE:View.GONE);manual.setProgress(s.volume);
        demoSpeech.setText(s.speech?"대화 끝내기":"대화 시작");demoReverse.setText(s.reverse?"P단으로 해제":"R단 진입");
        settings.setEnabled(s.parked);
        bottom.setText(!s.fault.isEmpty()?s.fault:s.simulation?"시연 중 · 실제 차량 오디오·기어·마이크를 사용하지 않습니다.":"실차 제어는 제조사 승인 필요 · 세부 설정은 P단 확인 후 사용할 수 있습니다.");
        bottom.setTextColor(!s.fault.isEmpty()||s.simulation?AMBER:MUTED);
        cabin.focus=s.solo&&!s.paused;cabin.fade=s.fade;cabin.balance=s.balance;cabin.invalidate();
        rendering=false;
    }

    private boolean sameDisplay(AutomationService.Snapshot a,AutomationService.Snapshot b){
        return a.simulation==b.simulation&&a.paused==b.paused&&a.conversation==b.conversation&&a.reversing==b.reversing&&a.solo==b.solo&&a.speech==b.speech&&a.reverse==b.reverse&&a.audioAvailable==b.audioAvailable&&a.gearAvailable==b.gearAvailable&&a.parked==b.parked&&a.manualOverride==b.manualOverride&&a.changing==b.changing&&a.holding==b.holding&&a.volume==b.volume&&a.maximum==b.maximum&&a.reasons==b.reasons&&a.fade==b.fade&&a.balance==b.balance&&a.focus==b.focus&&a.silenceMs==b.silenceMs&&a.conversationReduction==b.conversationReduction&&a.reverseReduction==b.reverseReduction&&a.audioStatus.equals(b.audioStatus)&&a.gearStatus.equals(b.gearStatus)&&a.micStatus.equals(b.micStatus)&&a.fault.equals(b.fault);
    }

    private void showStatus(){
        if(state==null||service==null)return;
        String message="오디오: "+state.audioStatus+"\n\n기어: "+state.gearStatus+"\n\n소리 위치: 공개 SDK에 읽기·복원 API 없음. 제조사 승인 연동이 필요합니다.\n\n마이크: "+state.micStatus+"\n\n화면 시연은 가상 입력과 가상 음량을 사용합니다. 차량이나 다른 앱의 음악을 바꾸지 않습니다.";
        new AlertDialog.Builder(this).setTitle("연동 상태와 시연") .setMessage(message)
            .setPositiveButton(state.simulation?"실제 연동으로 돌아가기":"화면 시연 시작",(d,w)->service.setSimulation(!state.simulation))
            .setNegativeButton("닫기",null).show();
    }

    private void showSettings(){
        if(state==null||!state.parked||service==null)return;
        LinearLayout content=column();content.setPadding(dp(24),dp(8),dp(24),dp(8));
        SeekBar c=setting(content,"대화 시 감소 정도",state.conversationReduction,10,90,"% 낮추기");
        SeekBar r=setting(content,"후진 시 감소 정도",state.reverseReduction,10,90,"% 낮추기");
        SeekBar wait=setting(content,"대화 종료 후 복원 대기",(int)state.silenceMs/1000,1,10,"초");
        SeekBar f=setting(content,"운전석 집중 정도",(int)Math.round(state.focus*100),10,90,"%");
        TextView note=text("정차 중에만 설정할 수 있습니다. 두 조건이 겹치면 더 강한 감소량을 한 번 적용합니다.",12,MUTED);content.addView(note);
        new AlertDialog.Builder(this).setTitle("내 차에 맞는 편안함").setView(content)
            .setPositiveButton("저장",(d,w)->{if(service!=null)service.settings(c.getProgress()+10,r.getProgress()+10,wait.getProgress()+1,f.getProgress()+10);})
            .setNegativeButton("취소",null).show();
    }
    private SeekBar setting(LinearLayout box,String name,int current,int min,int max,String suffix){
        TextView label=text(name+"  ·  "+current+suffix,14,INK);box.addView(label);
        SeekBar v=new SeekBar(this);v.setMax(max-min);v.setProgress(current-min);v.setContentDescription(name);
        v.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){label.setText(name+"  ·  "+(p+min)+suffix);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        box.addView(v,new LinearLayout.LayoutParams(-1,dp(45)));return v;
    }
    private void showPosition(){
        if(state==null||!state.simulation||service==null)return;
        LinearLayout box=column();box.setPadding(dp(24),dp(8),dp(24),dp(8));
        box.addView(text("시연 전용 · 직접 바꾼 축은 모드를 꺼도 유지합니다.",13,MUTED));
        SeekBar f=setting(box,"뒤  ←  페이더  →  앞",(int)(state.fade*100),-100,100,"");
        SeekBar b=setting(box,"왼쪽  ←  밸런스  →  오른쪽",(int)(state.balance*100),-100,100,"");
        f.setOnSeekBarChangeListener(positionListener(true));b.setOnSeekBarChangeListener(positionListener(false));
        new AlertDialog.Builder(this).setTitle("소리 위치 직접 조절 · 시연").setView(box).setPositiveButton("닫기",null).show();
    }
    private SeekBar.OnSeekBarChangeListener positionListener(boolean fade){return new SeekBar.OnSeekBarChangeListener(){
        public void onProgressChanged(SeekBar v,int p,boolean user){if(user&&service!=null){if(fade)service.demoFade((p-100)/100.0);else service.demoBalance((p-100)/100.0);}}
        public void onStartTrackingTouch(SeekBar v){}public void onStopTrackingTouch(SeekBar v){}
    };}

    private final class CabinView extends View{
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);boolean focus;double fade,balance;
        private final Shader halo=new RadialGradient(0,0,76,Color.argb(80,118,226,196),Color.TRANSPARENT,Shader.TileMode.CLAMP);
        CabinView(){super(MainActivity.this);setContentDescription("차량 소리 위치 시각화");}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight(),cx=w/2,cy=h/2,scale=Math.min(w/210,h/145);c.save();c.translate(cx,cy);c.scale(scale,scale);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.4f);paint.setColor(Color.rgb(68,86,103));c.drawRoundRect(-49,-65,49,65,23,23,paint);
            c.drawRoundRect(-38,-51,38,-28,8,8,paint);c.drawLine(-38,42,38,42,paint);
            float sx=(float)(balance*26),sy=(float)(-fade*28);
            paint.setStyle(Paint.Style.FILL);paint.setShader(halo);c.save();c.translate(sx,sy);c.drawCircle(0,0,76,paint);c.restore();paint.setShader(null);
            for(int i=0;i<4;i++){float x=i%2==0?-28:8,y=i<2?-19:15;paint.setColor(focus&&i==0?MINT:Color.rgb(56,74,89));c.drawRoundRect(x,y,x+20,y+24,5,5,paint);}
            paint.setStyle(Paint.Style.STROKE);paint.setColor(MINT);paint.setStrokeWidth(1.5f);c.drawCircle(-18,-25,6,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(MUTED);paint.setTextSize(10);paint.setTextAlign(Paint.Align.CENTER);c.drawText(focus?"운전석 중심":"소리 위치",0,84,paint);c.restore();
        }
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size*uiScale);v.setTextColor(color);v.setFontFeatureSettings("tnum");return v;}
    private Button button(String label,Runnable action){Button v=new Button(this);v.setText(label);v.setTextSize(12*uiScale);v.setTextColor(INK);v.setAllCaps(false);v.setPadding(dp(15),0,dp(15),0);v.setMinHeight(dp(48));v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(33,47,61)));v.setOnClickListener(x->action.run());return v;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(float value){return Math.round(value*uiScale*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams margins(int width,int height,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width<0?width:dp(width),height<0?height:dp(height));p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private LinearLayout.LayoutParams marginsWeighted(int width,int height,int l,int t,int r,int b,float weight){LinearLayout.LayoutParams p=margins(width,height,l,t,r,b);p.weight=weight;return p;}
}
