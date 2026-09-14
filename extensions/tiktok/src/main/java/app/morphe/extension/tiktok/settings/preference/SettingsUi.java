package app.morphe.extension.tiktok.settings.preference;

import static app.morphe.extension.shared.Utils.isDarkModeEnabled;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.ColorInt;

public final class SettingsUi {
    public static final @ColorInt int ACCENT = Color.argb(255, 240, 45, 99);
    public static final @ColorInt int DARK_BACKGROUND = Color.argb(255, 13, 13, 16);
    public static final @ColorInt int DARK_SURFACE = Color.argb(255, 21, 21, 26);
    public static final @ColorInt int DARK_SURFACE_LIFTED = Color.argb(255, 32, 32, 39);
    public static final @ColorInt int DARK_BORDER = Color.argb(255, 55, 55, 63);
    public static final @ColorInt int DARK_DIVIDER = Color.argb(255, 29, 29, 34);
    public static final @ColorInt int DARK_TEXT_PRIMARY = Color.argb(255, 242, 242, 245);
    public static final @ColorInt int DARK_TEXT_SECONDARY = Color.argb(255, 151, 151, 159);
    public static final @ColorInt int DARK_TEXT_DISABLED = Color.argb(255, 109, 109, 118);

    public static final @ColorInt int LIGHT_BACKGROUND = Color.WHITE;
    public static final @ColorInt int LIGHT_SURFACE = Color.WHITE;
    public static final @ColorInt int LIGHT_SURFACE_LIFTED = Color.argb(255, 250, 250, 250);
    public static final @ColorInt int LIGHT_BORDER = Color.argb(255, 210, 210, 210);
    public static final @ColorInt int LIGHT_DIVIDER = Color.argb(255, 224, 224, 224);
    public static final @ColorInt int LIGHT_TEXT_PRIMARY = Color.BLACK;
    public static final @ColorInt int LIGHT_TEXT_SECONDARY = Color.argb(255, 80, 80, 80);
    public static final @ColorInt int LIGHT_TEXT_DISABLED = Color.argb(255, 140, 140, 140);

    private SettingsUi() {
    }

