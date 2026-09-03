/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.preference.Preference;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

@SuppressWarnings("deprecation")
public final class SettingsMenuPreference extends Preference {
    public enum Icon {
        FILTER,
        TABS,
        LAYOUT,
        COMMENTS,
        DOWNLOADS,
        REGION,
        BEHAVIOR,
        LAB,
        DIAGNOSTICS,
        SHARE
    }

    private static final int ACCESSORY_TAG = 0x4D4D454E;
    private final int activeCount;

    public SettingsMenuPreference(
            Context context,
            String title,
            String summary,
            Icon icon,
            int activeCount,
            OnPreferenceClickListener listener
    ) {
        super(context);
        this.activeCount = activeCount;
        setTitle(title);
        setSummary(summary);
        setIcon(new MenuIconDrawable(icon));
        setOnPreferenceClickListener(listener);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setMinimumHeight(SettingsUi.dp(context, 68));
        row.setPadding(
                SettingsUi.dp(context, 20),
                SettingsUi.dp(context, 8),
                SettingsUi.dp(context, 20),
                SettingsUi.dp(context, 8)
        );

        FrameLayout iconFrame = new FrameLayout(context);
        iconFrame.setId(android.R.id.icon_frame);
        ImageView icon = new ImageView(context);
        icon.setId(android.R.id.icon);
        iconFrame.addView(icon, new FrameLayout.LayoutParams(
                SettingsUi.dp(context, 40),
                SettingsUi.dp(context, 40),
                Gravity.CENTER
        ));
        row.addView(iconFrame, new LinearLayout.LayoutParams(
                SettingsUi.dp(context, 40),
                SettingsUi.dp(context, 40)
        ));

        LinearLayout labels = new LinearLayout(context);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setOrientation(LinearLayout.VERTICAL);

        TextView title = SettingsUi.text(context, "", 15.5f, SettingsUi.textPrimary(), 1);
        title.setId(android.R.id.title);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView summary = SettingsUi.text(context, "", 12.8f, SettingsUi.textSecondary(), 0);
        summary.setId(android.R.id.summary);
        summary.setSingleLine(true);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(summary, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.leftMargin = SettingsUi.dp(context, 14);
        labelParams.rightMargin = SettingsUi.dp(context, 10);
        row.addView(labels, labelParams);

        LinearLayout widget = new LinearLayout(context);
        widget.setId(android.R.id.widget_frame);
        widget.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.addView(widget, new LinearLayout.LayoutParams(-2, -1));
        return row;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
        view.setMinimumHeight(SettingsUi.dp(getContext(), 68));
        styleText(view);
        styleIcon(view);
        addAccessory(view);
    }

    private void styleText(View view) {
        TextView title = view.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextSize(15.5f);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
        }

        TextView summary = view.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setTextSize(12.8f);
            summary.setSingleLine(true);
            summary.setEllipsize(TextUtils.TruncateAt.END);
        }
    }

    private void styleIcon(View view) {
        ImageView icon = view.findViewById(android.R.id.icon);
        if (icon == null) {
            return;
        }
        int size = SettingsUi.dp(getContext(), 40);
        ViewGroup.LayoutParams params = icon.getLayoutParams();
        params.width = size;
        params.height = size;
        if (params instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) params).gravity = Gravity.CENTER;
        }
        icon.setLayoutParams(params);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    }

    private void addAccessory(View view) {
        View widget = view.findViewById(android.R.id.widget_frame);
        if (!(widget instanceof ViewGroup)) {
            return;
        }

        ViewGroup frame = (ViewGroup) widget;
        frame.setVisibility(View.VISIBLE);
        View previous = frame.findViewWithTag(ACCESSORY_TAG);
        if (previous != null) {
            frame.removeView(previous);
        }

        LinearLayout accessory = new LinearLayout(getContext());
        accessory.setTag(ACCESSORY_TAG);
        accessory.setGravity(Gravity.CENTER_VERTICAL);
        accessory.setOrientation(LinearLayout.HORIZONTAL);

        if (activeCount > 0) {
            TextView badge = SettingsUi.text(
                    getContext(),
                    activeCount + " on",
                    11.5f,
                    SettingsUi.ACCENT,
                    1
            );
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(
                    SettingsUi.dp(getContext(), 9),
                    SettingsUi.dp(getContext(), 3),
                    SettingsUi.dp(getContext(), 9),
                    SettingsUi.dp(getContext(), 3)
            );
            badge.setBackground(new BadgeDrawable());
            accessory.addView(badge);
        }

        ImageView chevron = new ImageView(getContext());
        chevron.setImageDrawable(new ChevronDrawable());
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(
                SettingsUi.dp(getContext(), 18),
                SettingsUi.dp(getContext(), 18)
        );
        chevronParams.leftMargin = SettingsUi.dp(getContext(), activeCount > 0 ? 9 : 0);
        accessory.addView(chevron, chevronParams);
        frame.addView(accessory);
    }

    private static final class BadgeDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BadgeDrawable() {
            paint.setColor(Color.argb(SettingsUi.isDarkMode() ? 38 : 24, 240, 45, 99));
        }

        @Override
        public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            canvas.drawRoundRect(bounds, bounds.height() / 2f, bounds.height() / 2f, paint);
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

    private static final class ChevronDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ChevronDrawable() {
            paint.setColor(SettingsUi.textDisabled());
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            float centerX = getBounds().exactCenterX();
            float centerY = getBounds().exactCenterY();
            float offset = Math.min(getBounds().width(), getBounds().height()) * 0.22f;
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

    private static final class MenuIconDrawable extends Drawable {
        private final Icon icon;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyphFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        MenuIconDrawable(Icon icon) {
            this.icon = icon;
            fill.setColor(SettingsUi.isDarkMode()
                    ? Color.argb(255, 23, 23, 29)
                    : Color.argb(255, 246, 246, 248));
            border.setColor(SettingsUi.isDarkMode()
                    ? Color.argb(255, 48, 48, 56)
                    : Color.argb(255, 224, 224, 228));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(1f);
            line.setColor(SettingsUi.isDarkMode()
                    ? Color.argb(255, 207, 207, 215)
                    : Color.argb(255, 72, 72, 78));
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.8f);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            glyphFill.setColor(line.getColor());
            glyphFill.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            float radius = bounds.width() * 0.28f;
            canvas.drawRoundRect(bounds, radius, radius, fill);
            canvas.drawRoundRect(bounds, radius, radius, border);

            float left = bounds.left + bounds.width() * 0.27f;
            float right = bounds.right - bounds.width() * 0.27f;
            float top = bounds.top + bounds.height() * 0.27f;
            float bottom = bounds.bottom - bounds.height() * 0.27f;
            float cx = bounds.centerX();
            float cy = bounds.centerY();

            switch (icon) {
                case FILTER:
                    path.reset();
                    path.moveTo(left, top);
                    path.lineTo(right, top);
                    path.lineTo(cx + bounds.width() * 0.06f, cy);
                    path.lineTo(cx + bounds.width() * 0.06f, bottom);
                    path.lineTo(cx - bounds.width() * 0.06f, bottom + bounds.height() * 0.05f);
                    path.lineTo(cx - bounds.width() * 0.06f, cy);
                    path.close();
                    canvas.drawPath(path, line);
                    break;
                case TABS:
                case LAYOUT:
                    canvas.drawRoundRect(new RectF(left, top, right, bottom), 2, 2, line);
                    canvas.drawLine(left, cy - bounds.height() * 0.07f, right, cy - bounds.height() * 0.07f, line);
                    if (icon == Icon.TABS) {
                        canvas.drawLine(cx - bounds.width() * 0.1f, top, cx - bounds.width() * 0.1f,
                                cy - bounds.height() * 0.07f, line);
                    } else {
                        canvas.drawLine(cx - bounds.width() * 0.07f, cy - bounds.height() * 0.07f,
                                cx - bounds.width() * 0.07f, bottom, line);
                    }
                    break;
                case COMMENTS:
                    path.reset();
                    path.moveTo(left, top);
                    path.lineTo(right, top);
                    path.lineTo(right, bottom - bounds.height() * 0.08f);
                    path.lineTo(cx, bottom - bounds.height() * 0.08f);
                    path.lineTo(left + bounds.width() * 0.06f, bottom);
                    path.lineTo(left + bounds.width() * 0.08f, bottom - bounds.height() * 0.08f);
                    path.lineTo(left, bottom - bounds.height() * 0.08f);
                    path.close();
                    canvas.drawPath(path, line);
                    break;
                case DOWNLOADS:
                    canvas.drawLine(cx, top, cx, cy + bounds.height() * 0.05f, line);
                    canvas.drawLine(cx, cy + bounds.height() * 0.05f, cx - bounds.width() * 0.1f, cy - bounds.height() * 0.05f, line);
                    canvas.drawLine(cx, cy + bounds.height() * 0.05f, cx + bounds.width() * 0.1f, cy - bounds.height() * 0.05f, line);
                    canvas.drawLine(left, bottom, right, bottom, line);
                    break;
                case REGION:
                    canvas.drawCircle(cx, cy, bounds.width() * 0.23f, line);
                    canvas.drawLine(left, cy, right, cy, line);
                    canvas.drawOval(new RectF(cx - bounds.width() * 0.1f, top, cx + bounds.width() * 0.1f, bottom), line);
                    break;
                case BEHAVIOR:
                    canvas.drawLine(left, top + bounds.height() * 0.05f, right, top + bounds.height() * 0.05f, line);
                    canvas.drawLine(left, cy, right, cy, line);
                    canvas.drawLine(left, bottom - bounds.height() * 0.05f, right, bottom - bounds.height() * 0.05f, line);
                    canvas.drawCircle(cx + bounds.width() * 0.09f, top + bounds.height() * 0.05f, bounds.width() * 0.045f, line);
                    canvas.drawCircle(cx - bounds.width() * 0.08f, cy, bounds.width() * 0.045f, line);
                    canvas.drawCircle(cx + bounds.width() * 0.02f, bottom - bounds.height() * 0.05f, bounds.width() * 0.045f, line);
                    break;
                case LAB:
                    path.reset();
                    path.moveTo(cx - bounds.width() * 0.08f, top);
                    path.lineTo(cx - bounds.width() * 0.08f, cy - bounds.height() * 0.04f);
                    path.lineTo(left, bottom);
                    path.lineTo(right, bottom);
                    path.lineTo(cx + bounds.width() * 0.08f, cy - bounds.height() * 0.04f);
                    path.lineTo(cx + bounds.width() * 0.08f, top);
                    canvas.drawPath(path, line);
                    canvas.drawLine(left + bounds.width() * 0.08f, cy + bounds.height() * 0.09f,
                            right - bounds.width() * 0.08f, cy + bounds.height() * 0.09f, line);
                    break;
                case DIAGNOSTICS:
                    canvas.drawCircle(cx, cy, bounds.width() * 0.16f, line);
                    canvas.drawLine(cx, top, cx, cy - bounds.height() * 0.16f, line);
                    canvas.drawLine(cx, cy + bounds.height() * 0.16f, cx, bottom, line);
                    canvas.drawLine(left, cy, cx - bounds.width() * 0.16f, cy, line);
                    canvas.drawLine(cx + bounds.width() * 0.16f, cy, right, cy, line);
                    break;
                case SHARE: {
                    float pad = bounds.width() * 0.20f;
                    float s = (bounds.width() - pad * 2f) / 24f;
                    float ox = bounds.left + pad;
                    float oy = bounds.top + pad;

                    path.reset();
                    path.setFillType(Path.FillType.EVEN_ODD);

                    path.moveTo(ox + 1f * s, oy + 18.5088f * s);
                    path.cubicTo(ox + 1f * s, oy + 13.1679f * s, ox + 4.90169f * s, oy + 8.77098f * s, ox + 9.99995f * s, oy + 7.84598f * s);
                    path.lineTo(ox + 9.99995f * s, oy + 5.51119f * s);
                    path.cubicTo(ox + 9.99995f * s, oy + 3.63887f * s, ox + 12.1534f * s, oy + 2.58563f * s, ox + 13.6313f * s, oy + 3.73514f * s);
                    path.lineTo(ox + 21.9742f * s, oy + 10.224f * s);
                    path.cubicTo(ox + 23.1323f * s, oy + 11.1248f * s, ox + 23.1324f * s, oy + 12.8752f * s, ox + 21.9742f * s, oy + 13.7761f * s);
                    path.lineTo(ox + 13.6314f * s, oy + 20.2649f * s);
                    path.cubicTo(ox + 12.1534f * s, oy + 21.4144f * s, ox + 10f * s, oy + 20.3612f * s, ox + 10f * s, oy + 18.4888f * s);
                    path.lineTo(ox + 10f * s, oy + 16.5189f * s);
                    path.cubicTo(ox + 7.74106f * s, oy + 16.9525f * s, ox + 5.9625f * s, oy + 18.1157f * s, ox + 4.92778f * s, oy + 19.6838f * s);
                    path.cubicTo(ox + 4.33222f * s, oy + 20.5863f * s, ox + 3.30568f * s, oy + 20.7735f * s, ox + 2.55965f * s, oy + 20.5635f * s);
                    path.cubicTo(ox + 1.80473f * s, oy + 20.3511f * s, ox + 1.00011f * s, oy + 19.6306f * s, ox + 1f * s, oy + 18.5088f * s);
                    path.close();

                    path.moveTo(ox + 12.4034f * s, oy + 5.31385f * s);
                    path.cubicTo(ox + 12.2392f * s, oy + 5.18613f * s, ox + 11.9999f * s, oy + 5.30315f * s, ox + 11.9999f * s, oy + 5.51119f * s);
                    path.lineTo(ox + 11.9999f * s, oy + 9.41672f * s);
                    path.cubicTo(ox + 11.9999f * s, oy + 9.55479f * s, ox + 11.8873f * s, oy + 9.66637f * s, ox + 11.7493f * s, oy + 9.67008f * s);
                    path.cubicTo(ox + 8.09094f * s, oy + 9.76836f * s, ox + 4.97774f * s, oy + 12.0115f * s, ox + 3.66558f * s, oy + 15.1656f * s);
                    path.cubicTo(ox + 3.46812f * s, oy + 15.6402f * s, ox + 3.31145f * s, oy + 16.1354f * s, ox + 3.19984f * s, oy + 16.6471f * s);
                    path.cubicTo(ox + 3.07554f * s, oy + 17.217f * s, ox + 3.00713f * s, oy + 17.8072f * s, ox + 3.00053f * s, oy + 18.412f * s);
                    path.cubicTo(ox + 3.00018f * s, oy + 18.4442f * s, ox + 3f * s, oy + 18.4765f * s, ox + 3f * s, oy + 18.5088f * s);
                    path.cubicTo(ox + 3.00001f * s, oy + 18.6437f * s, ox + 3.18418f * s, oy + 18.6948f * s, ox + 3.25846f * s, oy + 18.5822f * s);
                    path.cubicTo(ox + 3.27467f * s, oy + 18.5577f * s, ox + 3.29101f * s, oy + 18.5332f * s, ox + 3.30747f * s, oy + 18.5088f * s);
                    path.cubicTo(ox + 3.30748f * s, oy + 18.5088f * s, ox + 3.30746f * s, oy + 18.5088f * s, ox + 3.30747f * s, oy + 18.5088f * s);
                    path.cubicTo(ox + 3.63446f * s, oy + 18.0244f * s, ox + 4.01059f * s, oy + 17.5765f * s, ox + 4.42994f * s, oy + 17.168f * s);
                    path.cubicTo(ox + 4.71487f * s, oy + 16.8905f * s, ox + 5.01975f * s, oy + 16.6313f * s, ox + 5.34276f * s, oy + 16.3912f * s);
                    path.cubicTo(ox + 7.05882f * s, oy + 15.1158f * s, ox + 9.28642f * s, oy + 14.3823f * s, ox + 11.7496f * s, oy + 14.3357f * s);
                    path.cubicTo(ox + 11.8877f * s, oy + 14.3331f * s, ox + 12f * s, oy + 14.4453f * s, ox + 12f * s, oy + 14.5834f * s);
                    path.lineTo(ox + 12f * s, oy + 18.4888f * s);
                    path.cubicTo(ox + 12f * s, oy + 18.6969f * s, ox + 12.2393f * s, oy + 18.8139f * s, ox + 12.4035f * s, oy + 18.6862f * s);
                    path.lineTo(ox + 20.7463f * s, oy + 12.1973f * s);
                    path.cubicTo(ox + 20.875f * s, oy + 12.0973f * s, ox + 20.875f * s, oy + 11.9028f * s, ox + 20.7463f * s, oy + 11.8027f * s);
                    path.lineTo(ox + 12.4034f * s, oy + 5.31385f * s);
                    path.close();

                    canvas.drawPath(path, glyphFill);
                    break;
                }
            }
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            border.setAlpha(alpha);
            line.setAlpha(alpha);
            glyphFill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fill.setColorFilter(colorFilter);
            border.setColorFilter(colorFilter);
            line.setColorFilter(colorFilter);
            glyphFill.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return 40;
        }

        @Override
        public int getIntrinsicHeight() {
            return 40;
        }
    }
}
