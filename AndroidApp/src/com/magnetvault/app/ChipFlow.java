package com.magnetvault.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/** 极简流式布局：子 View 放不下就换行（用于标签胶囊墙）。 */
public class ChipFlow extends ViewGroup {

    private int hGap;
    private int vGap;

    public ChipFlow(Context context) {
        super(context);
        init(context);
    }

    public ChipFlow(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context ctx) {
        hGap = Ui.dp(ctx, 8);
        vGap = Ui.dp(ctx, 8);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int childWidth = width - getPaddingLeft() - getPaddingRight();
        int lineWidth = 0;
        int lineHeight = 0;
        int totalHeight = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            measureChild(child, MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (lineWidth > 0 && lineWidth + hGap + w > childWidth) {
                totalHeight += lineHeight + vGap;
                lineWidth = w;
                lineHeight = h;
            } else {
                lineWidth += (lineWidth > 0 ? hGap : 0) + w;
                lineHeight = Math.max(lineHeight, h);
            }
        }
        totalHeight += lineHeight;
        setMeasuredDimension(width, totalHeight + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int childWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int lineHeight = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (x > getPaddingLeft() && x + w > getPaddingLeft() + childWidth) {
                x = getPaddingLeft();
                y += lineHeight + vGap;
                lineHeight = 0;
            }
            child.layout(x, y, x + w, y + h);
            x += w + hGap;
            lineHeight = Math.max(lineHeight, h);
        }
    }
}