/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.preference.Preference;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

@SuppressWarnings("deprecation")
public final class SettingsHeaderPreference extends Preference {
    private enum Kind {
        MASTER,
        SECTION,
        CAPTION
    }

    private final Kind kind;
    private final String heading;
    private final String detail;
    private final Runnable backAction;

    public static SettingsHeaderPreference master(Context context, Runnable backAction) {
        return new SettingsHeaderPreference(
                context,
                Kind.MASTER,
                "Settings",
                null,
                backAction
        );
    }

    public static SettingsHeaderPreference section(Context context, String title, Runnable backAction) {
        return new SettingsHeaderPreference(context, Kind.SECTION, title, null, backAction);
    }

    public static SettingsHeaderPreference caption(Context context, String caption) {
        return new SettingsHeaderPreference(context, Kind.CAPTION, null, caption, null);
    }

    private SettingsHeaderPreference(
            Context context,
            Kind kind,
            String heading,
            String detail,
            Runnable backAction
    ) {
        super(context);
        this.kind = kind;
        this.heading = heading;
        this.detail = detail;
        this.backAction = backAction;
        setSelectable(kind != Kind.CAPTION);
        setOrder(kind == Kind.CAPTION ? -900 : -1000);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        if (kind == Kind.MASTER) {
            return createMasterHeader();
        }
        if (kind == Kind.CAPTION) {
            return createCaption();
        }
        return createSectionHeader();
    }

    private View createMasterHeader() {
        Context context = getContext();
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(
                SettingsUi.dp(context, 8),
                SettingsUi.dp(context, 18),
                SettingsUi.dp(context, 22),
                SettingsUi.dp(context, 10)
        );
        container.setBackgroundColor(SettingsUi.background());

        LinearLayout topRow = new LinearLayout(context);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setOrientation(LinearLayout.HORIZONTAL);

        ImageView back = new ImageView(context);
        back.setContentDescription("Back");
        back.setImageDrawable(new BackDrawable());
        back.setOnClickListener(view -> {
            if (backAction != null) {
                backAction.run();
            }
        });
        int buttonSize = SettingsUi.dp(context, 40);
        topRow.addView(back, new LinearLayout.LayoutParams(buttonSize, buttonSize));

        TextView kicker = SettingsUi.text(context, "METRA PATCHES", 12.5f, SettingsUi.ACCENT, 1);
        LinearLayout.LayoutParams kickerParams = new LinearLayout.LayoutParams(0, -2, 1);
        kickerParams.leftMargin = SettingsUi.dp(context, 4);
        topRow.addView(kicker, kickerParams);
        topRow.addView(createSupportPill(context), new LinearLayout.LayoutParams(-2, -2));
        container.addView(topRow, new LinearLayout.LayoutParams(-1, -2));

        TextView title = SettingsUi.text(context, heading, 32, SettingsUi.textPrimary(), 1);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.leftMargin = SettingsUi.dp(context, 14);
        titleParams.topMargin = SettingsUi.dp(context, 2);
        container.addView(title, titleParams);

        TextView menuLabel = SettingsUi.text(context, "MENUS", 12.5f, SettingsUi.textDisabled(), 1);
        LinearLayout.LayoutParams menuLabelParams = new LinearLayout.LayoutParams(-1, -2);
        menuLabelParams.leftMargin = SettingsUi.dp(context, 14);
        menuLabelParams.topMargin = SettingsUi.dp(context, 18);
        container.addView(menuLabel, menuLabelParams);
        return container;
    }

    private View createSectionHeader() {
        Context context = getContext();
        LinearLayout container = new LinearLayout(context);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setMinimumHeight(SettingsUi.dp(context, 56));
        container.setPadding(
                SettingsUi.dp(context, 8),
                0,
                SettingsUi.dp(context, 16),
                0
        );
        container.setBackgroundColor(SettingsUi.background());
        container.setOnClickListener(view -> {
            if (backAction != null) {
                backAction.run();
            }
        });

        ImageView back = new ImageView(context);
        back.setContentDescription("Back");
        back.setImageDrawable(new BackDrawable());
        int buttonSize = SettingsUi.dp(context, 40);
        container.addView(back, new LinearLayout.LayoutParams(buttonSize, buttonSize));

        TextView title = SettingsUi.text(context, heading, 17, SettingsUi.textPrimary(), 1);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.leftMargin = SettingsUi.dp(context, 4);
        container.addView(title, titleParams);
        container.addView(createSupportPill(context), new LinearLayout.LayoutParams(-2, -2));
        return container;
    }

    private View createSupportPill(Context context) {
        return SupportUi.createPill(context);
    }

    private View createCaption() {
        Context context = getContext();
        TextView caption = SettingsUi.text(context, detail, 13.5f, SettingsUi.textSecondary(), 0);
        caption.setPadding(
                SettingsUi.dp(context, 22),
                SettingsUi.dp(context, 12),
                SettingsUi.dp(context, 22),
                SettingsUi.dp(context, 8)
        );
        caption.setBackgroundColor(SettingsUi.background());
        return caption;
    }

    private static final class BackDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BackDrawable() {
            paint.setColor(SettingsUi.textPrimary());
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.1f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            float centerX = getBounds().exactCenterX();
            float centerY = getBounds().exactCenterY();
            float offset = Math.min(getBounds().width(), getBounds().height()) * 0.18f;
            float pointX = centerX - offset;
            canvas.drawLine(pointX, centerY, centerX + offset, centerY, paint);
            canvas.drawLine(pointX, centerY, centerX - offset * 0.1f, centerY - offset * 0.9f, paint);
            canvas.drawLine(pointX, centerY, centerX - offset * 0.1f, centerY + offset * 0.9f, paint);
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
