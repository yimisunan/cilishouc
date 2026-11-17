package com.magnetvault.app;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Shared binder for entry list cards across MainActivity and SearchActivity. */
public final class EntryCard {

    public interface Actions {
        void onOpen(Entry entry);

        void onCopy(Entry entry);

        void onToggleStar(Entry entry);

        void onDetail(Entry entry);

        void onMore(Entry entry);
    }

    public static final class Holder {
        final View card, badge, focusRow, actions, actOpen, actCopy, actStar, actDetail, actMore;
        final LinearLayout contentCol;
        final ImageView icon, starMark, chev, copyBtn, actStarIcon;
        final TextView code, spec1, spec2, title, proto, sub, note, actStarLabel;

        Holder(View root) {
            card = root.findViewById(R.id.entry_card);
            badge = root.findViewById(R.id.item_badge);
            icon = root.findViewById(R.id.item_icon);
            contentCol = root.findViewById(R.id.item_content_col);
            focusRow = root.findViewById(R.id.item_focus_row);
            code = root.findViewById(R.id.item_code);
            spec1 = root.findViewById(R.id.item_spec_1);
            spec2 = root.findViewById(R.id.item_spec_2);
            title = root.findViewById(R.id.item_title);
            proto = root.findViewById(R.id.item_proto);
            starMark = root.findViewById(R.id.item_star_mark);
            sub = root.findViewById(R.id.item_sub);
            chev = root.findViewById(R.id.item_chev);
            note = root.findViewById(R.id.item_note);
            copyBtn = root.findViewById(R.id.item_copy);
            actions = root.findViewById(R.id.item_actions);
            actOpen = root.findViewById(R.id.act_open);
            actCopy = root.findViewById(R.id.act_copy);
            actStar = root.findViewById(R.id.act_star);
            actStarIcon = root.findViewById(R.id.act_star_icon);
            actStarLabel = root.findViewById(R.id.act_star_label);
            actDetail = root.findViewById(R.id.act_detail);
            actMore = root.findViewById(R.id.act_more);
        }
    }

    private EntryCard() {}

    public static View inflate(Activity act, ViewGroup parent) {
        View view = act.getLayoutInflater().inflate(R.layout.item_entry, parent, false);
        Holder h = new Holder(view);
        Design.tactile(h.copyBtn);
        Design.tactile(h.actOpen);
        Design.tactile(h.actCopy);
        Design.tactile(h.actStar);
        Design.tactile(h.actDetail);
        Design.tactile(h.actMore);
        view.setTag(h);
        return view;
    }

