package T3;

import android.os.Bundle;

public abstract class a extends com.ies_net.artemis.ArtemisActivity {
 public abstract void a();

 @Override
 public java.io.File getExternalFilesDir(String type) {
 String path = getIntent() == null ? null : getIntent().getStringExtra("path");
 if (path == null || path.isEmpty()) return super.getExternalFilesDir(type);
 if (path.startsWith("file://")) path = path.substring("file://".length());
 return new java.io.File(path);
 }

 @Override
 public final void onCreate(Bundle bundle) {
 super.onCreate(bundle);
 a();
 }

 @Override
 public final void onResume() {
 super.onResume();
 setRequestedOrientation(getIntent().getIntExtra("orientation", 6));
 }
}