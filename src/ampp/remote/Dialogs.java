package ampp.remote;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;

final class Dialogs {
    static AlertDialog.Builder builder(Context context) {
        int theme = isNight(context)
                ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
        return new AlertDialog.Builder(context, theme);
    }

    static AlertDialog show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.show();
        int color = isNight(dialog.getContext()) ? 0xff80cbc4 : 0xff00695c;
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
            android.widget.Button button = dialog.getButton(which);
            if (button != null) button.setTextColor(color);
        }
        return dialog;
    }

    private static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
}