    public static void bind(
            Activity act,
            View view,
            Entry e,
            boolean compactMode,
            boolean expanded,
            boolean selecting,
            boolean picked,
            String contextCategoryId,
            Actions listener) {
        Holder h = (Holder) view.getTag();
        boolean ed2k = MagnetParser.isEd2k(e.magnet);
        boolean showExpanded = !selecting && expanded;

        // Card background & selection badge
        h.card.setBackgroundResource(picked ? R.drawable.bg_card_selected : R.drawable.bg_card);
        if (selecting) {
            h.badge.setBackgroundResource(R.drawable.bg_badge_magnet);
            h.icon.setImageResource(picked ? R.drawable.ic_check : R.drawable.ic_circle);
            h.icon.setColorFilter(Design.color(act, picked ? R.color.c_accent : R.color.c_text2));
        } else {
            h.badge.setBackgroundResource(
                    ed2k ? R.drawable.bg_badge_ed2k : R.drawable.bg_badge_magnet);
            h.icon.setImageResource(ed2k ? R.drawable.ic_file : R.drawable.ic_magnet);
            h.icon.setColorFilter(
                    Design.color(act, ed2k ? R.color.c_accent2 : R.color.c_accent));
        }
        view.setSelected(picked);

        // Title & focus parsing
        String rawTitle = e.displayTitle();
        TitleFormat.Parsed parsed = compactMode ? TitleFormat.parse(rawTitle) : null;
        if (compactMode && parsed != null && parsed.hasFocus()) {
            boolean showCode = !parsed.code.isEmpty();
            boolean showSpecs = !showExpanded && !parsed.badges.isEmpty();
            if (showCode || showSpecs) {
                h.focusRow.setVisibility(View.VISIBLE);
            } else {
                h.focusRow.setVisibility(View.GONE);
            }
            if (showCode) {
                h.code.setVisibility(View.VISIBLE);
                h.code.setText(parsed.code);
                h.code.setTextColor(
                        Design.color(act, ed2k ? R.color.c_accent2 : R.color.c_accent));
                h.code.setBackgroundResource(
                        ed2k ? R.drawable.bg_badge_ed2k : R.drawable.bg_badge_magnet);
            } else {
                h.code.setVisibility(View.GONE);
            }
            if (showSpecs) {
                bindSpecs(act, h, parsed.badges);
            } else {
                h.spec1.setVisibility(View.GONE);
                h.spec2.setVisibility(View.GONE);
            }

            if (showExpanded) {
                h.title.setVisibility(View.VISIBLE);
                h.title.setText(rawTitle);
            } else if (parsed.summary.isEmpty()) {
                h.title.setVisibility(View.GONE);
            } else {
                h.title.setVisibility(View.VISIBLE);
                h.title.setText(parsed.summary);
            }
        } else {
            h.focusRow.setVisibility(View.GONE);
            h.code.setVisibility(View.GONE);
            h.spec1.setVisibility(View.GONE);
            h.spec2.setVisibility(View.GONE);
            h.title.setVisibility(View.VISIBLE);
            h.title.setText(rawTitle);
        }

        if (!compactMode || showExpanded) {
            h.title.setMaxLines(Integer.MAX_VALUE);
            h.title.setEllipsize(null);
        } else {
            h.title.setMaxLines(2);
            h.title.setEllipsize(TextUtils.TruncateAt.END);
        }

        // Protocol & star dot
        h.proto.setText(ed2k ? "ED2K" : "BT");
        h.proto.setTextColor(Design.color(act, ed2k ? R.color.c_accent2 : R.color.c_accent));
        h.proto.setBackgroundResource(ed2k ? R.drawable.bg_badge_ed2k : R.drawable.bg_badge_magnet);
        h.starMark.setVisibility(e.starred ? View.VISIBLE : View.GONE);

        // Metadata line: hide redundant folder name inside folder view when collapsed
        h.sub.setText(buildMeta(e, ed2k, contextCategoryId, showExpanded));
        if (showExpanded) {
            h.sub.setMaxLines(3);
            h.sub.setEllipsize(null);
        } else {
            h.sub.setMaxLines(1);
            h.sub.setEllipsize(TextUtils.TruncateAt.END);
        }

        // Expand chevron & quick copy button
        LinearLayout.LayoutParams cp = (LinearLayout.LayoutParams) h.contentCol.getLayoutParams();
        if (selecting) {
            h.copyBtn.setVisibility(View.GONE);
            h.chev.setVisibility(View.GONE);
            cp.rightMargin = Ui.dp(act, 6);
        } else {
            h.copyBtn.setVisibility(View.VISIBLE);
            h.copyBtn.setContentDescription("复制链接 " + rawTitle);
            h.copyBtn.setOnClickListener(v -> listener.onCopy(e));
            h.chev.setVisibility(View.VISIBLE);
            Design.rotateTo(h.chev, showExpanded ? 180f : 0f);
            cp.rightMargin = Ui.dp(act, 2);
        }
        h.contentCol.setLayoutParams(cp);

        // Expanded note & inline action bar
        if (showExpanded && !TextUtils.isEmpty(e.note)) {
            h.note.setVisibility(View.VISIBLE);
            h.note.setText("备注：" + e.note.trim());
        } else {
            h.note.setVisibility(View.GONE);
        }

        if (showExpanded) {
            h.actions.setVisibility(View.VISIBLE);
            h.actStarIcon.setImageResource(
                    e.starred ? R.drawable.ic_star : R.drawable.ic_star_outline);
            int starColor = Design.color(act, e.starred ? R.color.c_star : R.color.c_text2);
            h.actStarIcon.setColorFilter(starColor);
            h.actStarLabel.setTextColor(starColor);
            h.actStarLabel.setText(e.starred ? "已星标" : "星标");
            h.actOpen.setOnClickListener(v -> listener.onOpen(e));
            h.actCopy.setOnClickListener(v -> listener.onCopy(e));
            h.actStar.setOnClickListener(
                    v -> {
                        Design.popBounce(h.actStarIcon);
                        listener.onToggleStar(e);
                    });
            h.actDetail.setOnClickListener(v -> listener.onDetail(e));
            h.actMore.setOnClickListener(v -> listener.onMore(e));
        } else {
            h.actions.setVisibility(View.GONE);
        }
    }

    private static void bindSpecs(Activity act, Holder h, List<String> badges) {
        if (badges == null || badges.isEmpty()) {
            h.spec1.setVisibility(View.GONE);
            h.spec2.setVisibility(View.GONE);
            return;
        }
        h.spec1.setVisibility(View.VISIBLE);
        h.spec1.setText(badges.get(0));
        h.spec1.setMaxWidth(Ui.dp(act, 136));
        if (badges.size() == 1) {
            h.spec2.setVisibility(View.GONE);
        } else if (badges.size() == 2) {
            h.spec2.setVisibility(View.VISIBLE);
            h.spec2.setText(badges.get(1));
            h.spec2.setMaxWidth(Ui.dp(act, 136));
        } else {
            h.spec2.setVisibility(View.VISIBLE);
            h.spec2.setText(badges.get(1) + " · " + badges.get(2));
            h.spec2.setMaxWidth(Ui.dp(act, 160));
        }
    }

    private static String buildMeta(
            Entry e, boolean ed2k, String contextCategoryId, boolean expanded) {
        List<String> parts = new ArrayList<>();
        boolean sameFolder = contextCategoryId != null && contextCategoryId.equals(e.categoryId);
        if (!sameFolder || expanded) {
            parts.add(e.categoryId == null ? "未分类" : e.categoryName);
        }
        if (ed2k) {
            long sz = MagnetParser.xlSize(e.magnet);
            if (sz > 0) parts.add(Ui.sizeText(sz));
        }
        if (!e.tags.isEmpty()) {
            parts.add(e.tagText());
        }
        if (expanded || parts.isEmpty()) {
            parts.add(new SimpleDateFormat("MM月dd日", Locale.CHINA).format(new Date(e.createdAt)));
        }
        return TextUtils.join(" · ", parts);
    }
}
