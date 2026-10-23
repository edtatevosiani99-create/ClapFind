package com.clapfind.app;
import android.app.*;import android.os.*;import android.content.*;import android.content.pm.PackageManager;import android.Manifest;import android.view.Gravity;import android.widget.*;
public class MainActivity extends Activity {
 TextView status; Button power,stop; boolean on=false,alert=false; static final int R=5;
 public void onCreate(Bundle b){super.onCreate(b); LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setGravity(Gravity.CENTER);l.setPadding(24,24,24,24);TextView t=new TextView(this);t.setText("ClapFind");t.setTextSize(32);t.setGravity(Gravity.CENTER);l.addView(t);status=new TextView(this);status.setText("OFF");status.setGravity(Gravity.CENTER);l.addView(status);power=new Button(this);power.setText("ON");l.addView(power);power.setOnClickListener(v->{if(on)off();else start();});stop=new Button(this);stop.setText("STOP");stop.setVisibility(8);l.addView(stop);stop.setOnClickListener(v->silence());l.setOnClickListener(v->{if(alert)silence();});setContentView(l);}
 void start(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},R);return;}Intent i=new Intent(this,ListeningService.class);i.setAction("START");if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);on=true;status.setText("ON — listening until OFF");power.setText("OFF");}
 void off(){Intent i=new Intent(this,ListeningService.class);i.setAction("STOP");startService(i);on=false;alert=false;status.setText("OFF");power.setText("ON");stop.setVisibility(8);}
 void silence(){Intent i=new Intent(this,ListeningService.class);i.setAction("SILENCE");startService(i);alert=false;stop.setVisibility(8);status.setText("ON — listening");}
}
