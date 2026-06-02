package T3;

import android.os.Bundle;
import android.util.Log;

public abstract class a extends com.ies_net.artemis.ArtemisActivity {
 public abstract void a();

 @Override
 public java.io.File getExternalFilesDir(String type) {
  String path = getIntent() == null ? null : getIntent().getStringExtra("path");
  if (path == null || path.isEmpty()) {
   java.io.File fallback = super.getExternalFilesDir(type);
   Log.i("YukiArtemis", "getExternalFilesDir type=" + type + " fallback=" + (fallback == null ? "null" : fallback.getAbsolutePath()));
   return fallback;
  }
  if (path.startsWith("file://")) path = path.substring("file://".length());
  java.io.File out = new java.io.File(path);
  Log.i("YukiArtemis", "getExternalFilesDir type=" + type + " path=" + out.getAbsolutePath() + " scoped=" + getIntent().getBooleanExtra("scopedSaveDir", false));
  return out;
 }

 @Override
 public final void onCreate(Bundle bundle) {
  super.onCreate(bundle);
  Log.i("YukiArtemis", "onCreate path=" + (getIntent() == null ? null : getIntent().getStringExtra("path")) + " scoped=" + (getIntent() != null && getIntent().getBooleanExtra("scopedSaveDir", false)) + " saveName=" + (getIntent() == null ? null : getIntent().getStringExtra("scopedSaveName")));
  a();
 }

 @Override
 public final void onResume() {
  super.onResume();
  setRequestedOrientation(getIntent().getIntExtra("orientation", 6));
 }
}