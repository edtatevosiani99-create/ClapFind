package com.clapfind.app;
import android.app.*;import android.os.*;import android.content.*;import android.content.pm.PackageManager;import android.Manifest;import android.view.Gravity;import android.widget.*;
public class MainActivity extends Activity {
 TextView status; Button power,stop; boolean on=false,alert=false; static final int R=5;
 android.content.SharedPreferences prefs;
 BroadcastReceiver rx=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if("com.clapfind.ALERT".equals(i.getAction())){alert=true;stop.setVisibility(0);status.setText("PHONE FOUND — tap screen or STOP");}else if("com.clapfind.SILENCED".equals(i.getAction())){alert=false;stop.setVisibility(8);status.setText(on?"ON — listening":"OFF");}}};
 public void onCreate(Bundle b){super.onCreate(b);prefs=getSharedPreferences("clapfind",MODE_PRIVATE);LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setGravity(Gravity.CENTER);l.setPadding(24,24,24,24);TextView t=new TextView(this);t.setText("ClapFind");t.setTextSize(32);t.setGravity(Gravity.CENTER);l.addView(t);status=new TextView(this);status.setGravity(Gravity.CENTER);l.addView(status);power=new Button(this);l.addView(power);power.setOnClickListener(v->{if(on)off();else start();});stop=new Button(this);stop.setText("STOP");stop.setVisibility(8);l.addView(stop);stop.setOnClickListener(v->silence());l.setOnClickListener(v->{if(alert)silence();});setContentView(l);IntentFilter f=new IntentFilter();f.addAction("com.clapfind.ALERT");f.addAction("com.clapfind.SILENCED");if(Build.VERSION.SDK_INT>=33)registerReceiver(rx,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(rx,f);on=prefs.getBoolean("monitoring",false);updateUi();if(on)start();}
 void updateUi(){status.setText(on?"ON — listening until OFF":"OFF");power.setText(on?"OFF":"ON");}
 void start(){
  java.util.ArrayList<String> missing=new java.util.ArrayList<>();
  if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.RECORD_AUDIO);
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.POST_NOTIFICATIONS);
  if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),R);return;}
  launchListeningService();
 }
 void launchListeningService(){
  try{Intent i=new Intent(this,ListeningService.class);i.setAction("START");if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);on=true;prefs.edit().putBoolean("monitoring",true).apply();updateUi();}
  catch(Exception e){on=false;prefs.edit().putBoolean("monitoring",false).apply();updateUi();new AlertDialog.Builder(this).setTitle("ClapFind could not start").setMessage(e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())).setPositiveButton("OK",null).show();}
 }
 void off(){try{Intent i=new Intent(this,ListeningService.class);i.setAction("STOP");startService(i);}catch(Exception e){}on=false;prefs.edit().putBoolean("monitoring",false).apply();alert=false;stop.setVisibility(8);updateUi();}
 void silence(){try{Intent i=new Intent(this,ListeningService.class);i.setAction("SILENCE");startService(i);}catch(Exception e){}alert=false;stop.setVisibility(8);status.setText(on?"ON — listening":"OFF");}
 public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==R){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)Toast.makeText(this,"Allow ClapFind notifications so its listening status is visible.",Toast.LENGTH_LONG).show();launchListeningService();}else{on=false;prefs.edit().putBoolean("monitoring",false).apply();updateUi();Toast.makeText(this,"Microphone permission is required.",Toast.LENGTH_LONG).show();}}}
 protected void onDestroy(){try{unregisterReceiver(rx);}catch(Exception e){}super.onDestroy();}
}