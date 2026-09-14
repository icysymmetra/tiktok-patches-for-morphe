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
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class SupportUi {
    static final String SUPPORT_URL = "https://ko-fi.com/P5P5YOUU7";

    private SupportUi() {
    }

    static void openSupport() {
        app.morphe.extension.shared.Utils.openLink(SUPPORT_URL);
    }

    static View createPill(Context context) {
        LinearLayout pill = new LinearLayout(context);
        pill.setGravity(Gravity.CENTER);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setMinimumHeight(SettingsUi.dp(context, 34));
        pill.setPadding(
                SettingsUi.dp(context, 11),
                0,
                SettingsUi.dp(context, 13),
                0
        );
        pill.setBackground(new SupportBackgroundDrawable(context, 0, 0, 17));
        pill.setContentDescription("Support Metra patches");
        pill.setOnClickListener(view -> openSupport());

        ImageView heart = new ImageView(context);
        heart.setImageDrawable(new HeartDrawable());
        int iconSize = SettingsUi.dp(context, 16);
        pill.addView(heart, new LinearLayout.LayoutParams(iconSize, iconSize));

        TextView label = SettingsUi.text(context, "Support", 13.5f, SettingsUi.ACCENT, 1);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        labelParams.leftMargin = SettingsUi.dp(context, 6);
        pill.addView(label, labelParams);
        return pill;
    }

    static Drawable createHeartDrawable() {
        return new HeartDrawable();
    }

    static Drawable createRowBackground(Context context) {
        return new SupportBackgroundDrawable(context, 20, 12, 16);
    }

    private static final class HeartDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        HeartDrawable() {
            paint.setColor(SettingsUi.ACCENT);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas canvas) {
            float width = getBounds().width();
            float height = getBounds().height();
            float scale = Math.min(width, height) / 24f;

            path.reset();
            path.moveTo(12f, 21.2f);
            path.cubicTo(10.5f, 19.85f, 2f, 13.55f, 2f, 7.55f);
            path.cubicTo(2f, 4.45f, 4.45f, 2f, 7.55f, 2f);
            path.cubicTo(9.3f, 2f, 10.9f, 2.82f, 12f, 4.12f);
            path.cubicTo(13.1f, 2.82f, 14.7f, 2f, 16.45f, 2f);
            path.cubicTo(19.55f, 2f, 22f, 4.45f, 22f, 7.55f);
            path.cubicTo(22f, 13.55f, 13.5f, 19.85f, 12f, 21.2f);
            path.close();

            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(scale, scale);
            canvas.drawPath(path, paint);
            canvas.restore();
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

        @Override
        public int getIntrinsicWidth() {
            return 24;
        }

        @Override
        public int getIntrinsicHeight() {
            return 24;
        }
    }

    private static final class SupportBackgroundDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float horizontalInset;
        private final float verticalInset;
        private final float radius;

        SupportBackgroundDrawable(
                Context context,
                int horizontalInsetDp,
                int verticalInsetDp,
                int radiusDp
        ) {
            fill.setColor(SettingsUi.isDarkMode()
                    ? Color.argb(255, 35, 17, 25)
                    : Color.argb(255, 255, 247, 250));
            fill.setStyle(Paint.Style.FILL);
            line.setColor(SettingsUi.isDarkMode()
                    ? Color.argb(56, 240, 45, 99)
                    : Color.argb(52, 240, 45, 99));
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(1, SettingsUi.dp(context, 1)));
            horizontalInset = SettingsUi.dp(context, horizontalInsetDp);
            verticalInset = SettingsUi.dp(context, verticalInsetDp);
            radius = SettingsUi.dp(context, radiusDp);
        }

        @Override
        public void draw(Canvas canvas) {
            RectF bounds = new RectF(
                    getBounds().left + horizontalInset,
                    getBounds().top + verticalInset,
                    getBounds().right - horizontalInset,
                    getBounds().bottom - verticalInset
            );
            canvas.drawRoundRect(bounds, radius, radius, fill);
            canvas.drawRoundRect(bounds, radius, radius, line);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            line.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fill.setColorFilter(colorFilter);
            line.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
