package moe.artemis.gui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.util.Log;
import android.widget.EditText;

import java.util.HashMap;
import java.util.Map;

/**
 * Java dialog bridge used by the Artemis native engine.
 *
 * <p>Some Artemis games call into moe.artemis.gui.Dialog.Show(...) when scripts need a
 * platform confirmation/input dialog, for example save overwrite or exit confirmation.
 * Tyranor carries this bridge class. Without it the native side waits for a result that
 * never comes, which looks like the game is frozen at the confirmation point.</p>
 */
public final class Dialog {
    private static final String TAG = "ArtemisDialog";
    private static final Map<Integer, Dialog> INSTANCES = new HashMap<>();
    private static int seed;

    private final Activity activity;
    private final AlertDialog.Builder dialog;
    private final String message;
    private final boolean textField;
    private final long context;
    private EditText editText;

    public Dialog(Activity activity, String title, String message, boolean cancelable, boolean textField, long context) {
        this.activity = activity;
        this.message = message == null ? "" : message;
        this.textField = textField;
        this.context = context;
        this.dialog = new AlertDialog.Builder(activity);
        this.dialog.setTitle(title == null ? "" : title);
        if (!textField) {
            this.dialog.setMessage(this.message);
        }
        this.dialog.setOnCancelListener(d -> close(0));
        this.dialog.setPositiveButton("OK", (d, which) -> close(1));
        if (cancelable) {
            this.dialog.setNegativeButton("Cancel", (d, which) -> close(0));
        }
        activity.runOnUiThread(this::showInternal);
    }

    private native void OnClose(int result, String text, long context);

    public static void Release(int id) {
        synchronized (INSTANCES) {
            INSTANCES.remove(id);
        }
    }

    public static int Show(Activity activity, String title, String message, boolean cancelable, boolean textField, long context) {
        if (activity == null) return 0;
        int id;
        synchronized (INSTANCES) {
            id = ++seed;
            INSTANCES.put(id, new Dialog(activity, title, message, cancelable, textField, context));
        }
        return id;
    }

    private void showInternal() {
        try {
            if (activity.isFinishing()) {
                close(0);
                return;
            }
            if (textField) {
                editText = new EditText(activity);
                editText.setSingleLine(true);
                editText.setText(message);
                editText.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
                dialog.setView(editText);
            }
            AlertDialog shown = dialog.show();
            shown.setOnDismissListener(new DialogInterface.OnDismissListener() {
                private boolean called;
                @Override public void onDismiss(DialogInterface d) {
                    if (!called) {
                        called = true;
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "show Artemis dialog failed", t);
            close(0);
        }
    }

    private void close(int result) {
        String text = editText != null ? String.valueOf(editText.getText()) : "";
        try {
            OnClose(result, text, context);
        } catch (Throwable t) {
            Log.e(TAG, "notify Artemis dialog close failed", t);
        }
    }
}