    public static boolean isDarkMode() {
        return isDarkModeEnabled();
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static @ColorInt int background() {
        return isDarkMode() ? DARK_BACKGROUND : LIGHT_BACKGROUND;
    }

    public static @ColorInt int surface() {
        return isDarkMode() ? DARK_SURFACE : LIGHT_SURFACE;
    }

    public static @ColorInt int liftedSurface() {
        return isDarkMode() ? DARK_SURFACE_LIFTED : LIGHT_SURFACE_LIFTED;
    }

    public static @ColorInt int border() {
        return isDarkMode() ? DARK_BORDER : LIGHT_BORDER;
    }

    public static @ColorInt int divider() {
        return isDarkMode() ? DARK_DIVIDER : LIGHT_DIVIDER;
    }

    public static @ColorInt int textPrimary() {
        return isDarkMode() ? DARK_TEXT_PRIMARY : LIGHT_TEXT_PRIMARY;
    }

    public static @ColorInt int textSecondary() {
        return isDarkMode() ? DARK_TEXT_SECONDARY : LIGHT_TEXT_SECONDARY;
    }

    public static @ColorInt int textDisabled() {
        return isDarkMode() ? DARK_TEXT_DISABLED : LIGHT_TEXT_DISABLED;
    }

    public static void styleTitleAndSummary(View view) {
        TextView title = view.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(textPrimary());
        }

        TextView summary = view.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setTextColor(textSecondary());
        }
    }

    public static void styleCategory(View view) {
        TextView title = view.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(ACCENT);
            title.setTextSize(13);
            title.setTypeface(title.getTypeface(), Typeface.BOLD);
        }
    }

    public static TextView text(Context context, String value, float sizeSp, int color, int style) {
        TextView textView = new TextView(context);
        textView.setText(value);
        textView.setTextColor(color);
        textView.setTextSize(sizeSp);
        textView.setTypeface(textView.getTypeface(), style);
        return textView;
    }

    public static GradientDrawable roundedSurface(Context context, int radiusDp, boolean lifted) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(lifted ? liftedSurface() : surface());
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable borderedSurface(Context context, int radiusDp, boolean lifted) {
        GradientDrawable drawable = roundedSurface(context, radiusDp, lifted);
        drawable.setStroke(Math.max(1, dp(context, 1)), border());
        return drawable;
    }

    public static void styleDialog(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            View decorView = window.getDecorView();
            if (decorView != null) {
                decorView.setBackgroundColor(Color.TRANSPARENT);
            }
            constrainDialogWindow(dialog, window);
        }

        if (dialog instanceof AlertDialog) {
            AlertDialog alertDialog = (AlertDialog) dialog;
            View content = alertDialog.findViewById(android.R.id.content);
            if (content != null) {
                content.setBackgroundColor(Color.TRANSPARENT);
            }
            styleActionButton(alertDialog.getButton(DialogInterface.BUTTON_POSITIVE), true);
            styleActionButton(alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE), false);
            styleActionButton(alertDialog.getButton(DialogInterface.BUTTON_NEUTRAL), false);
        }
    }

    public static void styleFramedDialog(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(borderedSurface(dialog.getContext(), 6, true));
            constrainDialogWindow(dialog, window);
        }

        if (dialog instanceof AlertDialog) {
            AlertDialog alertDialog = (AlertDialog) dialog;
            View content = alertDialog.findViewById(android.R.id.content);
            if (content != null) {
                content.setBackgroundColor(Color.TRANSPARENT);
            }
            styleActionButton(alertDialog.getButton(DialogInterface.BUTTON_POSITIVE), true);
            styleActionButton(alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE), false);
            styleActionButton(alertDialog.getButton(DialogInterface.BUTTON_NEUTRAL), false);
        }
    }

    public static void styleStandardAlertDialog(AlertDialog dialog) {
        styleFramedDialog(dialog);

        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }

        makeDialogPanelsTransparent(window.getDecorView(), dialog.getContext());
        styleDialogText(window.getDecorView());

        ListView list = dialog.getListView();
        if (list != null) {
            list.setBackgroundColor(Color.TRANSPARENT);
            list.setDivider(new ColorDrawable(divider()));
            list.setDividerHeight(Math.max(1, dp(dialog.getContext(), 1)));
            list.post(() -> {
                styleDialogText(list);
                list.postDelayed(() -> styleDialogText(list), 50);
            });
        }

        styleActionButton(dialog.getButton(DialogInterface.BUTTON_POSITIVE), true);
        styleActionButton(dialog.getButton(DialogInterface.BUTTON_NEGATIVE), false);
        styleActionButton(dialog.getButton(DialogInterface.BUTTON_NEUTRAL), false);
    }

    public static int dialogListHeight(Context context, int preferredDp) {
        int preferred = dp(context, preferredDp);
        int screenBased = Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.38f);
        return Math.min(preferred, Math.max(dp(context, 220), screenBased));
    }

    private static void constrainDialogWindow(Dialog dialog, Window window) {
        int screenWidth = dialog.getContext().getResources().getDisplayMetrics().widthPixels;
        int horizontalMargins = dp(dialog.getContext(), 32);
        int maxWidth = dp(dialog.getContext(), 560);
        int width = Math.min(maxWidth, Math.max(dp(dialog.getContext(), 280), screenWidth - horizontalMargins));
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static void makeDialogPanelsTransparent(View root, Context context) {
        String[] panelNames = {
                "parentPanel",
                "topPanel",
                "contentPanel",
                "buttonPanel",
                "customPanel"
        };
        for (String panelName : panelNames) {
            int id = context.getResources().getIdentifier(panelName, "id", "android");
            if (id == 0) {
                continue;
            }
            View panel = root.findViewById(id);
            if (panel != null) {
                panel.setBackgroundColor(Color.TRANSPARENT);
            }
        }
    }

    private static void styleDialogText(View view) {
        if (view instanceof CheckBox) {
            CheckBox checkBox = (CheckBox) view;
            checkBox.setTextColor(textPrimary());
            styleCheckBox(checkBox);
        } else if (view instanceof CheckedTextView) {
            CheckedTextView checkedTextView = (CheckedTextView) view;
            checkedTextView.setTextColor(textPrimary());
        } else if (view instanceof Button) {
            ((Button) view).setTextColor(ACCENT);
        } else if (view instanceof TextView) {
            ((TextView) view).setTextColor(textPrimary());
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                styleDialogText(group.getChildAt(i));
            }
        }
    }

    public static void styleActionButton(Button button, boolean primary) {
        if (button == null) {
            return;
        }
        button.setTextColor(primary ? ACCENT : textSecondary());
        button.setAllCaps(false);
        button.setTypeface(button.getTypeface(), primary ? Typeface.BOLD : Typeface.NORMAL);
    }

    public static void styleTextAction(TextView button, boolean primary) {
        button.setTextColor(primary ? ACCENT : textSecondary());
        button.setTypeface(button.getTypeface(), primary ? Typeface.BOLD : Typeface.NORMAL);
    }

    public static void styleEditText(EditText editText) {
        editText.setTextColor(textPrimary());
        editText.setHintTextColor(textSecondary());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            editText.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        }
    }

    public static void styleCheckBox(CompoundButton button) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{-android.R.attr.state_enabled},
                    new int[]{}
            };
            int[] colors = new int[]{ACCENT, textDisabled(), textSecondary()};
            button.setButtonTintList(new ColorStateList(states, colors));
        }
    }

}
