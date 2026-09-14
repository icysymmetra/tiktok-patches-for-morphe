/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/ReVancedTikTokAboutPreference.java
 */

package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.preference.Preference;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.tiktok.Utils;

@SuppressWarnings("deprecation")
public class MorpheTikTokAboutPreference extends Preference {
    private static final int CHEVRON_TAG = 0x4D535550;

    public MorpheTikTokAboutPreference(Context context) {
        super(context);

        setTitle("Support my work");
        setSummary("If you enjoy these patches, I would really appreciate the support. It genuinely means a lot to me.");
        setIcon(SupportUi.createHeartDrawable());

        setOnPreferenceClickListener(pref -> {
            SupportUi.openSupport();
            return true;
        });
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setMinimumHeight(SettingsUi.dp(context, 136));
        row.setPadding(
                SettingsUi.dp(context, 38),
                SettingsUi.dp(context, 16),
                SettingsUi.dp(context, 38),
                SettingsUi.dp(context, 16)
        );
        row.setBackground(SupportUi.createRowBackground(context));

        FrameLayout iconFrame = new FrameLayout(context);
        iconFrame.setId(android.R.id.icon_frame);
        ImageView icon = new ImageView(context);
        icon.setId(android.R.id.icon);
        iconFrame.addView(icon, new FrameLayout.LayoutParams(
                SettingsUi.dp(context, 22),
                SettingsUi.dp(context, 22),
                Gravity.CENTER
        ));
        row.addView(iconFrame, new LinearLayout.LayoutParams(
                SettingsUi.dp(context, 22),
                SettingsUi.dp(context, 22)
        ));

        LinearLayout labels = new LinearLayout(context);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setOrientation(LinearLayout.VERTICAL);

        TextView title = SettingsUi.text(context, "", 14.5f, SettingsUi.textPrimary(), 1);
        title.setId(android.R.id.title);
        labels.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView summary = SettingsUi.text(context, "", 12.8f, SettingsUi.textSecondary(), 0);
        summary.setId(android.R.id.summary);
        summary.setMaxLines(3);
        labels.addView(summary, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.leftMargin = SettingsUi.dp(context, 14);
        labelParams.rightMargin = SettingsUi.dp(context, 10);
        row.addView(labels, labelParams);

        LinearLayout widget = new LinearLayout(context);
        widget.setId(android.R.id.widget_frame);
        widget.setGravity(Gravity.CENTER);
        row.addView(widget, new LinearLayout.LayoutParams(
                SettingsUi.dp(context, 18),
                SettingsUi.dp(context, 18)
        ));
        return row;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);

        Utils.setTitleAndSummaryColor(view);
        view.setMinimumHeight(SettingsUi.dp(getContext(), 112));
        view.setBackground(SupportUi.createRowBackground(getContext()));
        styleText(view);
        styleHeart(view);
        addChevron(view);
    }

    private void styleText(View view) {
        TextView title = view.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextSize(14.5f);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        TextView summary = view.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setTextSize(12.8f);
            summary.setMaxLines(3);
        }
    }

    private void styleHeart(View view) {
        ImageView icon = view.findViewById(android.R.id.icon);
        if (icon == null) {
            return;
        }

        int iconSize = SettingsUi.dp(getContext(), 21);
        ViewGroup.LayoutParams params = icon.getLayoutParams();
        params.width = iconSize;
        params.height = iconSize;
        if (params instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams frameParams = (FrameLayout.LayoutParams) params;
            frameParams.gravity = Gravity.CENTER;
            frameParams.topMargin = 0;
        }
        icon.setLayoutParams(params);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    }

    private void addChevron(View view) {
        View widget = view.findViewById(android.R.id.widget_frame);
        if (!(widget instanceof ViewGroup)) {
            return;
        }

        ViewGroup container = (ViewGroup) widget;
        container.setVisibility(View.VISIBLE);
        View existing = container.findViewWithTag(CHEVRON_TAG);
        if (existing != null) {
            return;
        }

        ImageView chevron = new ImageView(getContext());
        chevron.setTag(CHEVRON_TAG);
        chevron.setImageDrawable(new ChevronDrawable());
        int size = SettingsUi.dp(getContext(), 18);
        container.addView(chevron, new ViewGroup.LayoutParams(size, size));
    }

    private static final class ChevronDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ChevronDrawable() {
            paint.setColor(SettingsUi.isDarkMode()
                    ? Color.argb(255, 143, 139, 140)
                    : Color.argb(255, 135, 132, 133));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            paint.setStrokeCap(Paint.Cap.SQUARE);
        }

        @Override
        public void draw(Canvas canvas) {
            float centerX = getBounds().exactCenterX();
            float centerY = getBounds().exactCenterY();
            float offset = Math.min(getBounds().width(), getBounds().height()) * 0.17f;
            canvas.drawLine(centerX - offset, centerY - offset, centerX + offset, centerY, paint);
            canvas.drawLine(centerX + offset, centerY, centerX - offset, centerY + offset, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

}
