/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The ad skip screens' icons: Material Icons (Apache 2.0) path data on a 24 x 24 grid, drawn
 * at any size and colour, so no PNG or font travels with the patch.
 */
final class AdIcons extends Drawable {
    enum Icon {
        CLOSE("M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"),
        PLAY("M8 5v14l11-7z"),
        PAUSE("M6 19h4V5H6v14zm8-14v14h4V5h-4z"),
        STOP("M6 6h12v12H6z"),
        HEADPHONES("M12 1c-4.97 0-9 4.03-9 9v7c0 1.66 1.34 3 3 3h3v-8H5v-2c0-3.87 3.13-7 7-7s7 3.13 7 7v2h-4v8h3c1.66 0 3-1.34 3-3v-7c0-4.97-4.03-9-9-9z"),
        ZOOM_IN("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14zm2.5-4h-2v2H9v-2H7V9h2V7h1v2h2v1z"),
        ZOOM_OUT("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14zM7 9h5v1H7z"),
        MINUS("M19 13H5v-2h14v2z"),
        PLUS("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"),
        MORE("M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"),
        THUMB_UP("M9 21h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.58 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2zM9 9l4.34-4.34L12 10h9v2l-3 7H9V9zM1 9h4v12H1z"),
        THUMB_DOWN("M15 3H6c-.83 0-1.54.5-1.84 1.22l-3.02 7.05c-.09.23-.14.47-.14.73v2c0 1.1.9 2 2 2h6.31l-.95 4.57-.03.32c0 .41.17.79.44 1.06L9.83 23l6.58-6.59c.37-.36.59-.86.59-1.41V5c0-1.1-.9-2-2-2zm0 12l-4.34 4.34L12 14H3v-2l3-7h9v10zm4-12h4v12h-4z"),
        THUMB_UP_FILLED("M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z"),
        THUMB_DOWN_FILLED("M15 3H6c-.83 0-1.54.5-1.84 1.22l-3.02 7.05c-.09.23-.14.47-.14.73v2c0 1.1.9 2 2 2h6.31l-.95 4.57-.03.32c0 .41.17.79.44 1.06L9.83 23l6.59-6.59c.36-.36.58-.86.58-1.41V5c0-1.1-.9-2-2-2zm4 0v12h4V3h-4z"),
        CLOUD("M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z");

        final String data;
        private Path parsed;

        Icon(String data) {
            this.data = data;
        }

        synchronized Path path() {
            if (parsed == null) parsed = parse(data);
            return parsed;
        }
    }

    private final Icon icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path scaled = new Path();
    private final Matrix matrix = new Matrix();
    private final int size;

    private AdIcons(Icon icon, int color, int sizePx) {
        this.icon = icon;
        this.size = sizePx;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        setBounds(0, 0, sizePx, sizePx);
    }

    static AdIcons of(Context context, Icon icon, int color, float sizeDp) {
        return new AdIcons(icon, color, AdSkipUi.dp(context, sizeDp));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float side = Math.min(b.width(), b.height());
        matrix.setScale(side / 24f, side / 24f);
        matrix.postTranslate(b.left + (b.width() - side) / 2f, b.top + (b.height() - side) / 2f);
        icon.path().transform(matrix, scaled);
        canvas.drawPath(scaled, paint);
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    void setColor(int color) {
        paint.setColor(color);
        invalidateSelf();
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** SVG path data with M, L, H, V, C, S, Q, T and Z, absolute and relative; no arcs. */
    static Path parse(String d) {
        Path path = new Path();
        int[] at = {0};
        int n = d.length();
        char cmd = 0;
        float x = 0, y = 0, startX = 0, startY = 0, ctrlX = 0, ctrlY = 0;
        char last = 0;
        while (true) {
            skipSeparators(d, at);
            if (at[0] >= n) break;
            char c = d.charAt(at[0]);
            if (Character.isLetter(c)) {
                cmd = c;
                at[0]++;
                if (cmd == 'Z' || cmd == 'z') {
                    path.close();
                    x = startX;
                    y = startY;
                    last = cmd;
                    continue;
                }
            } else if (cmd == 0) {
                throw new IllegalArgumentException("Path data starts with a number");
            }
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    x = ox + number(d, at);
                    y = oy + number(d, at);
                    path.moveTo(x, y);
                    startX = x;
                    startY = y;
                    cmd = rel ? 'l' : 'L';
                    break;
                }
                case 'L':
                    x = ox + number(d, at);
                    y = oy + number(d, at);
                    path.lineTo(x, y);
                    break;
                case 'H':
                    x = ox + number(d, at);
                    path.lineTo(x, y);
                    break;
                case 'V':
                    y = oy + number(d, at);
                    path.lineTo(x, y);
                    break;
                case 'C': {
                    float x1 = ox + number(d, at), y1 = oy + number(d, at);
                    ctrlX = ox + number(d, at);
                    ctrlY = oy + number(d, at);
                    x = ox + number(d, at);
                    y = oy + number(d, at);
                    path.cubicTo(x1, y1, ctrlX, ctrlY, x, y);
                    break;
                }
                case 'S': {
                    boolean smooth = "CcSs".indexOf(last) >= 0;
                    float x1 = smooth ? 2 * x - ctrlX : x, y1 = smooth ? 2 * y - ctrlY : y;
                    ctrlX = ox + number(d, at);
                    ctrlY = oy + number(d, at);
                    x = ox + number(d, at);
                    y = oy + number(d, at);
                    path.cubicTo(x1, y1, ctrlX, ctrlY, x, y);
                    break;
                }
                case 'Q':
                    ctrlX = ox + number(d, at);
                    ctrlY = oy + number(d, at);
                    x = ox + number(d, at);
                    y = oy + number(d, at);
                    path.quadTo(ctrlX, ctrlY, x, y);
                    break;
                case 'T': {
                    boolean smooth = "QqTt".indexOf(last) >= 0;
                    ctrlX = smooth ? 2 * x - ctrlX : x;
                    ctrlY = smooth ? 2 * y - ctrlY : y;
                    x = ox + number(d, at);
                    y = oy + number(d, at);
                    path.quadTo(ctrlX, ctrlY, x, y);
                    break;
                }
                default:
                    throw new IllegalArgumentException("Unsupported path command " + cmd);
            }
            last = cmd;
        }
        return path;
    }

    private static void skipSeparators(String d, int[] at) {
        while (at[0] < d.length()) {
            char c = d.charAt(at[0]);
            if (c != ' ' && c != ',' && c != '\n' && c != '\t' && c != '\r') break;
            at[0]++;
        }
    }

    private static float number(String d, int[] at) {
        skipSeparators(d, at);
        int start = at[0], i = start, n = d.length();
        if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
        boolean dot = false;
        while (i < n) {
            char c = d.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' && !dot) {
                dot = true;
                i++;
            } else if ((c == 'e' || c == 'E') && i + 1 < n) {
                i++;
                if (d.charAt(i) == '-' || d.charAt(i) == '+') i++;
            } else {
                break;
            }
        }
        if (i == start) throw new IllegalArgumentException("Expected a number at " + start + " in " + d);
        at[0] = i;
        return Float.parseFloat(d.substring(start, i));
    }
}
